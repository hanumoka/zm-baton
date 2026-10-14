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
 * Handing a work item to an AI (contract v1 "위임", "실행 권리 청구", "실행마다 별도 작업 트리"):
 * the delegation, the claim by the named device only, the run's worktree and Git change list,
 * and the work item following the run. Against a real PostgreSQL 17.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class DelegationTests @Autowired constructor(
	private val delegations: DelegationService,
	private val runs: RunService,
	private val reads: ReadService,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
	private val jdbc: JdbcClient,
) {

	private val commit = "a".repeat(40)

	private fun repoKey() = "git:" + UUID.randomUUID().toString().replace("-", "") + "00000000"

	private data class Setup(val device: String, val repoKey: String, val workId: String)

	/** A device with one repository and a `ready` work item made here. */
	private fun setup(acceptance: String? = "README에 한 줄을 더한다", stage: String = "ready"): Setup {
		val device = sessions.registerDevice(RegisterDevice("handoff-pc", "windows"), person = SessionService.OWNER).deviceId
		val key = repoKey()
		delegations.addRepository(device, RepositoryUpsert(key, "demo", "https://me:secret@github.com/me/demo.git?x=1"), actor = SessionService.OWNER)
		val work = workItems.create(
			CreateWorkItem("데모 작업", "usr_owner", acceptance = acceptance, nextAction = "README를 연다",
				initialStage = stage, executorKind = "human"),
			actor = SessionService.OWNER,
		)
		return Setup(device, key, work.id)
	}

	private fun version(workId: String) = workItems.detail(workId).item.version

	private fun delegate(s: Setup, tools: List<String>? = listOf("Read", "Edit", "Write"), branch: String? = "main") =
		delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, branch, tools, version(s.workId)), actor = SessionService.OWNER)

	private fun runOf(workId: String): String =
		jdbc.sql("select id from runs where work_item_id = :id order by created_at desc limit 1").param("id", workId)
			.query(String::class.java).single()

	private fun event(gen: Long, seq: Long, kind: String, payload: Map<String, Any?>) =
		EventIn("evt_" + UUID.randomUUID(), gen, seq, kind, payload)

	private fun ingest(runId: String, vararg events: EventIn) = runs.ingest(runId, EventBatch(2, events.toList()))

	private fun workspace(s: Setup, runId: String) =
		mapOf("repo_key" to s.repoKey, "base_commit" to commit, "branch" to "zm-baton/$runId", "base_ref" to "main")

	private fun started(gen: Long, s: Setup, runId: String) =
		event(gen, 1, "lifecycle", mapOf("phase" to "started", "session_id" to UUID.randomUUID().toString(),
			"executor" to "claude_code", "pid" to 42, "workspace" to workspace(s, runId)))

	private fun ended(gen: Long, seq: Long, reason: String, executorStarted: Boolean, resultSeen: Boolean) =
		event(gen, seq, "lifecycle", mapOf("phase" to "ended", "end_reason" to reason,
			"executor_started" to executorStarted, "result_seen" to resultSeen, "exit_code" to 0))

	private fun waits(workId: String) = workItems.detail(workId).item.waitReasons

	private fun <T> withSkipped(stages: List<String>, block: () -> T): T {
		val before = workItems.workflow().skippedStages
		workItems.setWorkflow(WorkflowSettings(stages), actor = SessionService.OWNER)
		try {
			return block()
		} finally {
			workItems.setWorkflow(WorkflowSettings(before), actor = SessionService.OWNER)
		}
	}

	@Test
	fun `a ready work item becomes a requested run with an instruction and the manager as executor`() {
		val s = setup()
		val before = workItems.detail(s.workId)
		assertTrue(before.delegation.allowed)
		assertNull(before.delegation.reason)

		val after = delegate(s)
		assertEquals("manager", after.item.executorKind)
		assertEquals(DelegationService.MANAGER, after.executorId)
		assertEquals(before.item.version + 1, after.item.version)
		assertTrue(after.liveRun)
		assertFalse(after.delegation.allowed)
		assertContains(after.delegation.reason!!, "살아 있는 실행 시도")
		assertEquals("work_item.delegated", after.history.first().action)
		assertEquals("ready", after.item.stage) // it moves only when the run starts

		val runId = runOf(s.workId)
		val view = reads.runs(s.workId, 10).single()
		assertEquals("requested", view.state)
		assertEquals(s.repoKey, view.delegation!!.repoKey)
		assertEquals("demo", view.delegation.repoName)
		assertEquals("handoff-pc", view.delegation.deviceName)
		assertEquals(listOf("Read", "Edit", "Write"), view.delegation.allowedTools)
		val instruction = view.instruction!!
		for (part in listOf("데모 작업", s.workId, "README에 한 줄을 더한다", "README를 연다", "Git 작업 트리", "push하지 않는다")) {
			assertContains(instruction, part)
		}
		// Only the named device sees it in its poll; the plain poll still lists it for the compat agent.
		assertEquals(listOf(runId), runs.pendingRuns(100, s.device).map { it.id })
		assertTrue(runs.pendingRuns(100, "dev_other").none { it.id == runId })
		val pending = runs.pendingRuns(100).single { it.id == runId }
		assertEquals(s.device, pending.deviceId)
		assertEquals(s.repoKey, pending.repoKey)
		// The remote keeps host and path only.
		assertEquals("https://github.com/me/demo.git", delegations.repositories(s.device).single().remote)
	}

	@Test
	fun `only the named device claims a delegated run and it gets the delegation and the instruction`() {
		val s = setup()
		delegate(s, branch = null)
		val runId = runOf(s.workId)
		assertFailsWith<ConflictException> { runs.claim(runId, "dev_somebody_else") }

		val claimed = runs.claim(runId, s.device)
		val d = assertNotNull(claimed.delegation)
		assertEquals(s.repoKey, d.repoKey)
		assertNull(d.baseBranch)
		assertEquals("claude_code", d.executor)
		assertEquals(listOf("Read", "Edit", "Write"), d.allowedTools)
		assertContains(claimed.instruction!!, "데모 작업")
		assertNotNull(reads.runs(s.workId, 10).single().claimedAt)
	}

	@Test
	fun `a started run takes the work item into in_progress and a submitted one through to done`() = withSkipped(listOf("plan_review", "result_review")) {
		val s = setup()
		delegate(s)
		val runId = runOf(s.workId)
		val gen = runs.claim(runId, s.device).generation

		assertEquals(EventResult(1, 0, 0, 0), ingest(runId, started(gen, s, runId)))
		val running = workItems.detail(s.workId)
		assertEquals("in_progress", running.item.stage)
		val move = running.history.first()
		assertEquals("system", move.actorKind)
		assertEquals(runId, move.actorId)

		val changes = event(gen, 2, "git_changes", mapOf("files" to listOf("README.md", "docs/a.md"), "base_overlap" to listOf("README.md")))
		val answer = event(gen, 3, "answer", mapOf("text" to "더했다"))
		val last = event(gen, 4, "git_changes", mapOf("files" to listOf("README.md"), "base_overlap" to emptyList<String>(),
			"base_branch_overlap" to listOf("README.md")))
		assertEquals(EventResult(4, 0, 0, 0), ingest(runId, changes, answer, last, ended(gen, 5, "submitted", true, true)))

		val done = workItems.detail(s.workId)
		assertEquals("done", done.item.stage)
		assertEquals(listOf("result_review", "done"), done.history.first().details["path"])
		assertTrue(done.item.waitReasons.isEmpty())
		val view = reads.runs(s.workId, 10).single()
		assertEquals("submitted", view.endReason)
		assertEquals("zm-baton/$runId", view.workspace!!["branch"])
		assertEquals(commit, view.workspace["base_commit"])
		assertEquals(listOf("README.md"), view.gitChanges!!["base_branch_overlap"])
		assertNotNull(view.lastActivityAt)
	}

	@Test
	fun `with result review on a submitted run stops in result_review`() = withSkipped(listOf("plan_review")) {
		val s = setup()
		delegate(s)
		val runId = runOf(s.workId)
		val gen = runs.claim(runId, s.device).generation
		ingest(runId, started(gen, s, runId), ended(gen, 2, "submitted", true, true))
		assertEquals("result_review", workItems.detail(s.workId).item.stage)
	}

	@Test
	fun `a run cut after its executor started leaves outcome_unknown, one that never started leaves the work ready`() {
		val cut = setup()
		delegate(cut)
		val cutRun = runOf(cut.workId)
		val gen = runs.claim(cutRun, cut.device).generation
		ingest(cutRun, started(gen, cut, cutRun), ended(gen, 2, "failed", true, false))
		val after = workItems.detail(cut.workId)
		assertEquals("in_progress", after.item.stage)
		assertEquals(listOf("outcome_unknown"), after.item.waitReasons)

		val early = setup()
		delegate(early)
		val earlyRun = runOf(early.workId)
		val gen2 = runs.claim(earlyRun, early.device).generation
		// The worktree could not be made: the executor never started.
		assertEquals(EventResult(1, 0, 0, 0), ingest(earlyRun, ended(gen2, 1, "failed", false, false)))
		val stays = workItems.detail(early.workId)
		assertEquals("ready", stays.item.stage)
		assertTrue(stays.item.waitReasons.isEmpty())
		assertTrue(stays.delegation.allowed) // it can be handed over again at once

		// An executor error result: the result arrived, so the outcome is known.
		val failedResult = setup()
		delegate(failedResult)
		val frRun = runOf(failedResult.workId)
		val gen3 = runs.claim(frRun, failedResult.device).generation
		ingest(frRun, started(gen3, failedResult, frRun), ended(gen3, 2, "failed", true, true))
		assertTrue(waits(failedResult.workId).isEmpty())
	}

	@Test
	fun `a git copy, a closed or not yet ready work item and a live run are refused with the server's reason`() {
		val copy = setup()
		jdbc.sql("update work_items set source_of_truth = 'git', git_ref = '{\"path\":\"registry/tasks.json\"}' where id = :id")
			.param("id", copy.workId).update()
		val refusal = workItems.detail(copy.workId).delegation
		assertFalse(refusal.allowed)
		assertContains(refusal.reason!!, "registry/tasks.json")
		assertContains(refusal.reason, "맡길 수 없다")
		val e = assertFailsWith<ConflictException> { delegate(copy) }
		assertEquals(refusal.reason, e.message)

		val draft = setup(stage = "draft")
		assertContains(assertFailsWith<ConflictException> { delegate(draft) }.message!!, "수행 준비로 옮긴 뒤")

		val twice = setup()
		delegate(twice)
		assertFailsWith<ConflictException> { delegate(twice) }
	}

	@Test
	fun `an unknown repository, tool, executor or branch and a stale version are refused`() {
		val s = setup()
		val v = version(s.workId)
		assertFailsWith<BadRequestException> {
			delegations.delegate(s.workId, DelegateIn("claude_code", s.device, repoKey(), null, null, v), actor = SessionService.OWNER)
		}
		assertFailsWith<BadRequestException> {
			delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, null, listOf("Read", "Bash"), v), actor = SessionService.OWNER)
		}
		assertFailsWith<BadRequestException> {
			delegations.delegate(s.workId, DelegateIn("codex", s.device, s.repoKey, null, null, v), actor = SessionService.OWNER)
		}
		for (bad in listOf("-x", "a..b", "a b", "/main", "main.lock", "x//y")) {
			assertFailsWith<BadRequestException>(bad) {
				delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, bad, null, v), actor = SessionService.OWNER)
			}
		}
		assertFailsWith<ConflictException> {
			delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, null, null, v - 1), actor = SessionService.OWNER)
		}
		// A removed repository cannot be named any more.
		assertTrue(delegations.removeRepository(s.device, s.repoKey, actor = SessionService.OWNER))
		assertFalse(delegations.removeRepository(s.device, s.repoKey, actor = SessionService.OWNER))
		assertFailsWith<BadRequestException> {
			delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, null, null, v), actor = SessionService.OWNER)
		}
		// Nothing above left a run behind, and absent tools mean Read only.
		delegations.addRepository(s.device, RepositoryUpsert(s.repoKey, "demo"), actor = SessionService.OWNER)
		delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, null, null, v), actor = SessionService.OWNER)
		assertEquals(listOf("Read"), reads.runs(s.workId, 10).single().delegation!!.allowedTools)
	}

	@Test
	fun `a requested run can be taken back, a claimed one cannot, and an expired one is not claimed`() {
		val s = setup()
		delegate(s)
		val runId = runOf(s.workId)
		val back = delegations.withdraw(runId, version(s.workId), actor = SessionService.OWNER)
		assertNull(back.item.executorKind)
		assertFalse(back.liveRun)
		assertTrue(back.delegation.allowed)
		assertEquals("work_item.delegation_withdrawn", back.history.first().action)
		val view = reads.runs(s.workId, 10).single()
		assertEquals("cancelled", view.endReason)
		assertNotNull(view.delegation!!.revokedAt)
		assertTrue(waits(s.workId).isEmpty())
		assertFailsWith<ConflictException> { runs.claim(runId, s.device) }

		delegate(s)
		val second = runOf(s.workId)
		runs.claim(second, s.device)
		assertFailsWith<ConflictException> { delegations.withdraw(second, version(s.workId), actor = SessionService.OWNER) }

		val old = setup()
		delegate(old)
		val oldRun = runOf(old.workId)
		jdbc.sql("update delegations set expires_at = now() - interval '1 minute' where work_item_id = :id")
			.param("id", old.workId).update()
		assertTrue(runs.pendingRuns(100, old.device).isEmpty())
		assertFailsWith<ConflictException> { runs.claim(oldRun, old.device) }
	}

	@Test
	fun `reports with a path on the device, a foreign repository or a missing end flag are refused`() {
		val s = setup()
		delegate(s)
		val runId = runOf(s.workId)
		val gen = runs.claim(runId, s.device).generation
		val badChanges = listOf(
			mapOf("files" to listOf("C:/Users/me/repo/a.txt")),
			mapOf("files" to listOf("/home/me/a.txt")),
			mapOf("files" to listOf("../a.txt")),
			mapOf("files" to listOf("a.txt"), "base_overlap" to listOf("b.txt")),
			mapOf("files" to "a.txt"),
		)
		for ((i, p) in badChanges.withIndex()) {
			assertEquals(EventResult(0, 0, 0, 1), ingest(runId, event(gen, 10L + i, "git_changes", p)), "payload $p")
		}
		val foreign = workspace(s, runId) + ("repo_key" to repoKey())
		val withPath = workspace(s, runId) + ("branch" to "C:/x")
		for ((i, ws) in listOf(foreign, withPath).withIndex()) {
			val e = event(gen, 20L + i, "lifecycle", mapOf("phase" to "started", "workspace" to ws))
			assertEquals(EventResult(0, 0, 0, 1), ingest(runId, e))
		}
		val noFlags = event(gen, 30, "lifecycle", mapOf("phase" to "ended", "end_reason" to "failed"))
		assertEquals(EventResult(0, 0, 0, 1), ingest(runId, noFlags))
		val fakeSubmit = ended(gen, 31, "submitted", true, false)
		assertEquals(EventResult(0, 0, 0, 1), ingest(runId, fakeSubmit))
		// Version 1 knows no git_changes.
		assertEquals(EventResult(0, 0, 0, 1),
			runs.ingest(runId, EventBatch(1, listOf(event(gen, 32, "git_changes", mapOf("files" to listOf("a.txt")))))))
		assertEquals("claimed", reads.runs(s.workId, 10).single().state)
	}

	@Test
	fun `the instruction says what is written and that the run works in its own worktree`() {
		val text = Instructions.build(Instructions.Item("wrk_1", "제목", "TSK-001", null, null, "docs/a.md", "zm-baton"))
		assertContains(text, "작업 번호: wrk_1 (TSK-001)")
		assertContains(text, "프로젝트: zm-baton")
		assertContains(text, "적혀 있지 않다")
		assertContains(text, "참조 문서: docs/a.md")
		assertFalse(text.contains("다음 행동:"))
	}

	@Test
	fun `branch names that would read as options or paths are not usable`() {
		for (ok in listOf("main", "feature/x-1", "release/2026.10")) assertTrue(DelegationService.isBranchName(ok), ok)
		for (bad in listOf("", "-main", "a..b", "/a", "a/", "a.", "a.lock", "a//b", "a b", "a\\b", "C:/x", ".hidden", "a/.b")) {
			assertFalse(DelegationService.isBranchName(bad), bad)
		}
	}
}
