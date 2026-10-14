package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Stopping, steering and continuing a delegated run (FN-0410) and the server ending a run whose
 * connector went silent (contract v1 "실행 권리 청구" 5·6, "실행이 끊겼을 때", "실행마다 별도 작업
 * 트리" 6), plus the readiness check's end report (FN-0406). Against a real PostgreSQL 17.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["zm-baton.run-lost-grace-seconds=60", "zm-baton.run-sweep-ms=3600000"])
class RunControlTests @Autowired constructor(
	private val delegations: DelegationService,
	private val control: RunControlService,
	private val runs: RunService,
	private val reads: ReadService,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
	private val jdbc: JdbcClient,
) {

	private val commit = "b".repeat(40)

	private data class Setup(val device: String, val repoKey: String, val workId: String)

	private fun setup(): Setup {
		val device = sessions.registerDevice(RegisterDevice("control-pc", "windows")).deviceId
		val key = "git:" + UUID.randomUUID().toString().replace("-", "") + "00000000"
		delegations.addRepository(device, RepositoryUpsert(key, "demo"))
		val work = workItems.create(CreateWorkItem("제어 작업", "usr_owner", acceptance = "a.txt를 만든다", initialStage = "ready", executorKind = "human"))
		return Setup(device, key, work.id)
	}

	private fun version(workId: String) = workItems.detail(workId).item.version

	private fun delegate(s: Setup, device: String = s.device, note: String? = null) =
		delegations.delegate(s.workId, DelegateIn("claude_code", device, s.repoKey, "main", listOf("Read", "Edit"), version(s.workId), note))

	private fun newestRun(workId: String): String =
		jdbc.sql("select id from runs where work_item_id = :id order by created_at desc, id desc limit 1").param("id", workId)
			.query(String::class.java).single()

	private fun event(gen: Long, seq: Long, kind: String, payload: Map<String, Any?>) =
		EventIn("evt_" + UUID.randomUUID(), gen, seq, kind, payload)

	private fun ingest(runId: String, vararg events: EventIn) = runs.ingest(runId, EventBatch(2, events.toList()))

	private fun started(gen: Long, s: Setup, runId: String, session: String = UUID.randomUUID().toString(), liveInput: Boolean = true) =
		event(gen, 1, "lifecycle", mapOf("phase" to "started", "session_id" to session, "executor" to "claude_code", "pid" to 7,
			"capabilities" to mapOf("live_input" to liveInput, "resume" to true),
			"workspace" to mapOf("repo_key" to s.repoKey, "base_commit" to commit, "branch" to "zm-baton/$runId", "base_ref" to "main", "origin" to "new")))

	private fun ended(gen: Long, seq: Long, reason: String, started: Boolean, result: Boolean, extra: Map<String, Any?> = emptyMap()) =
		event(gen, seq, "lifecycle", mapOf("phase" to "ended", "end_reason" to reason, "executor_started" to started,
			"result_seen" to result) + extra)

	private fun waits(workId: String) = workItems.detail(workId).item.waitReasons

	private fun view(workId: String, runId: String) = reads.runs(workId, 20).single { it.id == runId }

	private fun lapse(runId: String, secondsAgo: Int) {
		jdbc.sql("update runs set lease_expires_at = now() - make_interval(secs => :s) where id = :id")
			.param("s", secondsAgo).param("id", runId).update()
	}

	/** A started run: delegated, claimed and reported started. */
	private fun running(s: Setup, liveInput: Boolean = true, session: String = UUID.randomUUID().toString()): Pair<String, Long> {
		delegate(s)
		val runId = newestRun(s.workId)
		val gen = runs.claim(runId, s.device).generation
		ingest(runId, started(gen, s, runId, session, liveInput))
		return runId to gen
	}

	private fun <T> withSkipped(stages: List<String>, block: () -> T): T {
		val before = workItems.workflow().skippedStages
		workItems.setWorkflow(WorkflowSettings(stages))
		try {
			return block()
		} finally {
			workItems.setWorkflow(WorkflowSettings(before))
		}
	}

	// ---------------------------------------------------------------- lease and liveness

	@Test
	fun `the holder renews the lease, anyone else and an ended run are refused`() {
		val s = setup()
		delegate(s)
		val runId = newestRun(s.workId)
		val gen = runs.claim(runId, s.device).generation
		lapse(runId, 10)
		val out = runs.renew(runId, LeaseIn(s.device, gen))
		assertEquals("claimed", out.state)
		assertEquals(RunService.LEASE_SECONDS, out.leaseSeconds)
		assertNull(out.stop)
		assertTrue(out.inputs.isEmpty())
		assertFalse(view(s.workId, runId).leaseLapsed)

		assertFailsWith<ConflictException> { runs.renew(runId, LeaseIn("dev_other", gen)) }
		assertFailsWith<ConflictException> { runs.renew(runId, LeaseIn(s.device, gen + 1)) }
		ingest(runId, ended(gen, 1, "failed", false, false))
		val e = assertFailsWith<ConflictException> { runs.renew(runId, LeaseIn(s.device, gen)) }
		assertContains(e.message!!, "not held by this device")
	}

	@Test
	fun `a run whose lease lapsed past the grace ends lost with outcome_unknown, a shorter lapse only shows`() {
		val s = setup()
		val (runId, gen) = running(s)
		control.sendInput(runId, RunInputIn("남은 것도 해 줘"))
		lapse(runId, 30) // within the 60-second grace of this test
		assertTrue(runs.sweepLost().none { it == runId })
		val lapsed = view(s.workId, runId)
		assertEquals("running", lapsed.state)
		assertTrue(lapsed.leaseLapsed)
		assertNotNull(lapsed.lostAt)

		lapse(runId, 120)
		assertTrue(runId in runs.sweepLost())
		val lost = view(s.workId, runId)
		assertEquals("ended", lost.state)
		assertEquals("lost", lost.endReason)
		assertFalse(lost.leaseLapsed)
		assertNull(lost.actions)
		assertEquals("undelivered", lost.inputs.single().state)
		val item = workItems.detail(s.workId)
		assertEquals("in_progress", item.item.stage)
		assertEquals(listOf("outcome_unknown"), item.item.waitReasons)
		assertEquals("run.lost", item.history.first().action)
		assertEquals("system", item.history.first().actorKind)
		// The connector coming back hears that the run is gone; its late end report changes nothing.
		assertFailsWith<ConflictException> { runs.renew(runId, LeaseIn(s.device, gen)) }
		assertEquals(EventResult(1, 0, 0, 0), ingest(runId, ended(gen, 2, "cancelled", true, false)))
		assertEquals("lost", view(s.workId, runId).endReason)
		// Sweeping again finds nothing new.
		assertTrue(runs.sweepLost().none { it == runId })
		// The work item can be handed on again: it continues the lost run.
		val again = workItems.detail(s.workId).delegation
		assertTrue(again.allowed)
		assertEquals("resume", again.mode)
		assertEquals(runId, again.resumeFrom!!.runId)
		assertEquals("lost", again.resumeFrom.endReason)
	}

	// ---------------------------------------------------------------- cancel

	@Test
	fun `cancelling a running run asks the connector to stop and the end report decides the rest`() {
		val s = setup()
		val (runId, gen) = running(s)
		val before = view(s.workId, runId)
		assertTrue(before.actions!!.cancel.allowed)

		val after = control.cancel(runId, version(s.workId))
		assertEquals("work_item.run_cancel_requested", after.history.first().action)
		val stopping = view(s.workId, runId)
		assertEquals("stopping", stopping.state)
		assertNotNull(stopping.delegation!!.revokedAt)
		assertFalse(stopping.actions!!.cancel.allowed)
		assertContains(stopping.actions.cancel.reason!!, "이미 취소를 요청했다")
		assertFalse(stopping.actions.sendInput.allowed)
		val e = assertFailsWith<ConflictException> { control.cancel(runId, version(s.workId)) }
		assertContains(e.message!!, "이미 취소를 요청했다")
		assertFailsWith<ConflictException> { control.sendInput(runId, RunInputIn("계속해")) }

		val lease = runs.renew(runId, LeaseIn(s.device, gen))
		assertEquals("stopping", lease.state)
		assertEquals("cancel", lease.stop)

		// The executor stopped before its result: cancelled, and the work item needs a look.
		ingest(runId, ended(gen, 2, "cancelled", true, false))
		val ended = view(s.workId, runId)
		assertEquals("cancelled", ended.endReason)
		val item = workItems.detail(s.workId)
		assertEquals("in_progress", item.item.stage)
		assertEquals(listOf("outcome_unknown"), item.item.waitReasons)
		assertEquals("resume", item.delegation.mode)
		assertFailsWith<ConflictException> { control.cancel(runId, version(s.workId)) }
	}

	@Test
	fun `cancelling before the executor started leaves the work ready, and a requested run is taken back`() {
		val s = setup()
		delegate(s)
		val runId = newestRun(s.workId)
		val gen = runs.claim(runId, s.device).generation
		control.cancel(runId, version(s.workId))
		ingest(runId, ended(gen, 1, "cancelled", false, false))
		val item = workItems.detail(s.workId)
		assertEquals("ready", item.item.stage)
		assertTrue(item.item.waitReasons.isEmpty())
		assertEquals("first", item.delegation.mode)

		delegate(s)
		val requested = newestRun(s.workId)
		val back = control.cancel(requested, version(s.workId))
		assertEquals("work_item.delegation_withdrawn", back.history.first().action)
		assertEquals("cancelled", view(s.workId, requested).endReason)
		// A stale version is refused before anything changes.
		delegate(s)
		val third = newestRun(s.workId)
		runs.claim(third, s.device)
		assertFailsWith<ConflictException> { control.cancel(third, version(s.workId) - 1) }
		assertEquals("claimed", view(s.workId, third).state)
		assertFailsWith<NotFoundException> { control.cancel("run_nope", 0) }
	}

	@Test
	fun `a work item with a live run cannot be cancelled until the run is stopped`() {
		val s = setup()
		running(s)
		val move = workItems.detail(s.workId).moves.single { it.target == "cancelled" }
		assertFalse(move.allowed)
		assertContains(move.reason!!, "실행 취소")
	}

	// ---------------------------------------------------------------- instructions while running

	@Test
	fun `an instruction reaches the connector at its next renewal and its state follows the reports`() {
		val s = setup()
		val (runId, gen) = running(s)
		val first = control.sendInput(runId, RunInputIn("  README도 고친다  "))
		assertEquals("README도 고친다", first.text)
		assertEquals("pending", first.state)
		val second = control.sendInput(runId, RunInputIn("테스트 파일은 건드리지 않는다"))
		assertFailsWith<BadRequestException> { control.sendInput(runId, RunInputIn("   ")) }
		assertFailsWith<BadRequestException> { control.sendInput(runId, RunInputIn("x".repeat(2001))) }
		assertEquals("run.input_sent", workItems.detail(s.workId).history.first().action)

		val lease = runs.renew(runId, LeaseIn(s.device, gen))
		assertEquals(listOf(first.id, second.id), lease.inputs.map { it.id })
		assertEquals("README도 고친다", lease.inputs.first().text)
		// The connector wrote the first one; the second waits.
		val next = runs.renew(runId, LeaseIn(s.device, gen, listOf(InputReport(first.id, "sent"), InputReport("inp_foreign", "delivered"))))
		assertEquals(listOf(second.id), next.inputs.map { it.id })
		assertEquals("sent", view(s.workId, runId).inputs.first().state)
		// A report never moves an instruction back.
		runs.renew(runId, LeaseIn(s.device, gen, listOf(InputReport(first.id, "delivered"))))
		runs.renew(runId, LeaseIn(s.device, gen, listOf(InputReport(first.id, "sent"))))
		val delivered = view(s.workId, runId).inputs.first()
		assertEquals("delivered", delivered.state)
		assertNotNull(delivered.deliveredAt)

		// The end report settles the rest: what the executor did not take is undelivered.
		ingest(runId, ended(gen, 2, "submitted", true, true, mapOf("inputs" to listOf(mapOf("id" to second.id, "state" to "sent")))))
		assertEquals(listOf("delivered", "undelivered"), view(s.workId, runId).inputs.map { it.state })
		assertFailsWith<ConflictException> { control.sendInput(runId, RunInputIn("늦었다")) }
	}

	@Test
	fun `an instruction needs a started executor whose adapter takes input`() {
		val s = setup()
		delegate(s)
		val runId = newestRun(s.workId)
		val gen = runs.claim(runId, s.device).generation
		val claimed = view(s.workId, runId).actions!!
		assertFalse(claimed.sendInput.allowed)
		assertContains(claimed.sendInput.reason!!, "아직 시작되지 않았다")
		assertFailsWith<ConflictException> { control.sendInput(runId, RunInputIn("지금")) }

		ingest(runId, started(gen, s, runId, liveInput = false))
		val old = view(s.workId, runId).actions!!
		assertFalse(old.sendInput.allowed)
		assertContains(old.sendInput.reason!!, "실행 중 지시를 받지 않는다")
		val e = assertFailsWith<ConflictException> { control.sendInput(runId, RunInputIn("지금")) }
		assertEquals(old.sendInput.reason, e.message)
	}

	// ---------------------------------------------------------------- continuing a work item

	@Test
	fun `a cut run is continued on the same device with its session and worktree, and the waits come off`() {
		val s = setup()
		val session = UUID.randomUUID().toString()
		val (cut, gen) = running(s, session = session)
		ingest(cut, ended(gen, 2, "failed", true, false))
		assertEquals(listOf("outcome_unknown"), waits(s.workId))

		val after = delegate(s, note = "테스트도 고친다")
		assertTrue(after.item.waitReasons.isEmpty())
		assertEquals("in_progress", after.item.stage)
		val history = after.history.first()
		assertEquals("work_item.delegated", history.action)
		assertEquals("resume_after_cut", history.details["continue_reason"])
		assertEquals(cut, history.details["resumed_from_run_id"])
		val next = newestRun(s.workId)
		val v = view(s.workId, next)
		assertEquals("resume_after_cut", v.continueReason)
		assertEquals(cut, v.resumedFromRunId)
		assertEquals("main", v.delegation!!.baseBranch)
		for (part in listOf("이어서 하는 실행이다", cut, "끊기거나 멈췄다", "사람이 덧붙인 지시:", "테스트도 고친다", "행동 규범:")) {
			assertContains(v.instruction!!, part)
		}

		val claim = runs.claim(next, s.device)
		val resume = assertNotNull(claim.resume)
		assertEquals(cut, resume.runId)
		assertEquals("resume_after_cut", resume.continueReason)
		assertTrue(resume.sameDevice)
		assertEquals(session, resume.sessionId)
		assertEquals("zm-baton/$cut", resume.workspace!!["branch"])
		// The continuing run starts: the stage stays in_progress.
		ingest(next, started(claim.generation, s, next))
		assertEquals("in_progress", workItems.detail(s.workId).item.stage)
	}

	@Test
	fun `a continuing run on another device gets neither the session nor the worktree`() {
		val s = setup()
		val (cut, gen) = running(s)
		ingest(cut, ended(gen, 2, "lost", true, false))
		val other = sessions.registerDevice(RegisterDevice("other-pc", "windows")).deviceId
		delegations.addRepository(other, RepositoryUpsert(s.repoKey, "demo"))
		delegate(s, device = other)
		val claim = runs.claim(newestRun(s.workId), other)
		val resume = assertNotNull(claim.resume)
		assertFalse(resume.sameDevice)
		assertNull(resume.sessionId)
		assertNull(resume.workspace)
	}

	@Test
	fun `a result in review goes back with a reason and the next run continues it`() = withSkipped(listOf("plan_review")) {
		val s = setup()
		val (first, gen) = running(s)
		ingest(first, ended(gen, 2, "submitted", true, true))
		val review = workItems.detail(s.workId)
		assertEquals("result_review", review.item.stage)
		assertEquals("reject_and_resume", review.delegation.mode)
		assertTrue(review.delegation.needsNote)
		assertEquals(first, review.delegation.resumeFrom!!.runId)
		assertEquals(listOf("Read", "Edit"), review.delegation.resumeFrom.allowedTools)
		assertFailsWith<BadRequestException> { delegate(s) }
		assertEquals("result_review", workItems.detail(s.workId).item.stage)

		val back = delegate(s, note = "a.txt의 둘째 줄이 빠졌다")
		assertEquals("in_progress", back.item.stage)
		val sentBack = back.history.single { it.action == "work_item.stage_changed" && it.details["from"] == "result_review" }
		assertEquals("a.txt의 둘째 줄이 빠졌다", sentBack.details["note"])
		val next = view(s.workId, newestRun(s.workId))
		assertEquals("result_rejected", next.continueReason)
		assertContains(next.instruction!!, "사람이 이전 결과를 돌려보냈다")
		assertContains(next.instruction, "a.txt의 둘째 줄이 빠졌다")
	}

	@Test
	fun `after a person sent a result back by hand, the next run reads the reason`() = withSkipped(listOf("plan_review")) {
		val s = setup()
		val (first, gen) = running(s)
		ingest(first, ended(gen, 2, "submitted", true, true))
		workItems.transition(s.workId, TransitionIn("in_progress", version(s.workId), "제목이 틀렸다"))
		val plan = workItems.detail(s.workId).delegation
		assertEquals("resume", plan.mode)
		assertFalse(plan.needsNote)
		delegate(s)
		val next = view(s.workId, newestRun(s.workId))
		assertEquals("result_rejected", next.continueReason)
		assertContains(next.instruction!!, "돌려보낸 이유: 제목이 틀렸다")
		assertFalse(next.instruction.contains("사람이 덧붙인 지시"))
	}

	@Test
	fun `a work item a person carries out is not taken over by a continuing run`() {
		val s = setup()
		workItems.transition(s.workId, TransitionIn("in_progress", version(s.workId)))
		val plan = workItems.detail(s.workId).delegation
		assertFalse(plan.allowed)
		assertNull(plan.mode)
		assertContains(plan.reason!!, "다시 계획하기")
		assertFailsWith<ConflictException> { delegate(s) }
	}

	// ---------------------------------------------------------------- readiness

	@Test
	fun `a failed readiness check ends the run before the executor and says why`() {
		val s = setup()
		delegate(s)
		val runId = newestRun(s.workId)
		val gen = runs.claim(runId, s.device).generation
		val readiness = mapOf("ok" to false, "checks" to listOf(
			mapOf("name" to "executor", "ok" to true, "detail" to "Claude Code 2.1.296"),
			mapOf("name" to "base_branch", "ok" to false, "detail" to "기준 브랜치 main이 이 저장소에 없다"),
		))
		// A detail with a path on the device is refused.
		val withPath = mapOf("ok" to false, "checks" to listOf(mapOf("name" to "repository", "ok" to false, "detail" to "C:\\Users\\me\\repo가 없다")))
		assertEquals(EventResult(0, 0, 0, 1), ingest(runId, ended(gen, 1, "failed", false, false, mapOf("readiness" to withPath))))
		assertEquals(EventResult(1, 0, 0, 0), ingest(runId, ended(gen, 2, "failed", false, false, mapOf("readiness" to readiness))))
		val item = workItems.detail(s.workId)
		assertEquals("ready", item.item.stage)
		assertEquals(listOf("readiness_failed"), item.item.waitReasons)
		val v = view(s.workId, runId)
		assertEquals(false, v.readiness!!["ok"])
		assertEquals(2, (v.readiness["checks"] as List<*>).size)
		// Handing it over again takes the wait off; the next run's own check decides again.
		assertTrue(delegate(s).item.waitReasons.isEmpty())
	}

	@Test
	fun `every norm of the norms file ends every instruction, in order`() {
		val norms = Instructions.norms
		assertEquals(7, norms.size)
		assertTrue(norms.all { it.first().isDigit() })
		val text = Instructions.build(Instructions.Item("wrk_1", "제목", null, null, null, null, null))
		val tail = text.substringAfter("행동 규범:\n")
		assertEquals(norms.joinToString("\n"), tail)
		for (part in listOf("AI 에이전트", "최종 책임은 사람", "Git 작업 트리", "push하지 않는다", "다른 방법으로 돌아가지 않는다",
			"가정 목록", "사람이 보낸 지시", "확인하지 못한 것")) {
			assertContains(tail, part)
		}
		assertEquals(listOf("1. a", "2. b"), Instructions.parseNorms("# 설명\n\n1. a\r\n  # 또\n2. b\n"))
	}
}
