package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
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
 * Questions and renegotiation (FN-0210; contract v1 "질문과 과금 신호", question path): routing,
 * `waiting_answer`, the first screen's bucket 2, answering with the records it led to, a run's
 * question carried by a continuing run, and no answer never counting as one. Against a real
 * PostgreSQL 17, through the services and, for the session token, over HTTP.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["zm-baton.question-sweep-ms=3600000", "zm-baton.review-sweep-ms=3600000"],
)
class QuestionTests @Autowired constructor(
	env: Environment,
	private val delegations: DelegationService,
	private val runs: RunService,
	private val reads: ReadService,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
	private val questions: QuestionService,
	private val documents: DocumentWriteService,
	private val jdbc: JdbcClient,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	private data class Setup(val device: String, val repoKey: String, val workId: String)

	private fun setup(stage: String = "ready"): Setup {
		val device = sessions.registerDevice(RegisterDevice("question-pc", "windows")).deviceId
		val key = "git:" + UUID.randomUUID().toString().replace("-", "") + "00000000"
		delegations.addRepository(device, RepositoryUpsert(key, "demo"))
		val work = workItems.create(CreateWorkItem("질문 작업", "usr_owner", acceptance = "기한을 정한다", initialStage = stage, executorKind = "human"))
		return Setup(device, key, work.id)
	}

	private fun version(workId: String) = workItems.detail(workId).item.version

	private fun waits(workId: String) = workItems.detail(workId).item.waitReasons

	private fun newestRun(workId: String): String =
		jdbc.sql("select id from runs where work_item_id = :id order by created_at desc, id desc limit 1").param("id", workId)
			.query(String::class.java).single()

	private fun event(gen: Long, seq: Long, kind: String, payload: Map<String, Any?>) = EventIn("evt_" + UUID.randomUUID(), gen, seq, kind, payload)

	/** Hands the work item over (or on) and reports a submitted result from executor session [session]. */
	private fun submitted(s: Setup, session: String, answer: String = "끝냈다"): String {
		delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, "main", listOf("Read", "Edit"), version(s.workId)))
		val runId = newestRun(s.workId)
		val gen = runs.claim(runId, s.device).generation
		runs.ingest(runId, EventBatch(2, listOf(
			event(gen, 1, "lifecycle", mapOf("phase" to "started", "session_id" to session)),
			event(gen, 2, "answer", mapOf("text" to answer)),
			event(gen, 3, "git_changes", mapOf("files" to listOf("notes.md"), "base_overlap" to emptyList<String>(),
				"base_branch_overlap" to emptyList<String>(), "snapshot" to "d".repeat(40))),
			event(gen, 4, "lifecycle", mapOf("phase" to "ended", "end_reason" to "submitted", "executor_started" to true, "result_seen" to true)),
		)))
		return runId
	}

	private fun ask(workId: String, body: String = "보존 기간이 정확히 30일인가?", kind: String = "interpretation", blocking: Boolean = true,
		askedBy: String = "person", runId: String? = null) =
		questions.ask(workId, AskIn(body, kind, blocking, askedBy = askedBy, runId = runId, version = version(workId)), "usr_owner")

	private fun only(workId: String) = questions.forWorkItem(workId).single()

	private data class Item(val user: String, val bucket: Int, val kind: String, val notify: String, val summary: String, val repeat: Int)

	private fun openItems(questionId: String): List<Item> =
		jdbc.sql("select user_id, bucket, kind, notify, summary, repeat_count from inbox_items where source_id = :id and state = 'open'")
			.param("id", questionId).query { rs, _ -> Item(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getInt(6)) }.list()

	private fun <T> withSkipped(stages: List<String>, block: () -> T): T {
		val before = workItems.workflow().skippedStages
		workItems.setWorkflow(WorkflowSettings(stages))
		try {
			return block()
		} finally {
			workItems.setWorkflow(WorkflowSettings(before))
		}
	}

	// ---------------------------------------------------------------- asking and routing

	@Test
	fun `a person's question is routed, holds the work item waiting and reaches bucket 2`() {
		val s = setup()
		workItems.transition(s.workId, TransitionIn("in_progress", version(s.workId)))
		val after = ask(s.workId)
		assertEquals(listOf("waiting_answer"), after.item.waitReasons)
		assertTrue(after.history.any { it.action == "question.asked" })
		val q = only(s.workId)
		assertEquals("awaiting_answer", q.state)
		assertEquals("person", q.askedByKind)
		assertEquals("usr_owner", q.askedById)
		assertEquals("work_owner", q.routedRole) // no linked document: the work item's owner
		assertEquals("usr_owner", q.routedToId)
		assertFalse(q.overdue)
		val item = openItems(q.id).single()
		assertEquals(Item("usr_owner", 2, "run_question", "immediate", "질문 작업: 보존 기간이 정확히 30일인가?", 0), item)
		assertNotNull(questions.view(q.id).inboxItemId)

		// A question that does not block goes to the digest.
		val loose = setup()
		ask(loose.workId, blocking = false, kind = "fact")
		assertEquals("digest", openItems(only(loose.workId).id).single().notify)
	}

	@Test
	fun `interpretation goes to the source document's owner and an approval request to the approver`() {
		val s = setup()
		val doc = documents.register(
			mapOf("title" to "보존 정책", "source_of_truth" to "product", "body_markdown" to "30일 보관한다", "work_item_ids" to listOf(s.workId)),
			"usr_owner",
		).document.id
		jdbc.sql("update documents set owner_user_id = 'usr_policy' where id = :id").param("id", doc).update()
		ask(s.workId, kind = "interpretation")
		val q = only(s.workId)
		assertEquals("document_owner", q.routedRole)
		assertEquals("usr_policy", q.routedToId)
		assertEquals("usr_policy", openItems(q.id).single().user) // it waits for that person, not the owner

		val approval = setup()
		ask(approval.workId, "이 정책 예외를 허락하나?", kind = "approval")
		assertEquals("approver", only(approval.workId).routedRole)
		assertEquals(ApprovalPolicy.approverFor("org_owner", "question"), only(approval.workId).routedToId)
		assertEquals("work_owner", QuestionRouting.route("execution", "usr_a", "usr_doc", "usr_ap").role)
		assertEquals("usr_ap", QuestionRouting.route("policy_exception", "usr_a", "usr_doc", "usr_ap").id)
	}

	@Test
	fun `questions are refused on closed work, Git copies and runs of another work item`() {
		val s = setup()
		workItems.transition(s.workId, TransitionIn("cancelled", version(s.workId)))
		assertContains(assertFailsWith<ConflictException> { ask(s.workId) }.message!!, "질문을 남기지 않는다")
		val closed = workItems.detail(s.workId).question
		assertFalse(closed.allowed)
		assertContains(closed.reason!!, "질문을 남기지 않는다")
		assertTrue(workItems.detail(setup().workId).question.allowed)
		val other = setup()
		val runId = submitted(other, UUID.randomUUID().toString())
		val mine = setup()
		assertFailsWith<BadRequestException> { ask(mine.workId, askedBy = "run", runId = runId) }
		assertFailsWith<BadRequestException> { ask(mine.workId, askedBy = "run") }
		assertFailsWith<BadRequestException> { ask(mine.workId, kind = "guess") }
		assertFailsWith<BadRequestException> { ask(mine.workId, body = " ") }
		assertFailsWith<ConflictException> { questions.ask(mine.workId, AskIn("질문", "fact", version = version(mine.workId) - 1), "usr_owner") }
	}

	// ---------------------------------------------------------------- answering

	@Test
	fun `an answer settles a person's question, keeps who answered and the records, and lifts the wait with the last one`() {
		val s = setup()
		ask(s.workId, "첫 질문")
		ask(s.workId, "둘째 질문", kind = "execution")
		val (first, second) = questions.forWorkItem(s.workId)
		val decision = jdbc.sql("insert into decisions (id, org_id, question, status, number, source_of_truth) values (:id, 'org_owner', '기한', 'proposed', nextval('decision_numbers'), 'product') returning id")
			.param("id", Ids.decision()).query(String::class.java).single()
		assertFailsWith<BadRequestException> { questions.answer(first.id, AnswerIn("30일", decisionRef = "dcs_none"), "usr_owner") }
		assertFailsWith<BadRequestException> { questions.answer(first.id, AnswerIn("30일", docChangeRef = "x_1"), "usr_owner") }
		val a = questions.answer(first.id, AnswerIn("30일이다", decisionRef = decision, evidenceRefs = listOf("DEC-0102")), "usr_owner")
		assertEquals("resolved", a.state)
		assertEquals("answered", a.closeReason)
		assertEquals("person", a.answeredByKind)
		assertEquals("usr_owner", a.answeredById)
		assertEquals(decision, a.decisionRef)
		assertEquals(listOf("DEC-0102"), a.evidenceRefs)
		assertTrue(openItems(first.id).isEmpty())
		assertEquals(listOf("waiting_answer"), waits(s.workId)) // the second still waits
		assertFailsWith<ConflictException> { questions.answer(first.id, AnswerIn("다시"), "usr_owner") }

		questions.withdraw(second.id, WithdrawQuestionIn("필요 없어졌다"), "usr_owner")
		assertEquals("withdrawn", questions.view(second.id).closeReason)
		assertTrue(waits(s.workId).isEmpty())
		assertTrue(workItems.detail(s.workId).history.any { it.action == "question.answered" })
	}

	@Test
	fun `an answer that changes a document waits for the change before it is settled`() {
		val s = setup()
		ask(s.workId, "수락 조건을 바꿔야 하나?")
		val q = only(s.workId)
		val waiting = questions.answer(q.id, AnswerIn("바꾼다. 문서에 반영한다", needsDocChange = true), "usr_owner")
		assertEquals("awaiting_doc_change", waiting.state)
		assertTrue(waits(s.workId).isEmpty()) // the answer came; the document change does not block the work (작성자 시작값)
		assertTrue(questions.detail(q.id).canConfirmDocChange)
		val doc = documents.register(mapOf("title" to "요구", "source_of_truth" to "product", "body_markdown" to "새 조건"), "usr_owner")
		val version = doc.versions.first().id
		val done = questions.confirmDocChange(q.id, DocChangeIn(version), "usr_owner")
		assertEquals("resolved", done.state)
		assertEquals(version, done.docChangeRef)
		assertFailsWith<ConflictException> { questions.confirmDocChange(q.id, DocChangeIn(version), "usr_owner") }
	}

	// ---------------------------------------------------------------- a run's question

	@Test
	fun `a run's question sends its result back, and the continuing run carries the answer to the executor`() = withSkipped(listOf("plan_review")) {
		val s = setup()
		val session = UUID.randomUUID().toString()
		val first = submitted(s, session, "보존 기간이 30일인지 90일인지 모르겠다. 30일로 가정했다")
		assertEquals("result_review", workItems.detail(s.workId).item.stage)
		val after = ask(s.workId, "보존 기간은 30일인가 90일인가?", askedBy = "run", runId = first)
		assertEquals("in_progress", after.item.stage)
		assertEquals(listOf("waiting_answer"), after.item.waitReasons)
		val q = only(s.workId)
		assertEquals("manager", q.askedByKind)
		assertEquals(DelegationService.MANAGER, q.askedById)
		assertEquals(first, q.runId)
		assertEquals("run_answer", jdbc.sql("select origin from questions where id = :id").param("id", q.id).query(String::class.java).single())

		val answered = questions.answer(q.id, AnswerIn("90일이다"), "usr_owner")
		assertEquals("answered", answered.state) // settled only once the executor has it
		val detail = workItems.detail(s.workId)
		assertTrue(detail.item.waitReasons.isEmpty())
		assertEquals("resume", detail.delegation.mode)
		assertEquals(1, detail.delegation.answersReady)

		delegations.delegate(s.workId, DelegateIn("claude_code", s.device, s.repoKey, "main", listOf("Read", "Edit"), version(s.workId)))
		val second = newestRun(s.workId)
		val view = reads.runs(s.workId, 20).single { it.id == second }
		assertEquals("answer_arrived", view.continueReason)
		assertEquals(first, view.resumedFromRunId)
		assertContains(view.instruction!!, "이전 실행이 남긴 질문과 사람의 답:")
		assertContains(view.instruction, "질문(${q.id}): 보존 기간은 30일인가 90일인가?")
		assertContains(view.instruction, "답(사람): 90일이다")
		assertEquals(second, questions.view(q.id).deliveryRunId)
		assertEquals(0, workItems.detail(s.workId).delegation.answersReady)

		val gen = runs.claim(second, s.device).generation
		runs.ingest(second, EventBatch(2, listOf(event(gen, 1, "lifecycle", mapOf("phase" to "started", "session_id" to session)))))
		val delivered = questions.view(q.id)
		assertEquals("resolved", delivered.state)
		assertEquals("answered", delivered.closeReason)
		assertEquals("resume", delivered.delivery)
		assertNotNull(delivered.deliveredAt)
	}

	@Test
	fun `a run's question on a done work item is refused, and a new session counts as a new session`() = withSkipped(listOf("plan_review", "result_review")) {
		val s = setup()
		val first = submitted(s, UUID.randomUUID().toString())
		assertEquals("done", workItems.detail(s.workId).item.stage)
		assertFailsWith<ConflictException> { ask(s.workId, askedBy = "run", runId = first) }

		val open = setup()
		val cut = run {
			delegations.delegate(open.workId, DelegateIn("claude_code", open.device, open.repoKey, "main", listOf("Read"), version(open.workId)))
			val id = newestRun(open.workId)
			val gen = runs.claim(id, open.device).generation
			runs.ingest(id, EventBatch(2, listOf(
				event(gen, 1, "lifecycle", mapOf("phase" to "started", "session_id" to "s-old")),
				event(gen, 2, "lifecycle", mapOf("phase" to "ended", "end_reason" to "failed", "executor_started" to true, "result_seen" to false)),
			)))
			id
		}
		ask(open.workId, "어느 쪽인가?", askedBy = "run", runId = cut)
		questions.answer(only(open.workId).id, AnswerIn("왼쪽"), "usr_owner")
		delegations.delegate(open.workId, DelegateIn("claude_code", open.device, open.repoKey, "main", listOf("Read"), version(open.workId)))
		val next = newestRun(open.workId)
		assertEquals("answer_arrived", reads.runs(open.workId, 20).single { it.id == next }.continueReason)
		val gen = runs.claim(next, open.device).generation
		runs.ingest(next, EventBatch(2, listOf(event(gen, 1, "lifecycle", mapOf("phase" to "started", "session_id" to "s-new")))))
		assertEquals("new_session", only(open.workId).delivery)
	}

	// ---------------------------------------------------------------- no answer

	@Test
	fun `no answer is never an answer - past its due time a question is raised again, still waiting`() {
		val s = setup()
		ask(s.workId, "답을 기다린다", blocking = false)
		val q = only(s.workId)
		assertEquals("digest", openItems(q.id).single().notify)
		questions.remindOverdue() // nothing due yet for this one
		assertEquals(0, questions.view(q.id).remindCount)
		assertEquals(0, openItems(q.id).single().repeat)

		jdbc.sql("update questions set due_at = now() - interval '1 minute' where id = :id").param("id", q.id).update()
		assertTrue(questions.view(q.id).overdue)
		assertTrue(questions.remindOverdue() >= 1)
		val after = questions.view(q.id)
		assertEquals("awaiting_answer", after.state)
		assertNull(after.answer)
		assertNull(after.answeredById)
		assertEquals(1, after.remindCount)
		assertFalse(after.overdue)
		assertTrue(after.dueAt.isAfter(java.time.OffsetDateTime.now()))
		assertEquals(listOf("waiting_answer"), waits(s.workId))
		val raised = openItems(q.id).single()
		assertEquals("immediate", raised.notify)
		assertEquals(1, raised.repeat)
		assertTrue(raised.summary.startsWith("기한 지남: "))
		assertEquals(1, jdbc.sql("select count(*) from audit_log where action = 'question.overdue' and target_id = :id").param("id", q.id)
			.query(Long::class.javaObjectType).single().toInt())
		assertEquals(0, jdbc.sql("select count(*) from approvals where target_id = :id").param("id", q.id).query(Long::class.javaObjectType).single().toInt())
		val history = questions.detail(q.id).routingHistory
		assertEquals(listOf("routed", "overdue"), history.map { it["event"] })
		// Raised once per window: sweeping again changes nothing.
		questions.remindOverdue()
		assertEquals(1, questions.view(q.id).remindCount)
	}

	@Test
	fun `cancelling the work item closes its questions with it`() {
		val s = setup()
		ask(s.workId)
		val q = only(s.workId)
		workItems.transition(s.workId, TransitionIn("cancelled", version(s.workId)))
		val closed = questions.view(q.id)
		assertEquals("resolved", closed.state)
		assertEquals("work_cancelled", closed.closeReason)
		assertTrue(openItems(q.id).isEmpty())
	}

	@Test
	fun `answering is a person action - a session token is refused and audited`() {
		val s = setup()
		ask(s.workId)
		val q = only(s.workId)
		val device = sessions.registerDevice(RegisterDevice("ai-pc")).deviceId
		val token = sessions.register(RegisterSession(2, device, "claude_code", executorSessionId = UUID.randomUUID().toString())).token
		fun post(path: String, body: String, bearer: String? = null): HttpResponse<String> {
			val b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
			if (bearer != null) b.header("Authorization", "Bearer $bearer")
			return http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
		}
		assertEquals(403, post("/api/questions/${q.id}/answer", """{"answer":"AI가 답한다"}""", token).statusCode())
		assertEquals(403, post("/api/work-items/${s.workId}/questions", """{"body":"x","kind":"fact","version":0}""", token).statusCode())
		assertTrue(jdbc.sql("select count(*) from audit_log where action = 'person_action.refused' and target_id = 'answer a question'")
			.query(Long::class.javaObjectType).single() >= 1)
		assertEquals("awaiting_answer", questions.view(q.id).state)
		val ok = post("/api/questions/${q.id}/answer", """{"answer":"사람이 답한다"}""")
		assertEquals(200, ok.statusCode(), ok.body())
		val list = http.send(HttpRequest.newBuilder(URI.create("$base/api/work-items/${s.workId}/questions")).GET().build(), HttpResponse.BodyHandlers.ofString())
		assertContains(list.body(), "\"answer\":\"사람이 답한다\"")
	}

	@Test
	fun `questions are in the export`() {
		assertTrue("questions" in ExportService.COLLECTIONS.map { it.second })
	}
}
