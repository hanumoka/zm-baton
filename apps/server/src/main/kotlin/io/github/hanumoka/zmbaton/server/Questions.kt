package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Questions and renegotiation (FN-0210; contract v1 "질문과 과금 신호", question path). A question
// about a work item goes to the person responsible for its source (QuestionRouting); while it waits
// the work item carries `waiting_answer` and that person's first screen shows it in bucket 2. The
// answer settles it, or waits for the document change it needs; a run's question is settled once a
// continuing run carried the answer to the executor (`continue_reason = answer_arrived`). No answer
// is never an answer: past its due time a question is raised again, still waiting.

data class AskIn(
	val body: String,
	/** interpretation, execution, policy_exception, fact or approval. */
	val kind: String,
	val blocking: Boolean = true,
	@JsonProperty("blocked_step") val blockedStep: String? = null,
	@JsonProperty("independent_steps") val independentSteps: String? = null,
	val options: List<String>? = null,
	/** `person` (a person asks) or `run`: a person records what a run of this work item asked. */
	@JsonProperty("asked_by") val askedBy: String = "person",
	@JsonProperty("run_id") val runId: String? = null,
	/** The work item's version the person saw. */
	val version: Long,
	@JsonProperty("due_hours") val dueHours: Int? = null,
)

data class AnswerIn(
	val answer: String,
	/** The answer changes a requirement, policy or contract: settled once the document change is recorded. */
	@JsonProperty("needs_doc_change") val needsDocChange: Boolean = false,
	@JsonProperty("doc_change_ref") val docChangeRef: String? = null,
	@JsonProperty("decision_ref") val decisionRef: String? = null,
	@JsonProperty("approval_ref") val approvalRef: String? = null,
	@JsonProperty("evidence_refs") val evidenceRefs: List<String>? = null,
)

data class DocChangeIn(@JsonProperty("doc_change_ref") val docChangeRef: String)

data class WithdrawQuestionIn(val note: String? = null)

data class QuestionView(
	val id: String,
	@JsonProperty("work_item_id") val workItemId: String,
	@JsonProperty("run_id") val runId: String?,
	val origin: String,
	val kind: String,
	val blocking: Boolean,
	@JsonProperty("blocked_step") val blockedStep: String?,
	@JsonProperty("independent_steps") val independentSteps: String?,
	val body: String,
	val options: List<String>,
	@JsonProperty("asked_by_kind") val askedByKind: String,
	@JsonProperty("asked_by_id") val askedById: String,
	@JsonProperty("routed_to_kind") val routedToKind: String,
	@JsonProperty("routed_to_id") val routedToId: String,
	@JsonProperty("routed_role") val routedRole: String,
	@JsonProperty("due_at") val dueAt: OffsetDateTime,
	val overdue: Boolean,
	@JsonProperty("remind_count") val remindCount: Int,
	val answer: String?,
	@JsonProperty("answered_by_kind") val answeredByKind: String?,
	@JsonProperty("answered_by_id") val answeredById: String?,
	@JsonProperty("answered_at") val answeredAt: OffsetDateTime?,
	@JsonProperty("needs_doc_change") val needsDocChange: Boolean,
	@JsonProperty("doc_change_ref") val docChangeRef: String?,
	@JsonProperty("decision_ref") val decisionRef: String?,
	@JsonProperty("approval_ref") val approvalRef: String?,
	@JsonProperty("evidence_refs") val evidenceRefs: List<String>,
	val delivery: String?,
	@JsonProperty("delivered_at") val deliveredAt: OffsetDateTime?,
	@JsonProperty("delivery_run_id") val deliveryRunId: String?,
	val state: String,
	@JsonProperty("close_reason") val closeReason: String?,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	/** The open first-screen item, to go there from the work item. */
	@JsonProperty("inbox_item_id") val inboxItemId: String?,
)

data class QuestionDetail(
	val question: QuestionView,
	@JsonProperty("work_item") val workItem: ReviewWorkItem,
	@JsonProperty("routing_history") val routingHistory: List<Map<String, Any?>>,
	/** What a person can do now. */
	@JsonProperty("can_answer") val canAnswer: Boolean,
	@JsonProperty("can_confirm_doc_change") val canConfirmDocChange: Boolean,
	@JsonProperty("can_withdraw") val canWithdraw: Boolean,
)

