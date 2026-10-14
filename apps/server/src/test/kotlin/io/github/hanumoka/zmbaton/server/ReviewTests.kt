package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Picking what people must see (FN-0411; contract v1 "사람에게 보낼 것을 고른다", "첫 화면"):
 * a submitted result is pinned and checked against the risk paths, sampled or left 「사람 미확인」;
 * only a person approves or rejects, and a rejection moves the work item as the contract says.
 * Against a real PostgreSQL 17, through the services and, for the session token, over HTTP.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = ["zm-baton.review-sweep-ms=3600000"])
class ReviewTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val delegations: DelegationService,
	private val runs: RunService,
	private val reads: ReadService,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
	private val reviews: ReviewService,
	private val settings: ReviewSettingsService,
	private val people: PersonService,
	private val jdbc: JdbcClient,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()
	private val commit = "c".repeat(40)

	private data class Setup(val device: String, val repoKey: String, val workId: String, val projectId: String?)

	private fun setup(project: Boolean = false, title: String = "검토 작업"): Setup {
		val device = sessions.registerDevice(RegisterDevice("review-pc", "windows")).deviceId
		val key = "git:" + UUID.randomUUID().toString().replace("-", "") + "00000000"
		delegations.addRepository(device, RepositoryUpsert(key, "demo"))
		val projectId = if (project) {
			val id = Ids.project()
			jdbc.sql("insert into projects (id, org_id, name) values (:id, 'org_owner', :name)").param("id", id).param("name", "프로젝트 $id").update()
			id
		} else {
			null
		}
		val work = workItems.create(CreateWorkItem(title, "usr_owner", projectId, acceptance = "고친다", initialStage = "ready", executorKind = "human"))
		return Setup(device, key, work.id, projectId)
	}

	private fun version(workId: String) = workItems.detail(workId).item.version

	private fun newestRun(workId: String): String =
		jdbc.sql("select id from runs where work_item_id = :id order by created_at desc, id desc limit 1").param("id", workId)
			.query(String::class.java).single()

	private fun event(gen: Long, seq: Long, kind: String, payload: Map<String, Any?>) = EventIn("evt_" + UUID.randomUUID(), gen, seq, kind, payload)

	private fun sha() = UUID.randomUUID().toString().replace("-", "").padEnd(40, 'a').take(40)

	/**
	 * Hands the work item over (or on: [note] sends a result in result review back) and reports a
	 * submitted result: the final Git change list with [files] and [snapshot], and tool calls on [toolPaths].
	 */
	private fun result(
		s: Setup, files: List<String>, snapshot: String? = sha(), toolPaths: List<String> = emptyList(), note: String? = null,
		billingStopped: Boolean = false,
	): String {
		delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, "main", listOf("Read", "Edit"), version(s.workId), note))
		val runId = newestRun(s.workId)
		val gen = runs.claim(runId, s.device).generation
		// An extra billing signal during the run stops the work item (UsageService); a handoff clears it, so it comes after.
		if (billingStopped) jdbc.sql("update work_items set wait_reasons = array['billing_stopped'] where id = :id").param("id", s.workId).update()
		val changes = linkedMapOf<String, Any?>("files" to files, "base_overlap" to emptyList<String>(), "base_branch_overlap" to emptyList<String>())
		if (snapshot != null) changes["snapshot"] = snapshot
		val events = mutableListOf(
			event(gen, 1, "lifecycle", mapOf("phase" to "started", "session_id" to UUID.randomUUID().toString(),
				"workspace" to mapOf("repo_key" to s.repoKey, "base_commit" to commit, "branch" to "zm-baton/$runId", "base_ref" to "main"))),
		)
		toolPaths.forEachIndexed { i, p -> events += event(gen, 2L + i, "action", mapOf("name" to "Edit", "input" to mapOf("file_path" to p))) }
		events += event(gen, 10, "git_changes", changes)
		events += event(gen, 11, "lifecycle", mapOf("phase" to "ended", "end_reason" to "submitted", "executor_started" to true, "result_seen" to true))
		val stored = runs.ingest(runId, EventBatch(2, events))
		assertEquals(0, stored.rejected, "rejected events")
		return runId
	}

	private data class Review(val id: String, val reason: String, val state: String, val targetKind: String, val targetRef: String)

	private fun reviewsOf(runId: String): List<Review> =
		jdbc.sql("select id, reason, state, target_kind, target_ref from review_items where run_id = :id order by created_at, id").param("id", runId)
			.query { rs, _ -> Review(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }.list()

	private fun inboxOf(reviewId: String): InboxItemView? = people.inbox().singleOrNull { it.sourceId == reviewId }

	private fun run(workId: String, runId: String) = reads.runs(workId, 20).single { it.id == runId }

	private fun approve(r: Review) = reviews.decide(r.id, ReviewDecisionIn("approved", r.targetRef), "person", "usr_owner")

	private fun reject(r: Review, note: String = "고칠 것이 있다", workVersion: Long? = null, propose: String? = null) =
		reviews.decide(r.id, ReviewDecisionIn("rejected", r.targetRef, note, workVersion, propose), "person", "usr_owner")

	private fun <T> withSkipped(stages: List<String>, block: () -> T): T {
		val before = workItems.workflow().skippedStages
		workItems.setWorkflow(WorkflowSettings(stages))
		try {
			return block()
		} finally {
			workItems.setWorkflow(WorkflowSettings(before))
		}
	}

	/** Runs [block] with the given sample rate and daily cap, then puts the old ones back. */
	private fun <T> withSampling(rate: Double, cap: Int = 1000, block: () -> T): T {
		val before = settings.view()
		settings.setOrg(OrgReviewSettingsIn(sampleRate = rate, sampleDailyCap = cap), "usr_owner")
		try {
			return block()
		} finally {
			settings.setOrg(OrgReviewSettingsIn(sampleRate = before.sampleRate, sampleDailyCap = before.sampleDailyCap), "usr_owner")
		}
	}

	private fun audits(action: String, targetId: String): List<String> =
		jdbc.sql("select actor_kind from audit_log where action = :a and target_id = :t order by at").param("a", action).param("t", targetId)
			.query(String::class.java).list().filterNotNull()

	// ---------------------------------------------------------------- picking

	@Test
	fun `a result that touches a risk path is pinned, sent to bucket 4 at once and held in result review`() = withSkipped(listOf("plan_review", "result_review")) {
		val s = setup()
		val snap = sha()
		val runId = result(s, listOf("README.md", ".github/workflows/ci.yml", "apps/server/src/main/resources/db/migration/V10__x.sql"), snap)
		val r = reviewsOf(runId).single()
		assertEquals("risk_path", r.reason)
		assertEquals("open", r.state)
		assertEquals("run_snapshot", r.targetKind)
		assertEquals(snap, r.targetRef)
		val item = assertNotNull(inboxOf(r.id))
		assertEquals(4, item.bucket)
		assertEquals("risk_review", item.kind)
		assertEquals("immediate", item.notify)
		assertContains(item.summary, ".github/workflows/ci.yml")
		assertContains(item.summary, "외 1개")

		val view = run(s.workId, runId)
		assertEquals("required", view.humanReviewState)
		assertEquals("not_run", view.autoCheckState)
		assertEquals(snap, view.resultSnapshotSha)
		assertEquals(r.id, view.reviews.single().id)
		assertEquals(item.id, view.reviews.single().inboxItemId)
		@Suppress("UNCHECKED_CAST")
		val matches = (view.reviewBasis!!["matches"] as List<Map<String, Any?>>).map { it["pattern"] }
		assertEquals(listOf(".github/**", "**/db/migration/**"), matches)
		assertEquals("org", view.reviewBasis["list"])

		// The organisation skips result review, but an open risk request holds the result there.
		val detail = workItems.detail(s.workId)
		assertEquals("result_review", detail.item.stage)
		val done = detail.moves.single { it.target == "done" }
		assertFalse(done.allowed)
		assertContains(done.reason!!, "위험 목록 검토")
		assertFailsWith<ConflictException> { workItems.transition(s.workId, TransitionIn("done", detail.item.version)) }
		assertEquals(listOf("system"), audits("review.raised", r.id))
	}

	@Test
	fun `the tools' file paths count too, and a result without a risk and no sample is left not reviewed`() = withSampling(0.0) {
		withSkipped(listOf("plan_review", "result_review")) {
			val s = setup()
			val risky = result(s, listOf("README.md"), toolPaths = listOf("docs\\governance\\decisions\\DEC-0200-x.md"))
			assertEquals("risk_path", reviewsOf(risky).single().reason)

			val plain = setup()
			val runId = result(plain, listOf("README.md", "src/app.ts"))
			assertTrue(reviewsOf(runId).isEmpty())
			val view = run(plain.workId, runId)
			assertEquals("not_reviewed", view.humanReviewState)
			assertEquals("not_run", view.autoCheckState)
			@Suppress("UNCHECKED_CAST")
			val sample = view.reviewBasis!!["sample"] as Map<String, Any?>
			assertEquals(Sampler.RULE, sample["rule"])
			assertEquals(Sampler.draw(runId), (sample["draw"] as Number).toDouble())
			assertEquals(false, sample["picked"])
			assertEquals("done", workItems.detail(plain.workId).item.stage)
		}
	}

	@Test
	fun `a sample goes to bucket 5 for the digest, reproducibly, until the day's cap`() = withSkipped(listOf("plan_review", "result_review")) {
		val midnight = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime()
		val today = jdbc.sql("select count(*) from review_items where reason = 'sample' and recipient_user_id = 'usr_owner' and created_at >= :m")
			.param("m", midnight).query(Long::class.javaObjectType).single().toInt()
		withSampling(1.0, cap = today + 1) {
			val s = setup()
			val first = result(s, listOf("src/a.ts"))
			val r = reviewsOf(first).single()
			assertEquals("sample", r.reason)
			val item = assertNotNull(inboxOf(r.id))
			assertEquals(5, item.bucket)
			assertEquals("sample_review", item.kind)
			assertEquals("digest", item.notify)
			assertEquals("sampled", run(s.workId, first).humanReviewState)
			val evidence = reviews.detail(r.id).evidence
			assertEquals(Sampler.draw(first), (evidence["draw"] as Number).toDouble())
			assertEquals("sha256(${Sampler.seed(first)})", evidence["seed"])
			// A sample does not hold the result: the work item went on to done.
			assertEquals("done", workItems.detail(s.workId).item.stage)

			val second = setup()
			val capped = result(second, listOf("src/b.ts"))
			assertTrue(reviewsOf(capped).isEmpty())
			@Suppress("UNCHECKED_CAST")
			val basis = run(second.workId, capped).reviewBasis!!["sample"] as Map<String, Any?>
			assertEquals(true, basis["capped"])
			assertEquals("not_reviewed", run(second.workId, capped).humanReviewState)
		}
	}

	@Test
	fun `a result without a snapshot is not pinned, is never a sample, and its approval says so`() = withSampling(1.0) {
		val s = setup()
		val runId = result(s, listOf("AGENTS.md", "x.txt"), snapshot = null)
		val r = reviewsOf(runId).single()
		assertEquals("unpinned", r.targetKind)
		assertEquals(64, r.targetRef.length)
		approve(r)
		val ref = jdbc.sql("select target_ref from approvals where target_kind = 'review_item' and target_id = :id").param("id", r.id)
			.query(String::class.java).single()
		assertEquals("unpinned:${r.targetRef}", ref)

		val plain = setup()
		val unpinned = result(plain, listOf("x.txt"), snapshot = null)
		assertTrue(reviewsOf(unpinned).isEmpty())
		@Suppress("UNCHECKED_CAST")
		assertEquals("unpinned", (run(plain.workId, unpinned).reviewBasis!!["sample"] as Map<String, Any?>)["skipped"])
	}

	@Test
	fun `a work item stopped for extra billing raises its own request next to the risk one`() = withSkipped(listOf("plan_review")) {
		val s = setup()
		val runId = result(s, listOf("AGENTS.md"), billingStopped = true)
		assertEquals(setOf("risk_path", "extra_billing"), reviewsOf(runId).map { it.reason }.toSet())
		// Rejecting both: only the first moves the work item back; the second is recorded (contract boundary 6).
		val risk = reviewsOf(runId).single { it.reason == "risk_path" }
		val billing = reviewsOf(runId).single { it.reason == "extra_billing" }
		val first = reject(risk, "정책 파일을 건드리면 안 된다", version(s.workId))
		assertEquals("returned", first.effect)
		assertEquals("in_progress", workItems.detail(s.workId).item.stage)
		val second = reject(billing, "과금도 문제다")
		assertEquals("recorded", second.effect)
		assertEquals("in_progress", workItems.detail(s.workId).item.stage)
		assertEquals("reviewed", run(s.workId, runId).humanReviewState)
	}

	// ---------------------------------------------------------------- deciding

	@Test
	fun `approving the held result records the approval and lets it pass to done where result review is skipped`() = withSkipped(listOf("plan_review", "result_review")) {
		val s = setup()
		val runId = result(s, listOf("CLAUDE.md"))
		val r = reviewsOf(runId).single()
		val detail = reviews.detail(r.id)
		assertContains(detail.effects!!.approve, "완료로 넘어간다")
		val out = approve(r)
		assertEquals("released", out.effect)
		assertEquals("approved", out.review.state)
		assertNull(inboxOf(r.id))
		assertEquals("reviewed", run(s.workId, runId).humanReviewState)
		val item = workItems.detail(s.workId)
		assertEquals("done", item.item.stage)
		assertTrue(item.history.any { it.action == "review.approved" })
		val approval = jdbc.sql("select approver_kind, approver_id, decision, target_ref from approvals where target_id = :id").param("id", r.id)
			.query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.single()
		assertEquals(listOf("human", "usr_owner", "approved", "run_snapshot:${r.targetRef}"), approval)
		// Decided once: a second verdict is refused.
		val e = assertFailsWith<ConflictException> { reject(r) }
		assertContains(e.message!!, "이미 승인")
	}

	@Test
	fun `with result review in use an approval leaves the work item there for the person`() = withSkipped(listOf("plan_review")) {
		val s = setup()
		val r = reviewsOf(result(s, listOf("SECURITY.md"))).single()
		assertEquals("approved", approve(r).effect)
		val detail = workItems.detail(s.workId)
		assertEquals("result_review", detail.item.stage)
		assertTrue(detail.moves.single { it.target == "done" }.allowed)
	}

	@Test
	fun `rejecting the newest result sends the work item back and the next run gets the reason, then a new result is approved`() =
		withSkipped(listOf("plan_review", "result_review")) {
			val s = setup()
			val first = result(s, listOf(".claude/settings.json"))
			val r = reviewsOf(first).single()
			val detail = reviews.detail(r.id)
			assertTrue(detail.effects!!.rejectNeedsVersion)
			assertFailsWith<BadRequestException> { reject(r, workVersion = null) }
			assertFailsWith<BadRequestException> { reviews.decide(r.id, ReviewDecisionIn("rejected", r.targetRef, " "), "person", "usr_owner") }
			assertFailsWith<ConflictException> { reject(r, workVersion = detail.workItem!!.version - 1) }

			val out = reject(r, "권한 설정은 고치지 말 것", detail.workItem!!.version)
			assertEquals("returned", out.effect)
			assertEquals("rejected", out.review.state)
			val after = workItems.detail(s.workId)
			assertEquals("in_progress", after.item.stage)
			assertEquals("resume", after.delegation.mode)
			assertEquals(first, after.delegation.resumeFrom!!.runId)

			// 「이어서 맡기기」: the new run continues the rejected one, with the person's reason in its instruction.
			val second = result(s, listOf(".claude/settings.json", "README.md"))
			val secondRun = run(s.workId, second)
			assertEquals("result_rejected", secondRun.continueReason)
			assertEquals(first, secondRun.resumedFromRunId)
			assertContains(secondRun.instruction!!, "권한 설정은 고치지 말 것")
			assertEquals("result_review", workItems.detail(s.workId).item.stage)
			val again = reviewsOf(second).single()
			assertEquals("released", approve(again).effect)
			assertEquals("done", workItems.detail(s.workId).item.stage)
			// The first run's rejection stays on its own record.
			assertEquals("rejected", reviewsOf(first).single().state)
			assertEquals("reviewed", run(s.workId, first).humanReviewState)
		}

	@Test
	fun `a verdict needs the target the person saw`() {
		val s = setup()
		val r = reviewsOf(result(s, listOf("AGENTS.md"))).single()
		val e = assertFailsWith<ConflictException> { reviews.decide(r.id, ReviewDecisionIn("approved", sha()), "person", "usr_owner") }
		assertContains(e.message!!, "대상")
		assertEquals("open", reviewsOf(newestRun(s.workId)).single().state)
	}

	@Test
	fun `a newer result replaces the open requests of the earlier one`() = withSkipped(listOf("plan_review", "result_review")) {
		val s = setup()
		val first = result(s, listOf("AGENTS.md"))
		val old = reviewsOf(first).single()
		// The person sends the result back from the work detail instead of the first screen.
		val second = result(s, listOf("AGENTS.md", "b.txt"), note = "다시 해 줘")
		val new = reviewsOf(second).single()
		val replaced = reviewsOf(first).single()
		assertEquals("superseded", replaced.state)
		val superseded = jdbc.sql("select superseded_by from review_items where id = :id").param("id", old.id).query(String::class.java).single()
		assertEquals(new.id, superseded)
		assertNull(inboxOf(old.id))
		assertNotNull(inboxOf(new.id))
		assertEquals("not_reviewed", run(s.workId, first).humanReviewState)
		assertEquals("required", run(s.workId, second).humanReviewState)
		val e = assertFailsWith<ConflictException> { approve(old) }
		assertContains(e.message!!, "대체")
	}

	@Test
	fun `a request whose work item moved on is only recorded when rejected (contract boundary 7)`() = withSkipped(emptyList()) {
		val s = setup()
		val r = reviewsOf(result(s, listOf("AGENTS.md"))).single()
		workItems.transition(s.workId, TransitionIn("plan_review", version(s.workId), "범위가 바뀌었다"))
		val detail = reviews.detail(r.id)
		assertFalse(detail.effects!!.rejectNeedsVersion)
		assertEquals("recorded", reject(r, "어차피 다시 계획한다").effect)
		val item = workItems.detail(s.workId)
		assertEquals("plan_review", item.item.stage)
		assertTrue(item.history.any { it.action == "review.rejected" && it.details["effect"] == "recorded" })
	}

	@Test
	fun `rejecting a done work item's sample makes a fixing work item and can put forward a risk path`() = withSampling(1.0) {
		withSkipped(listOf("plan_review", "result_review")) {
			val s = setup(project = true, title = "결제 화면")
			val runId = result(s, listOf("src/pay/form.ts", "src/pay/api.ts"))
			val r = reviewsOf(runId).single()
			assertEquals("sample", r.reason)
			assertEquals("done", workItems.detail(s.workId).item.stage)
			val detail = reviews.detail(r.id)
			assertEquals("src/pay/**", detail.suggestedPattern)
			assertContains(detail.effects!!.reject, "고침 작업")
			assertFailsWith<BadRequestException> { reviews.decide(r.id, ReviewDecisionIn("approved", r.targetRef, proposeRiskPath = "src/pay/**"), "person", "usr_owner") }
			assertFailsWith<BadRequestException> { reject(r, propose = "/abs/path") }

			val out = reject(r, "검증이 빠졌다", propose = "src/pay/**")
			assertEquals("fix_created", out.effect)
			val fix = workItems.detail(out.fixWorkItemId!!)
			assertEquals("draft", fix.item.stage)
			assertEquals("고침: 결제 화면", fix.item.title)
			assertEquals("검증이 빠졌다", fix.acceptance)
			assertEquals(s.projectId, fix.item.project?.id)
			assertEquals("fixes", jdbc.sql("select kind from work_item_links where from_id = :f and to_id = :t")
				.param("f", fix.item.id).param("t", s.workId).query(String::class.java).single())
			assertEquals("done", workItems.detail(s.workId).item.stage)

			// The candidate waits for a person; nothing added it.
			val before = settings.view()
			val proposal = before.proposals.single { it.reviewId == r.id }
			assertEquals("src/pay/**", proposal.pattern)
			assertEquals(s.projectId, proposal.projectId)
			val scope = before.projects.single { it.project.id == s.projectId }
			assertTrue(scope.inherited)
			assertFalse("src/pay/**" in scope.patterns)
			assertEquals(1, scope.openProposals)

			val after = settings.decideProposal(r.id, ProposalIn("add"), "usr_owner")
			val added = after.projects.single { it.project.id == s.projectId }
			assertFalse(added.inherited)
			assertEquals(RiskPaths.ORG_DEFAULT + "src/pay/**", added.patterns)
			assertTrue(after.proposals.none { it.reviewId == r.id })
			assertFailsWith<ConflictException> { settings.decideProposal(r.id, ProposalIn("dismiss"), "usr_owner") }
			// The project's list now catches the next result there, and only there.
			assertEquals("risk_path", reviewsOf(result(setupIn(s.projectId!!), listOf("src/pay/form.ts"))).single().reason)
			assertEquals(listOf("person"), audits("review_settings.project_risk_paths", s.projectId))
		}
	}

	private fun setupIn(projectId: String): Setup {
		val s = setup()
		jdbc.sql("update work_items set project_id = :p where id = :id").param("p", projectId).param("id", s.workId).update()
		return s.copy(projectId = projectId)
	}

	// ---------------------------------------------------------------- only a person decides

	@Test
	fun `AI cannot close a review request, and the refusal is audited (contract boundary 10)`() {
		val s = setup()
		val r = reviewsOf(result(s, listOf("AGENTS.md"))).single()
		for (actor in listOf("session", "manager", "system")) {
			val e = assertFailsWith<ForbiddenException> { reviews.decide(r.id, ReviewDecisionIn("approved", r.targetRef), actor, "ai_$actor") }
			assertContains(e.message!!, "사람만")
		}
		assertEquals(listOf("session", "system", "system"), audits("review.close_refused", r.id))

		// Over HTTP a session token is refused before anything is read, and that is audited too.
		val device = sessions.registerDevice(RegisterDevice("ai-pc")).deviceId
		val token = sessions.register(RegisterSession(2, device, "claude_code", executorSessionId = UUID.randomUUID().toString())).token
		val body = json.writeValueAsString(mapOf("decision" to "approved", "target_ref" to r.targetRef))
		val response = http.send(
			HttpRequest.newBuilder(URI.create("$base/api/reviews/${r.id}/decision")).header("Content-Type", "application/json")
				.header("Authorization", "Bearer $token").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(403, response.statusCode())
		assertTrue(jdbc.sql("select count(*) from audit_log where action = 'person_action.refused' and target_id = 'approve or reject a review request'")
			.query(Long::class.javaObjectType).single() >= 1)
		assertEquals("open", reviewsOf(newestRun(s.workId)).single().state)

		// A person over HTTP: the target and the work item version from the detail.
		val detail = http.send(HttpRequest.newBuilder(URI.create("$base/api/reviews/${r.id}")).GET().build(), HttpResponse.BodyHandlers.ofString())
		assertEquals(200, detail.statusCode())
		assertContains(detail.body(), "\"target_ref\":\"${r.targetRef}\"")
		val ok = http.send(
			HttpRequest.newBuilder(URI.create("$base/api/reviews/${r.id}/decision")).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(200, ok.statusCode(), ok.body())
		assertEquals("approved", reviewsOf(newestRun(s.workId)).single().state)
	}

	// ---------------------------------------------------------------- settings and the first screen

	@Test
	fun `a person edits the organisation's and a project's list, every change audited`() {
		val s = setup(project = true)
		val before = settings.view()
		try {
			assertFalse(before.org.customized)
			assertEquals(RiskPaths.ORG_DEFAULT, before.org.patterns)
			assertFailsWith<BadRequestException> { settings.setOrg(OrgReviewSettingsIn(riskPaths = listOf("C:/x")), "usr_owner") }
			assertFailsWith<BadRequestException> { settings.setOrg(OrgReviewSettingsIn(sampleRate = 1.5), "usr_owner") }
			val org = settings.setOrg(OrgReviewSettingsIn(riskPaths = listOf("only/**")), "usr_owner")
			assertTrue(org.org.customized)
			assertEquals(listOf("only/**"), org.projects.single { it.project.id == s.projectId }.patterns)

			val own = settings.setProject(s.projectId!!, ProjectRiskPathsIn(listOf("mine/**", " mine/** ", "")), "usr_owner")
			assertEquals(listOf("mine/**"), own.projects.single { it.project.id == s.projectId }.patterns)
			assertEquals(EffectiveRiskPaths(listOf("mine/**"), "project"), settings.effective(s.projectId, s.repoKey))
			assertEquals(EffectiveRiskPaths(listOf("only/**"), "org"), settings.effective(null, s.repoKey))
			val back = settings.setProject(s.projectId, ProjectRiskPathsIn(null), "usr_owner")
			assertTrue(back.projects.single { it.project.id == s.projectId }.inherited)
			assertEquals(listOf("person", "person"), audits("review_settings.project_risk_paths", s.projectId))
			assertTrue(audits("review_settings.changed", "org_owner").isNotEmpty())
			assertFailsWith<NotFoundException> { settings.setProject("prj_none", ProjectRiskPathsIn(null), "usr_owner") }
		} finally {
			settings.setOrg(OrgReviewSettingsIn(riskPaths = before.org.patterns), "usr_owner")
			jdbc.sql("update organizations set settings = settings - 'risk_paths' where id = 'org_owner'").update()
		}
	}

	@Test
	fun `a review open for a day is raised to the top of the first screen`() {
		val s = setup()
		val r = reviewsOf(result(s, listOf("AGENTS.md"))).single()
		val item = inboxOf(r.id)!!
		jdbc.sql("update inbox_items set waiting_since = now() - interval '25 hours' where id = :id").param("id", item.id).update()
		assertTrue(reviews.promoteWaiting() >= 1)
		val raised = people.inbox()
		val mine = raised.single { it.id == item.id }
		assertNotNull(mine.promotedAt)
		assertEquals(4, mine.bucket)
		val firstNotRaised = raised.indexOfFirst { it.promotedAt == null && it.bucket > 1 }
		assertTrue(firstNotRaised == -1 || raised.indexOf(mine) < firstNotRaised, "a raised review sorts with bucket 1")
	}

	@Test
	fun `review requests and work item links are in the export`() {
		val tables = ExportService.COLLECTIONS.map { it.second }
		assertTrue("review_items" in tables && "work_item_links" in tables)
	}
}