/**
 * Who answers (question path "받는 사람은 역할 규칙에 따라 고른다"). A question of interpretation
 * goes to the owner of the source text (the work item's linked document), else the work item's
 * owner; an execution or fact question to the work item's owner; a policy exception or an approval
 * request to the approver, never to an AI. Pure. Stage 1 has one person, so all lead to the owner,
 * but the rule is the one a team will use.
 */
object QuestionRouting {
	val KINDS = setOf("interpretation", "execution", "policy_exception", "fact", "approval")

	data class Route(val kind: String, val id: String, val role: String)

	fun route(kind: String, workOwner: String, documentOwner: String?, approver: String): Route = when (kind) {
		"interpretation" -> if (documentOwner != null) Route("person", documentOwner, "document_owner") else Route("person", workOwner, "work_owner")
		"policy_exception", "approval" -> Route("person", approver, "approver")
		else -> Route("person", workOwner, "work_owner")
	}

	/** The state an answer leaves: a document change first, then delivery to the run, else settled. */
	fun afterAnswer(needsDocChange: Boolean, hasDocChange: Boolean, fromRun: Boolean): String = when {
		needsDocChange && !hasDocChange -> "awaiting_doc_change"
		fromRun -> "answered"
		else -> "resolved"
	}
}

@Service
class QuestionService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
	transactions: PlatformTransactionManager,
) {
	companion object {
		const val BODY_MAX = 4000
		const val LINE_MAX = 300
		const val OPTIONS_MAX = 10
		/** Starting value of the answer window (question path: the organisation's setting). */
		const val DEFAULT_DUE_HOURS = 24
		val OPEN = listOf("open", "awaiting_answer")
	}

	private val listType = object : TypeReference<List<String>>() {}
	private val historyType = object : TypeReference<List<Map<String, Any?>>>() {}
	private val tx = TransactionTemplate(transactions)

	// ---------------------------------------------------------------- asking

	/**
	 * A person asks, or records what a run of this work item asked, about a work item that is still
	 * open. The question is routed, the work item gets `waiting_answer`, and the person it went to
	 * finds it in bucket 2 of the first screen, at once when it blocks the work. A run's question on
	 * the run's result in result review sends that result back to in progress: the run stopped to ask.
	 */
	@Transactional
	fun ask(workItemId: String, body: AskIn, actorId: String): WorkItemDetail {
		val text = body.body.trim()
		if (text.isEmpty() || text.length > BODY_MAX) throw BadRequestException("body must be 1-$BODY_MAX characters")
		val kind = body.kind.trim()
		if (kind !in QuestionRouting.KINDS) throw BadRequestException("kind must be one of ${QuestionRouting.KINDS}")
		val blockedStep = line(body.blockedStep, "blocked_step")
		val independent = line(body.independentSteps, "independent_steps")
		val options = body.options.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
		if (options.size > OPTIONS_MAX || options.any { it.length > LINE_MAX }) {
			throw BadRequestException("at most $OPTIONS_MAX options of up to $LINE_MAX characters")
		}
		val dueHours = body.dueHours ?: dueHours()
		if (dueHours !in 1..720) throw BadRequestException("due_hours must be 1-720")
		val facts = workItems.lockFacts(workItemId, body.version)
		facts.readOnlyReason?.let { throw ConflictException("$it. 질문은 원본 저장소에서 다룬다") }
		if (facts.stage in Workflow.CLOSED) {
			throw ConflictException("${Workflow.label(facts.stage)}된 작업에는 질문을 남기지 않는다. 이어서 할 일이면 새 작업으로 만든다")
		}

		val fromRun = when (body.askedBy.trim()) {
			"person" -> null
			"run" -> runOf(workItemId, body.runId ?: throw BadRequestException("a run's question needs run_id"))
			else -> throw BadRequestException("asked_by must be person or run")
		}
		if (fromRun != null && facts.stage == "result_review" && fromRun.newest) {
			workItems.transition(workItemId, TransitionIn("in_progress", body.version, "AI 실행의 질문이 남아 수행으로 돌린다: ${text.take(100)}"))
		}
		val (owner, title) = jdbc.sql("select responsible_user_id, title from work_items where id = :id").param("id", workItemId)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		val docOwner = jdbc.sql(
			"""
			select d.owner_user_id from document_work_items l join documents d on d.id = l.document_id
			 where l.work_item_id = :id order by l.created_at, d.id limit 1
			""".trimIndent(),
		).param("id", workItemId).query(String::class.java).optional().orElse(null)
		val route = QuestionRouting.route(kind, owner, docOwner, ApprovalPolicy.approverFor(WorkItemService.ORG, "question"))

		val id = Ids.question()
		val history = listOf(mapOf("at" to OffsetDateTime.now().toString(), "event" to "routed", "to" to route.id, "role" to route.role))
		jdbc.sql(
			"""
			insert into questions (id, org_id, work_item_id, blocked_step, run_id, executor_session_id, origin, kind, blocking,
			                       independent_steps, body, options, asked_by_kind, asked_by_id, routed_to_kind, routed_to_id,
			                       routed_role, routing_history, due_at)
			values (:id, :org, :wid, :step, :run, :esid, :origin, :kind, :blocking, :independent, :body, cast(:options as jsonb),
			        :akind, :aid, :rkind, :rid, :role, cast(:history as jsonb), now() + make_interval(hours => :hours))
			""".trimIndent(),
		).param("id", id).param("org", WorkItemService.ORG).param("wid", workItemId).param("step", blockedStep)
			.param("run", fromRun?.id).param("esid", fromRun?.sessionId).param("origin", if (fromRun != null) "run_answer" else "person")
			.param("kind", kind).param("blocking", body.blocking).param("independent", independent).param("body", text)
			.param("options", json.writeValueAsString(options)).param("akind", if (fromRun != null) "manager" else "person")
			.param("aid", fromRun?.askedBy ?: actorId).param("rkind", route.kind).param("rid", route.id).param("role", route.role)
			.param("history", json.writeValueAsString(history)).param("hours", dueHours).update()
		jdbc.sql(
			"""
			update work_items set wait_reasons = array_append(wait_reasons, 'waiting_answer'), version = version + 1, updated_at = now()
			 where id = :id and not ('waiting_answer' = any(wait_reasons))
			""".trimIndent(),
		).param("id", workItemId).update()
		raise(id, route.id, title, text, kind, body.blocking, fromRun != null, workItemId, remind = 0)
		val details = mapOf("question_id" to id, "kind" to kind, "blocking" to body.blocking, "asked_by" to body.askedBy,
			"run_id" to fromRun?.id, "routed_to" to route.id, "role" to route.role, "body" to text.take(200))
		sessions.audit("person", actorId, "question.asked", "qst", id, details)
		workItems.record(workItemId, "question.asked", details)
		return workItems.detail(workItemId)
	}

	private data class AskingRun(val id: String, val sessionId: String?, val askedBy: String, val newest: Boolean)

	/** The ended delegated run of this work item that asked; its executor is the manager the delegation named. */
	private fun runOf(workItemId: String, runId: String): AskingRun {
		val run = jdbc.sql(
			"""
			select r.id, r.executor_session_id, d.grantee_id, r.state,
			       r.id = (select n.id from runs n where n.work_item_id = r.work_item_id order by n.created_at desc, n.id desc limit 1)
			  from runs r join delegations d on d.id = r.delegation_id where r.id = :run and r.work_item_id = :wid
			""".trimIndent(),
		).param("run", runId.trim()).param("wid", workItemId).query { rs, _ ->
			AskingRun(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(5)) to rs.getString(4)
		}.optional().orElseThrow { BadRequestException("run $runId is not a delegated run of this work item") }
		if (run.second != "ended") throw ConflictException("실행이 아직 끝나지 않았다. 끝난 뒤 그 결과의 질문을 남긴다")
		return run.first
	}

	/** Puts the question on the first screen of the person it went to: bucket 2, at once when it blocks. */
	private fun raise(id: String, user: String, title: String, body: String, kind: String, blocking: Boolean, fromRun: Boolean, workItemId: String, remind: Int) {
		val summary = (if (remind > 0) "기한 지남: " else "") + "$title: ${body.replace(Regex("\\s+"), " ").take(150)}"
		val detail = mapOf("work_item_id" to workItemId, "title" to title, "body" to body.take(1000), "kind" to kind,
			"blocking" to blocking, "from_run" to fromRun, "overdue" to (remind > 0))
		jdbc.sql(
			"""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, detail, repeat_count, waiting_since, notify)
			select :id, :user, 2, 'run_question', 'qst', :q, :summary, cast(:detail as jsonb), :repeat, q.created_at, :notify
			  from questions q where q.id = :q
			""".trimIndent(),
		).param("id", Ids.inbox()).param("user", user).param("q", id).param("summary", summary.take(300))
			.param("detail", json.writeValueAsString(detail)).param("repeat", remind)
			.param("notify", if (blocking || remind > 0) "immediate" else "digest").update()
	}

	// ---------------------------------------------------------------- answering

	/**
	 * A person answers. The answer, who gave it and the records it led to (a document change, a
	 * decision, an approval) are kept with the question. A person's question is settled; a run's
	 * waits until a continuing run carries it; an answer that needs a document change waits for it
	 * first. The work item's `waiting_answer` comes off with its last waiting question.
	 */
	@Transactional
	fun answer(questionId: String, body: AnswerIn, actorId: String): QuestionView {
		val text = body.answer.trim()
		if (text.isEmpty() || text.length > BODY_MAX) throw BadRequestException("answer must be 1-$BODY_MAX characters")
		val evidence = body.evidenceRefs.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
		if (evidence.size > 10 || evidence.any { it.length > LINE_MAX }) throw BadRequestException("at most 10 evidence_refs of up to $LINE_MAX characters")
		val docChange = body.docChangeRef?.trim()?.ifEmpty { null }?.also(::requireDocChange)
		val decision = body.decisionRef?.trim()?.ifEmpty { null }?.also { requireRow("decisions", it, "decision_ref") }
		val approval = body.approvalRef?.trim()?.ifEmpty { null }?.also { requireRow("approvals", it, "approval_ref") }
		val q = lock(questionId)
		if (q.state !in OPEN) throw ConflictException(stateReason(q.state))
		val state = QuestionRouting.afterAnswer(body.needsDocChange, docChange != null, q.runId != null)
		jdbc.sql(
			"""
			update questions
			   set answer = :answer, answered_by_kind = 'person', answered_by_id = :by, answered_at = now(),
			       evidence_refs = cast(:evidence as jsonb), doc_change_ref = :doc, needs_doc_change = :needs,
			       decision_ref = :decision, approval_ref = :approval, state = :state,
			       close_reason = case when cast(:state as text) = 'resolved' then 'answered' else null end
			 where id = :id
			""".trimIndent(),
		).param("answer", text).param("by", actorId).param("evidence", json.writeValueAsString(evidence)).param("doc", docChange)
			.param("needs", body.needsDocChange).param("decision", decision).param("approval", approval).param("state", state)
			.param("id", questionId).update()
		closeInbox(questionId)
		releaseWait(q.workItemId)
		val details = mapOf("question_id" to questionId, "state" to state, "answer" to text.take(200), "doc_change_ref" to docChange,
			"decision_ref" to decision, "approval_ref" to approval, "needs_doc_change" to body.needsDocChange)
		sessions.audit("person", actorId, "question.answered", "qst", questionId, details)
		workItems.record(q.workItemId, "question.answered", details)
		return view(questionId)
	}

	/** The document change an answer waited for is recorded: the question goes on to delivery, or is settled. */
	@Transactional
	fun confirmDocChange(questionId: String, body: DocChangeIn, actorId: String): QuestionView {
		val ref = body.docChangeRef.trim().also(::requireDocChange)
		val q = lock(questionId)
		if (q.state != "awaiting_doc_change") throw ConflictException("문서 반영을 기다리는 질문이 아니다")
		val state = QuestionRouting.afterAnswer(needsDocChange = true, hasDocChange = true, fromRun = q.runId != null)
		jdbc.sql(
			"""
			update questions set doc_change_ref = :ref, state = :state,
			       close_reason = case when cast(:state as text) = 'resolved' then 'answered' else null end
			 where id = :id
			""".trimIndent(),
		).param("ref", ref).param("state", state).param("id", questionId).update()
		val details = mapOf("question_id" to questionId, "doc_change_ref" to ref, "state" to state)
		sessions.audit("person", actorId, "question.doc_changed", "qst", questionId, details)
		workItems.record(q.workItemId, "question.doc_changed", details)
		return view(questionId)
	}

	/** A person takes a question back before it is settled. */
	@Transactional
	fun withdraw(questionId: String, body: WithdrawQuestionIn, actorId: String): QuestionView {
		val note = body.note?.trim()?.take(LINE_MAX)?.ifEmpty { null }
		val q = lock(questionId)
		if (q.state == "resolved") throw ConflictException(stateReason(q.state))
		jdbc.sql("update questions set state = 'resolved', close_reason = 'withdrawn' where id = :id").param("id", questionId).update()
		closeInbox(questionId)
		releaseWait(q.workItemId)
		val details = mapOf("question_id" to questionId, "note" to note)
		sessions.audit("person", actorId, "question.withdrawn", "qst", questionId, details)
		workItems.record(q.workItemId, "question.withdrawn", details)
		return view(questionId)
	}

	private data class Locked(val workItemId: String, val runId: String?, val state: String)

	/** The work item first, then the question: the order a report and a handoff lock in. */
	private fun lock(questionId: String): Locked {
		val wid = jdbc.sql("select work_item_id from questions where id = :id").param("id", questionId).query(String::class.java)
			.optional().orElseThrow { NotFoundException("no question $questionId") }
		jdbc.sql("select id from work_items where id = :id for update").param("id", wid).query(String::class.java).list()
		return jdbc.sql("select work_item_id, run_id, state from questions where id = :id for update").param("id", questionId)
			.query { rs, _ -> Locked(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
	}

	private fun stateReason(state: String) = when (state) {
		"resolved" -> "이미 닫힌 질문이다"
		"answered", "awaiting_doc_change" -> "이미 답한 질문이다"
		else -> "답할 수 없는 상태다: $state"
	}

	/** `waiting_answer` comes off once no question of the work item waits for an answer. */
	private fun releaseWait(workItemId: String) {
		jdbc.sql(
			"""
			update work_items set wait_reasons = array_remove(wait_reasons, 'waiting_answer'), version = version + 1, updated_at = now()
			 where id = :id and 'waiting_answer' = any(wait_reasons)
			   and not exists (select 1 from questions q where q.work_item_id = :id and q.state in ('open', 'awaiting_answer'))
			""".trimIndent(),
		).param("id", workItemId).update()
	}

	private fun closeInbox(questionId: String) {
		jdbc.sql("update inbox_items set state = 'closed', closed_at = now() where source_kind = 'qst' and source_id = :id and state = 'open'")
			.param("id", questionId).update()
	}

	private fun requireDocChange(ref: String) {
		val table = when {
			ref.startsWith("dvr_") -> "document_versions"
			ref.startsWith("doc_") -> "documents"
			else -> throw BadRequestException("doc_change_ref must be a document (doc_) or document version (dvr_)")
		}
		requireRow(table, ref, "doc_change_ref")
	}

	// The table name is one of the constants above, never input.
	private fun requireRow(table: String, id: String, field: String) {
		val found = jdbc.sql("select count(*) from $table where id = :id").param("id", id).query(Long::class.javaObjectType).single() > 0
		if (!found) throw BadRequestException("$field $id does not exist")
	}

	private fun line(raw: String?, field: String): String? {
		val t = raw?.trim()?.ifEmpty { null } ?: return null
		if (t.length > LINE_MAX) throw BadRequestException("$field must be at most $LINE_MAX characters")
		return t
	}

	private fun dueHours(): Int =
		jdbc.sql("select (settings ->> 'question_due_hours')::int from organizations where id = :org").param("org", WorkItemService.ORG)
			.query(Int::class.javaObjectType).optional().orElse(null)?.takeIf { it in 1..720 } ?: DEFAULT_DUE_HOURS

	// ---------------------------------------------------------------- delivering the answer

	/** Answered questions of the work item's runs that no continuing run carried yet, oldest first. */
	fun answersToDeliver(workItemId: String): List<Instructions.Answer> =
		jdbc.sql(
			"""
			select id, body, answer from questions
			 where work_item_id = :wid and state = 'answered' and run_id is not null and delivery_run_id is null
			 order by created_at, id
			""".trimIndent(),
		).param("wid", workItemId).query { rs, _ -> Instructions.Answer(rs.getString(1), rs.getString(2), rs.getString(3)) }.list()

	/** The continuing run that carries these answers (DelegationService, in its transaction). */
	fun attachDelivery(questionIds: List<String>, runId: String) {
		if (questionIds.isEmpty()) return
		jdbc.sql("update questions set delivery_run_id = :run where id in (:ids) and state = 'answered'")
			.param("run", runId).param("ids", questionIds).update()
	}

	/**
	 * The continuing run's executor started with the answers in its instruction (question path "③ 답을
	 * 실행기에 전달한다" 4): the questions are settled, through the same session (`resume`) or a new one.
	 * Called in the report's transaction (RunService).
	 */
	fun markDelivered(runId: String) {
		val delivered = jdbc.sql(
			"""
			update questions q
			   set delivered_at = now(), state = 'resolved', close_reason = 'answered',
			       delivery = case when r.executor_session_id is not null and r.executor_session_id = q.executor_session_id
			                       then 'resume' else 'new_session' end
			  from runs r
			 where r.id = :run and q.delivery_run_id = r.id and q.state = 'answered'
			returning q.id, q.work_item_id, q.delivery
			""".trimIndent(),
		).param("run", runId).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.list()
		for ((id, workItemId, how) in delivered) {
			sessions.audit("system", runId, "question.delivered", "qst", id, mapOf("run_id" to runId, "delivery" to how, "work_item_id" to workItemId))
		}
	}

	// ---------------------------------------------------------------- no answer

	/**
	 * Questions past their due time are raised again, never answered or approved (question path "응답이
	 * 없으면…" 3, FN-0210). Stage 1 has no deputy or superior, so the same person is told again: a new
	 * first-screen item, at once, and the next due time one window later. Each question in its own
	 * transaction. Returns how many were raised.
	 */
	fun remindOverdue(): Int {
		val due = jdbc.sql("select id from questions where state = 'awaiting_answer' and due_at < now() order by due_at limit 100")
			.query(String::class.java).list().filterNotNull()
		return due.count { id -> tx.execute { remind(id) } == true }
	}

	private fun remind(questionId: String): Boolean {
		data class Row(val routedTo: String, val title: String, val body: String, val kind: String, val blocking: Boolean, val fromRun: Boolean, val workItemId: String, val count: Int)
		val row = jdbc.sql(
			"""
			select q.routed_to_id, w.title, q.body, q.kind, q.blocking, q.run_id is not null, q.work_item_id, q.remind_count + 1
			  from questions q join work_items w on w.id = q.work_item_id
			 where q.id = :id and q.state = 'awaiting_answer' and q.due_at < now()
			   for update of q
			""".trimIndent(),
		).param("id", questionId).query { rs, _ ->
			Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getString(7), rs.getInt(8))
		}.optional().orElse(null) ?: return false
		val entry = mapOf("at" to OffsetDateTime.now().toString(), "event" to "overdue", "to" to row.routedTo,
			"note" to "기한이 지났다. 대리자·상위 책임자가 없어 같은 사람에게 다시 알린다. 답이나 승인으로 치지 않는다")
		jdbc.sql(
			"""
			update questions
			   set remind_count = :count, reminded_at = now(), routing_history = routing_history || cast(:entry as jsonb),
			       due_at = now() + make_interval(hours => :hours)
			 where id = :id
			""".trimIndent(),
		).param("count", row.count).param("entry", json.writeValueAsString(listOf(entry))).param("hours", dueHours()).param("id", questionId).update()
		closeInbox(questionId)
		raise(questionId, row.routedTo, row.title, row.body, row.kind, row.blocking, row.fromRun, row.workItemId, remind = row.count)
		sessions.audit("system", SessionService.SYSTEM_ACTOR, "question.overdue", "qst", questionId, mapOf("remind_count" to row.count))
		return true
	}

	// ---------------------------------------------------------------- reads

	private val SELECT = """
		select q.id, q.work_item_id, q.run_id, q.origin, q.kind, q.blocking, q.blocked_step, q.independent_steps, q.body,
		       q.options::text, q.asked_by_kind, q.asked_by_id, q.routed_to_kind, q.routed_to_id, q.routed_role, q.due_at,
		       (q.state = 'awaiting_answer' and q.due_at < now()) as overdue, q.remind_count, q.answer, q.answered_by_kind,
		       q.answered_by_id, q.answered_at, q.needs_doc_change, q.doc_change_ref, q.decision_ref, q.approval_ref,
		       q.evidence_refs::text, q.delivery, q.delivered_at, q.delivery_run_id, q.state, q.close_reason, q.created_at,
		       (select i.id from inbox_items i where i.source_kind = 'qst' and i.source_id = q.id and i.state = 'open' limit 1)
		  from questions q
	""".trimIndent()

	private fun row(rs: java.sql.ResultSet) = QuestionView(
		rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6), rs.getString(7),
		rs.getString(8), rs.getString(9), json.readValue(rs.getString(10), listType), rs.getString(11), rs.getString(12),
		rs.getString(13), rs.getString(14), rs.getString(15), rs.getObject(16, OffsetDateTime::class.java), rs.getBoolean(17),
		rs.getInt(18), rs.getString(19), rs.getString(20), rs.getString(21), rs.getObject(22, OffsetDateTime::class.java),
		rs.getBoolean(23), rs.getString(24), rs.getString(25), rs.getString(26), json.readValue(rs.getString(27), listType),
		rs.getString(28), rs.getObject(29, OffsetDateTime::class.java), rs.getString(30), rs.getString(31), rs.getString(32),
		rs.getObject(33, OffsetDateTime::class.java), rs.getString(34),
	)

	fun view(questionId: String): QuestionView =
		jdbc.sql("$SELECT where q.id = :id").param("id", questionId).query { rs, _ -> row(rs) }.optional()
			.orElseThrow { NotFoundException("no question $questionId") }

	fun forWorkItem(workItemId: String): List<QuestionView> =
		jdbc.sql("$SELECT where q.work_item_id = :id order by q.created_at, q.id").param("id", workItemId).query { rs, _ -> row(rs) }.list()

	fun detail(questionId: String): QuestionDetail {
		val q = view(questionId)
		val work = jdbc.sql(
			"""
			select w.id, w.title, w.external_ref, w.stage, w.version, p.id, p.external_ref, p.name
			  from work_items w left join projects p on p.id = w.project_id where w.id = :id
			""".trimIndent(),
		).param("id", q.workItemId).query { rs, _ ->
			ReviewWorkItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
				rs.getString(6)?.let { ProjectRef(it, rs.getString(7), rs.getString(8)) })
		}.single()
		val history = jdbc.sql("select routing_history::text from questions where id = :id").param("id", questionId)
			.query(String::class.java).single().let { json.readValue(it, historyType) }
		return QuestionDetail(q, work, history, q.state in OPEN, q.state == "awaiting_doc_change", q.state != "resolved")
	}
}

@RestController
@RequestMapping("/api")
class QuestionController(private val questions: QuestionService, private val people: PersonService) {

	/** Returns the work item, like every other change to it: its version and wait reasons moved. */
	@PostMapping("/work-items/{id}/questions")
	@ResponseStatus(HttpStatus.CREATED)
	fun ask(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: AskIn,
	): WorkItemDetail {
		people.refuseSessionToken(authorization, "ask a question")
		return questions.ask(id, body, Requester.person())
	}

	@GetMapping("/work-items/{id}/questions")
	fun list(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): List<QuestionView> {
		people.refuseSessionToken(authorization, "read questions")
		return questions.forWorkItem(id)
	}

	@GetMapping("/questions/{id}")
	fun detail(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): QuestionDetail {
		people.refuseSessionToken(authorization, "read a question")
		return questions.detail(id)
	}

	@PostMapping("/questions/{id}/answer")
	fun answer(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: AnswerIn,
	): QuestionView {
		people.refuseSessionToken(authorization, "answer a question")
		return questions.answer(id, body, Requester.person())
	}

	@PostMapping("/questions/{id}/doc-change")
	fun docChange(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: DocChangeIn,
	): QuestionView {
		people.refuseSessionToken(authorization, "record a question's document change")
		return questions.confirmDocChange(id, body, Requester.person())
	}

	@PostMapping("/questions/{id}/withdraw")
	fun withdraw(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: WithdrawQuestionIn,
	): QuestionView {
		people.refuseSessionToken(authorization, "withdraw a question")
		return questions.withdraw(id, body, Requester.person())
	}
}

/** Raises questions past their due time again. Runs inside the server, like the other sweeps. */
@Component
class QuestionSweeper(private val questions: QuestionService) {
	@Scheduled(fixedDelayString = "\${zm-baton.question-sweep-ms:60000}", initialDelayString = "\${zm-baton.question-sweep-ms:60000}")
	fun sweep() {
		questions.remindOverdue()
	}
}
