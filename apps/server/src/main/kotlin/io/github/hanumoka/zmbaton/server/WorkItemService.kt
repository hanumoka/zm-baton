package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.ScopeColumns
import io.github.hanumoka.zmbaton.server.permission.Visibility
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// Work items a person creates, edits and moves through their stages (contract v1 "작업"), the
// projects they belong to and the organisation's workflow setting. Every change checks the
// `version` the caller saw and is written to audit_log, which is also the work item's history.
// Who may make a change is judged before it (Permissions); the person who made it is its actor.

data class CreateWorkItem(
	val title: String,
	@JsonProperty("responsible_user_id") val responsibleUserId: String,
	@JsonProperty("project_id") val projectId: String? = null,
	val acceptance: String? = null,
	@JsonProperty("next_action") val nextAction: String? = null,
	@JsonProperty("due_on") val dueOn: String? = null,
	/** `draft` or `ready`. Absent keeps the compat-test default `ready`. */
	@JsonProperty("initial_stage") val initialStage: String? = null,
	/** `human` makes the responsible person the executor. Absent leaves the executor unset. */
	@JsonProperty("executor_kind") val executorKind: String? = null,
	/** Added with permissions: the sharing scope (G06.1). Absent: `org`, as every work item before. */
	val visibility: String? = null,
)

data class WorkItemCreated(val id: String, val stage: String)

data class ProjectRef(
	val id: String,
	@JsonProperty("external_ref") val externalRef: String?,
	val name: String,
)

data class WorkItemView(
	val id: String,
	val title: String,
	val stage: String,
	@JsonProperty("wait_reasons") val waitReasons: List<String>,
	val generation: Long,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	// Added in the second stage-1 slice; the fields above are what the compat screen reads.
	val version: Long,
	@JsonProperty("external_ref") val externalRef: String?,
	val project: ProjectRef?,
	@JsonProperty("source_of_truth") val sourceOfTruth: String,
	@JsonProperty("executor_kind") val executorKind: String?,
	@JsonProperty("next_action") val nextAction: String?,
	@JsonProperty("wait_note") val waitNote: String?,
	@JsonProperty("opened_on") val openedOn: LocalDate?,
	@JsonProperty("due_on") val dueOn: LocalDate?,
	@JsonProperty("closed_at") val closedAt: OffsetDateTime?,
	@JsonProperty("updated_at") val updatedAt: OffsetDateTime,
	/** Added with permissions: the sharing scope (G06.1): personal, restricted, team, org or public. */
	val visibility: String = "org",
)

data class MoveView(
	val target: String,
	val path: List<String>,
	val allowed: Boolean,
	val reason: String?,
	@JsonProperty("needs_note") val needsNote: Boolean,
)

data class HistoryEntry(
	val action: String,
	@JsonProperty("actor_kind") val actorKind: String,
	@JsonProperty("actor_id") val actorId: String,
	val details: Map<String, Any?>,
	val at: OffsetDateTime,
)

data class WorkItemDetail(
	val item: WorkItemView,
	@JsonProperty("responsible_user_id") val responsibleUserId: String,
	@JsonProperty("executor_id") val executorId: String?,
	val acceptance: String?,
	@JsonProperty("source_ref") val sourceRef: String?,
	@JsonProperty("git_ref") val gitRef: Map<String, Any?>?,
	@JsonProperty("live_run") val liveRun: Boolean,
	@JsonProperty("read_only_reason") val readOnlyReason: String?,
	val moves: List<MoveView>,
	val history: List<HistoryEntry>,
	/** Added with the third stage-1 slice: whether the work item can be handed to an AI now. */
	val delegation: DelegationAvailability,
	/** Added with questions: whether a question can be left on the work item now, and why not. */
	val question: ActionAvailability = ActionAvailability(true, null),
)

/** Whether a person can hand the work item to an AI now, and if not, why (contract "위임"). */
data class DelegationAvailability(
	val allowed: Boolean,
	val reason: String?,
	// Added with run control (stopping, resuming and steering a run).
	/** `first`, `resume` (continue an `in_progress` work item) or `reject_and_resume`; null when not allowed. */
	val mode: String? = null,
	/** The request needs a note: why the result goes back. */
	@JsonProperty("needs_note") val needsNote: Boolean = false,
	/** The run a continuing run follows, so a screen can offer its repository and tools again. */
	@JsonProperty("resume_from") val resumeFrom: PreviousRun? = null,
	/** Added with questions: answered questions of earlier runs that the next run carries (`answer_arrived`). */
	@JsonProperty("answers_ready") val answersReady: Int = 0,
)

/** The newest delegated run of a work item that a connector claimed and that has ended. */
data class PreviousRun(
	@JsonProperty("run_id") val runId: String,
	@JsonProperty("end_reason") val endReason: String,
	@JsonProperty("device_id") val deviceId: String?,
	@JsonProperty("repo_key") val repoKey: String,
	@JsonProperty("base_branch") val baseBranch: String?,
	@JsonProperty("allowed_tools") val allowedTools: List<String>,
	/** Read by the server only: the executor session and worktree a resumed run may continue. */
	@JsonIgnore val sessionId: String? = null,
	@JsonIgnore val workspace: Map<String, Any?>? = null,
	/** Added with AI accounts: the account the earlier run went on, so a screen can offer it again. */
	@JsonProperty("account_id") val accountId: String? = null,
)

data class TransitionIn(val to: String, val version: Long, val note: String? = null)

data class WaitReasonIn(val reason: String, val version: Long, val note: String? = null)

data class ProjectView(
	val id: String,
	@JsonProperty("external_ref") val externalRef: String?,
	val name: String,
	@JsonProperty("open_work_items") val openWorkItems: Long,
)

data class WorkflowSettings(@JsonProperty("skipped_stages") val skippedStages: List<String>)

@Service
class WorkItemService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val sessions: SessionService,
	private val facts: PermissionFacts,
) {
	companion object {
		/** The stage-1 organisation, kept for callers written before organisations had their own rows. */
		const val ORG = Requester.STAGE1_ORG
		const val TITLE_MAX = 300
		const val TEXT_MAX = 4000
		const val NOTE_MAX = 1000
		const val HISTORY_MAX = 50
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	// ---------------------------------------------------------------- create and edit

	/**
	 * A new work item in [actor]'s organisation. The responsible person must be an active member of it
	 * (data model constraint 1: always a person).
	 */
	@Transactional
	fun create(body: CreateWorkItem, actor: String): WorkItemCreated {
		val owner = body.responsibleUserId.trim()
		if (owner.isEmpty()) throw BadRequestException("responsible_user_id is required")
		val org = facts.orgOf(actor)
		if (!facts.isActiveMember(org, owner)) throw BadRequestException("책임자($owner)는 이 조직에서 활동 중인 사람이어야 한다")
		val visibility = visibility(body.visibility) ?: Visibility.ORG
		val title = title(body.title)
		val stage = body.initialStage?.trim() ?: "ready"
		if (stage !in setOf("draft", "ready")) throw BadRequestException("initial_stage must be draft or ready")
		val executorKind = body.executorKind?.trim()
		if (executorKind != null && executorKind != "human") throw BadRequestException("executor_kind must be human")
		val projectId = body.projectId?.trim()?.ifEmpty { null }?.also(::requireProject)
		val id = Ids.workItem()
		jdbc.sql(
			"""
			insert into work_items (id, org_id, title, stage, responsible_user_id, executor_kind, executor_id, project_id,
			                        acceptance, next_action, due_on, opened_on, visibility)
			values (:id, :org, :title, :stage, :owner, :ekind, :eid, :project, :acceptance, :next, :due, current_date, :visibility)
			""".trimIndent(),
		).param("id", id).param("org", org).param("title", title).param("stage", stage).param("owner", owner)
			.param("visibility", visibility.wire)
			.param("ekind", executorKind).param("eid", if (executorKind == "human") owner else null)
			.param("project", projectId).param("acceptance", text(body.acceptance, "acceptance"))
			.param("next", text(body.nextAction, "next_action")).param("due", date(body.dueOn, "due_on"))
			.update()
		history(id, "work_item.created", mapOf("stage" to stage, "title" to title, "visibility" to visibility.wire), actor)
		return WorkItemCreated(id, stage)
	}

	/**
	 * Changes the fields present in [patch]. A present key with null clears the field; `title`
	 * cannot be cleared. `version` must be the version the caller saw.
	 */
	@Transactional
	fun update(id: String, patch: Map<String, Any?>, actor: String): WorkItemDetail {
		val version = (patch["version"] as? Number)?.toLong() ?: throw BadRequestException("version is required")
		val known = setOf("version", "title", "acceptance", "next_action", "due_on", "project_id", "visibility")
		val unknown = patch.keys - known
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown")
		val row = lockEditable(id, version)
		val changes = linkedMapOf<String, Any?>()
		if ("title" in patch) changes["title"] = title(patch["title"] as? String ?: throw BadRequestException("title must be text"))
		if ("acceptance" in patch) changes["acceptance"] = text(patch["acceptance"] as String?, "acceptance")
		if ("next_action" in patch) changes["next_action"] = text(patch["next_action"] as String?, "next_action")
		if ("due_on" in patch) changes["due_on"] = date(patch["due_on"] as String?, "due_on")
		if ("project_id" in patch) changes["project_id"] = (patch["project_id"] as String?)?.trim()?.ifEmpty { null }?.also(::requireProject)
		if ("visibility" in patch) {
			changes["visibility"] = (visibility(patch["visibility"] as? String) ?: throw BadRequestException("visibility cannot be cleared")).wire
		}
		if (changes.isEmpty()) throw BadRequestException("nothing to change")
		// Column names come from the fixed set above, never from input.
		val sets = changes.keys.joinToString(", ") { "$it = :$it" }
		val stmt = jdbc.sql("update work_items set $sets, version = version + 1, updated_at = now() where id = :id")
			.param("id", row.id)
		changes.forEach { (k, v) -> stmt.param(k, v) }
		stmt.update()
		history(id, "work_item.updated", changes.mapValues { it.value?.toString() }, actor)
		return detail(id)
	}

	// ---------------------------------------------------------------- stages and wait reasons

	@Transactional
	fun transition(id: String, body: TransitionIn, actor: String): WorkItemDetail {
		val row = lockEditable(id, body.version, checkSource = false)
		val note = body.note?.trim()?.take(NOTE_MAX)?.ifEmpty { null }
		val target = body.to.trim()
		if (target !in Workflow.STAGES) throw BadRequestException("unknown stage $target")
		val move = Workflow.move(facts(row), target)
		if (!move.allowed) throw ConflictException(move.reason ?: "not allowed")
		if (move.needsNote && note == null) throw BadRequestException("this move needs a note")
		val closing = move.finalStage in Workflow.CLOSED
		// Closing a work item ends the waits that only made sense while it was open.
		jdbc.sql(
			"""
			update work_items
			   set stage = :stage, version = version + 1, updated_at = now(),
			       wait_reasons = case when :closing then '{}'::text[] else wait_reasons end,
			       wait_note = case when :closing then null else wait_note end,
			       closed_at = case when :closing then now() else null end
			 where id = :id
			""".trimIndent(),
		).param("stage", move.finalStage).param("closing", closing).param("id", id).update()
		history(
			id, "work_item.stage_changed",
			mapOf("from" to row.stage, "to" to move.finalStage, "path" to move.path, "note" to note), actor,
		)
		if (move.finalStage == "cancelled") closeQuestions(id, actor)
		return detail(id)
	}

	/**
	 * A cancelled work item takes its questions with it (question path: close reason
	 * `work_cancelled`), and their first-screen items close in the same transaction.
	 */
	private fun closeQuestions(id: String, actor: String) {
		jdbc.sql(
			"""
			update inbox_items set state = 'closed', closed_at = now()
			 where source_kind = 'qst' and state = 'open'
			   and source_id in (select q.id from questions q where q.work_item_id = :id and q.state <> 'resolved')
			""".trimIndent(),
		).param("id", id).update()
		val closed = jdbc.sql(
			"update questions set state = 'resolved', close_reason = 'work_cancelled' where work_item_id = :id and state <> 'resolved'",
		).param("id", id).update()
		if (closed > 0) history(id, "question.closed_with_work", mapOf("count" to closed), actor)
	}

	@Transactional
	fun addWait(id: String, body: WaitReasonIn, actor: String): WorkItemDetail {
		val reason = body.reason.trim()
		if (reason !in Workflow.PERSON_WAITS) throw BadRequestException("a person can set only ${Workflow.PERSON_WAITS}")
		val row = lockEditable(id, body.version)
		if (row.stage in Workflow.CLOSED) throw ConflictException("a closed work item has no wait reasons")
		val note = body.note?.trim()?.take(NOTE_MAX)?.ifEmpty { null }
		if (reason == "waiting_other" && note == null) throw BadRequestException("waiting_other needs a note")
		jdbc.sql(
			"""
			update work_items
			   set wait_reasons = case when :reason = any(wait_reasons) then wait_reasons else array_append(wait_reasons, :reason) end,
			       wait_note = case when :reason = 'waiting_other' then :note else wait_note end,
			       version = version + 1, updated_at = now()
			 where id = :id
			""".trimIndent(),
		).param("reason", reason).param("note", note).param("id", id).update()
		history(id, "work_item.wait_added", mapOf("reason" to reason, "note" to note), actor)
		return detail(id)
	}

	@Transactional
	fun removeWait(id: String, reason: String, version: Long, actor: String): WorkItemDetail {
		if (reason !in Workflow.PERSON_WAITS) throw BadRequestException("a person can clear only ${Workflow.PERSON_WAITS}")
		val row = lockEditable(id, version)
		if (reason !in row.waitReasons) throw ConflictException("the work item is not $reason")
		jdbc.sql(
			"""
			update work_items
			   set wait_reasons = array_remove(wait_reasons, :reason),
			       wait_note = case when :reason = 'waiting_other' then null else wait_note end,
			       version = version + 1, updated_at = now()
			 where id = :id
			""".trimIndent(),
		).param("reason", reason).param("id", id).update()
		history(id, "work_item.wait_removed", mapOf("reason" to reason), actor)
		return detail(id)
	}

	// ---------------------------------------------------------------- reads

	/**
	 * Work items, newest first. [open] keeps only those not done or cancelled; [query] matches
	 * the title, external number and next action. With [viewer], only those its scope lets them see.
	 */
	fun list(limit: Int, projectId: String?, open: Boolean, query: String?, viewer: Actor? = null): List<WorkItemView> {
		val where = mutableListOf("true")
		val scope = viewer?.let { PermissionRules.filter(it, ScopeColumns.WORK_ITEMS) }
		if (scope != null) where += scope.sql
		if (projectId != null) where += "w.project_id = :project"
		if (open) where += "w.stage not in ('done', 'cancelled')"
		val q = query?.trim()?.take(100)?.ifEmpty { null }
		if (q != null) where += "(w.title ilike :q or w.external_ref ilike :q or w.next_action ilike :q)"
		val stmt = jdbc.sql(
			"$SELECT_VIEW where ${where.joinToString(" and ")} order by w.created_at desc, w.id desc limit :limit",
		).param("limit", limit)
		if (projectId != null) stmt.param("project", projectId)
		if (q != null) stmt.param("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
		scope?.params?.forEach { (k, v) -> stmt.param(k, v) }
		return stmt.query { rs, _ -> view(rs) }.list()
	}

	/**
	 * Work items a person holds: responsible for, or executing themselves or through their manager
	 * (contract "현재 상태 카드" 1). Open ones first, work under way first, then the most recently changed.
	 */
	fun involving(personId: String, managerId: String?, limit: Int): List<WorkItemView> =
		jdbc.sql(
			"""
			$SELECT_VIEW where w.responsible_user_id = :p or w.executor_id in (:who)
			 order by (w.stage in ('done', 'cancelled')), (w.stage = 'in_progress') desc, w.updated_at desc, w.id desc limit :limit
			""".trimIndent(),
		).param("p", personId).param("who", listOfNotNull(personId, managerId)).param("limit", limit).query { rs, _ -> view(rs) }.list()

	fun detail(id: String): WorkItemDetail {
		val row = load(id) ?: throw NotFoundException("no work item $id")
		val view = jdbc.sql("$SELECT_VIEW where w.id = :id").param("id", id).query { rs, _ -> view(rs) }.single()
		val facts = facts(row)
		val plan = Workflow.delegationPlan(facts)
		val history = jdbc.sql(
			"""
			select action, actor_kind, actor_id, details::text, at from audit_log
			 where target_kind = 'wrk' and target_id = :id order by at desc, id desc limit :limit
			""".trimIndent(),
		).param("id", id).param("limit", HISTORY_MAX).query { rs, _ ->
			HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), json.readValue(rs.getString(4), mapType),
				rs.getObject(5, OffsetDateTime::class.java))
		}.list()
		return WorkItemDetail(
			view, row.responsibleUserId, row.executorId, row.acceptance, row.sourceRef, row.gitRef, row.hasLiveRun,
			facts.readOnlyReason,
			Workflow.moves(facts).map { MoveView(it.target, it.path, it.allowed, it.reason, it.needsNote) },
			history,
			DelegationAvailability(
				plan.mode != null, plan.refusal, plan.mode?.name?.lowercase(), plan.needsNote,
				if (plan.mode == Workflow.DelegationMode.FIRST) null else previousRun(id),
				if (plan.mode == Workflow.DelegationMode.RESUME) answersReady(id) else 0,
			),
			questionAvailability(facts),
		)
	}

	/** The projects of [viewer]'s organisation (every project without one) and the open work items they may see in each. */
	fun projects(viewer: Actor? = null): List<ProjectView> {
		val scope = viewer?.let { PermissionRules.filter(it, ScopeColumns.WORK_ITEMS) }
		val stmt = jdbc.sql(
			"""
			select p.id, p.external_ref, p.name,
			       (select count(*) from work_items w where w.project_id = p.id and w.stage not in ('done', 'cancelled')
			           and ${scope?.sql ?: "true"})
			  from projects p where cast(:org as text) is null or p.org_id = :org order by p.external_ref nulls last, p.name
			""".trimIndent(),
		).param("org", viewer?.orgId)
		scope?.params?.forEach { (k, v) -> stmt.param(k, v) }
		return stmt.query { rs, _ -> ProjectView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) }.list()
	}

	fun workflow(orgId: String = ORG): WorkflowSettings = WorkflowSettings(skippedStages(orgId).sortedBy { Workflow.STAGES.indexOf(it) })

	/** Changes the skipped stages of [actor]'s organisation. */
	@Transactional
	fun setWorkflow(body: WorkflowSettings, actor: String): WorkflowSettings {
		val skipped = body.skippedStages.map { it.trim() }.toSet()
		val bad = skipped - Workflow.SKIPPABLE
		if (bad.isNotEmpty()) throw BadRequestException("only ${Workflow.SKIPPABLE} can be skipped, not $bad")
		val org = facts.orgOf(actor)
		val before = skippedStages(org)
		jdbc.sql(
			"""
			update organizations
			   set settings = jsonb_set(settings, '{workflow,skipped_stages}', cast(:stages as jsonb), true)
			 where id = :org
			""".trimIndent(),
		).param("stages", json.writeValueAsString(skipped.sortedBy { Workflow.STAGES.indexOf(it) })).param("org", org).update()
		sessions.audit("person", actor, "workflow.skipped_stages", "org", org,
			mapOf("before" to before.sorted(), "after" to skipped.sorted()))
		return workflow(org)
	}

	// ---------------------------------------------------------------- delegated runs

	/** What a run reported that moves its work item (contract "단계"). */
	enum class RunStep { STARTED, SUBMITTED }

	/**
	 * Locks the work item, checks the version the caller saw and returns the facts its moves and
	 * its handoff depend on. For DelegationService, which writes in the same transaction.
	 */
	fun lockFacts(id: String, version: Long): Workflow.Facts = facts(lockEditable(id, version, checkSource = false))

	/** Writes one line of the work item's history, acted by [actor]. */
	fun record(id: String, action: String, details: Map<String, Any?>, actor: String) =
		history(id, action, details, actor)

	/**
	 * Moves a work item along with its delegated run. A run that started takes `ready` to
	 * `in_progress`: the run was accepted. A submitted result takes `in_progress` on to
	 * `result_review`, or through it to `done` when the organisation skips result review. A work
	 * item a person has moved elsewhere meanwhile stays where the person put it. Failed, cancelled
	 * and lost runs leave the stage alone; the caller adds `outcome_unknown` when the contract says so.
	 * The caller holds the run and work item locks in its transaction (RunService.ingest).
	 */
	fun followRun(id: String, runId: String, step: RunStep) {
		val row = load(id, forUpdate = true) ?: return
		if (row.sourceOfTruth != "product") return // a Git copy is changed only by an import
		var stage = row.stage
		if (stage == "ready") {
			systemMove(id, runId, stage, listOf("in_progress"))
			stage = "in_progress"
		}
		if (step == RunStep.SUBMITTED && stage == "in_progress") {
			val move = Workflow.move(facts(row).copy(stage = stage, hasLiveRun = false), "result_review")
			if (move.allowed) systemMove(id, runId, stage, move.path)
		}
	}

	/** A move made by the server for a run; written to the history with the run as the actor. */
	private fun systemMove(id: String, runId: String, from: String, path: List<String>) {
		val stage = path.last()
		val closing = stage in Workflow.CLOSED
		jdbc.sql(
			"""
			update work_items
			   set stage = :stage, version = version + 1, updated_at = now(),
			       wait_reasons = case when :closing then '{}'::text[] else wait_reasons end,
			       wait_note = case when :closing then null else wait_note end,
			       closed_at = case when :closing then now() else null end
			 where id = :id
			""".trimIndent(),
		).param("stage", stage).param("closing", closing).param("id", id).update()
		sessions.audit("system", runId, "work_item.stage_changed", "wrk", id, mapOf("from" to from, "to" to stage, "path" to path, "run_id" to runId))
	}

	/**
	 * The newest delegated run of the work item that a connector claimed and that has ended: the
	 * run a continuing run follows (contract "실행 권리 청구" 1-2, `resumed_from_run_id`).
	 */
	fun previousRun(id: String): PreviousRun? =
		jdbc.sql(
			"""
			select r.id, r.end_reason, r.device_id, d.scope::text, r.executor_session_id, r.workspace::text, r.account_id
			  from runs r join delegations d on d.id = r.delegation_id
			 where r.work_item_id = :id and r.state = 'ended' and r.claimed_at is not null
			 order by r.created_at desc, r.id desc limit 1
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			val scope = json.readValue(rs.getString(4), mapType)
			PreviousRun(
				// The master's run (MasterChecks.kt) has no repository.
				rs.getString(1), rs.getString(2), rs.getString(3), scope["repo_key"] as? String ?: "", scope["base_branch"] as String?,
				(scope["allowed_tools"] as? List<*>).orEmpty().map { it.toString() }, rs.getString(5),
				rs.getString(6)?.let { json.readValue(it, mapType) }, rs.getString(7),
			)
		}.optional().orElse(null)

	/** Whether a question can be left now (QuestionService.ask refuses with the same reasons). */
	private fun questionAvailability(facts: Workflow.Facts): ActionAvailability = when {
		facts.readOnlyReason != null -> ActionAvailability(false, "${facts.readOnlyReason}. 질문은 원본 저장소에서 다룬다")
		facts.stage in Workflow.CLOSED ->
			ActionAvailability(false, "${Workflow.label(facts.stage)}된 작업에는 질문을 남기지 않는다. 이어서 할 일이면 새 작업으로 만든다")
		else -> ActionAvailability(true, null)
	}

	/** Answered questions of the work item's runs that no continuing run carried yet (QuestionService). */
	private fun answersReady(id: String): Int =
		jdbc.sql(
			"select count(*) from questions where work_item_id = :id and state = 'answered' and run_id is not null and delivery_run_id is null",
		).param("id", id).query(Int::class.javaObjectType).single()

	/** Whether the work item's organisation skips result review (contract "단계": `workflow.skipped_stages`). */
	fun skipsResultReview(workItemId: String): Boolean = "result_review" in skippedStages(
		jdbc.sql("select org_id from work_items where id = :id").param("id", workItemId).query(String::class.java).single(),
	)

	/**
	 * A result held in result review only because of an open risk review request goes on to done
	 * once that request is decided, where the organisation skips result review (contract "반려하면
	 * 작업이 어떻게 되나" 4: skipping applies when no risk request is open). Written as the person's
	 * move, since their approval let it go. Returns whether it moved. The caller holds the lock.
	 */
	fun releaseHeldResult(id: String, actor: String): Boolean {
		val row = load(id, forUpdate = true) ?: return false
		if (row.stage != "result_review" || row.sourceOfTruth != "product" || "result_review" !in skippedStages(row.orgId)) return false
		val move = Workflow.move(facts(row), "done")
		if (!move.allowed) return false
		jdbc.sql(
			"""
			update work_items
			   set stage = 'done', version = version + 1, updated_at = now(), wait_reasons = '{}'::text[], wait_note = null,
			       closed_at = now()
			 where id = :id
			""".trimIndent(),
		).param("id", id).update()
		history(id, "work_item.stage_changed", mapOf("from" to row.stage, "to" to "done", "path" to move.path,
			"note" to "위험 목록 검토를 승인해, 결과 검토를 쓰지 않는 조직의 단계 생략을 적용했다"), actor)
		return true
	}

	/** Takes off server-set wait reasons a person has dealt with by handing the work item on. */
	fun clearWaits(id: String, reasons: Collection<String>) {
		jdbc.sql(
			"""
			update work_items
			   set wait_reasons = array(select r from unnest(wait_reasons) r where r <> all(array[:reasons]::text[])),
			       version = version + 1, updated_at = now()
			 where id = :id and wait_reasons && array[:reasons]::text[]
			""".trimIndent(),
		).param("reasons", reasons.toList()).param("id", id).update()
	}

	// ---------------------------------------------------------------- internals

	internal data class Row(
		val id: String,
		val stage: String,
		val waitReasons: List<String>,
		val version: Long,
		val responsibleUserId: String,
		val executorKind: String?,
		val executorId: String?,
		val acceptance: String?,
		val sourceRef: String?,
		val sourceOfTruth: String,
		val gitRef: Map<String, Any?>?,
		val hasLiveRun: Boolean,
		val lastRunEnd: String?,
		/** The newest run's result has an open risk review request (ReviewService). */
		val resultHeld: Boolean,
		val orgId: String,
	)

	private fun load(id: String, forUpdate: Boolean = false): Row? =
		jdbc.sql(
			"""
			select w.id, w.stage, w.wait_reasons, w.version, w.responsible_user_id, w.executor_kind, w.executor_id,
			       w.acceptance, w.source_ref, w.source_of_truth, w.git_ref::text,
			       exists (select 1 from runs r where r.work_item_id = w.id and r.state <> 'ended') as live_run,
			       (select r.end_reason from runs r
			         where r.work_item_id = w.id and r.delegation_id is not null and r.state = 'ended' and r.claimed_at is not null
			         order by r.created_at desc, r.id desc limit 1) as last_run_end,
			       exists (select 1 from review_items v
			                where v.work_item_id = w.id and v.state = 'open' and v.reason <> 'sample'
			                  and v.run_id = (select r.id from runs r where r.work_item_id = w.id
			                                   order by r.created_at desc, r.id desc limit 1)) as result_held,
			       w.org_id
			  from work_items w where w.id = :id
			""".trimIndent() + if (forUpdate) " for update of w" else "",
		).param("id", id).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			Row(
				rs.getString(1), rs.getString(2), (rs.getArray(3).array as Array<String>).toList(), rs.getLong(4),
				rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
				rs.getString(11)?.let { json.readValue(it, mapType) }, rs.getBoolean(12), rs.getString(13), rs.getBoolean(14),
				rs.getString(15),
			)
		}.optional().orElse(null)

	/**
	 * Locks the work item and checks the version. Unless [checkSource] is false the copy must be
	 * editable here; a transition checks that itself, as the reason of every move.
	 */
	private fun lockEditable(id: String, version: Long, checkSource: Boolean = true): Row {
		val row = load(id, forUpdate = true) ?: throw NotFoundException("no work item $id")
		if (row.version != version) {
			throw ConflictException("the work item changed (version ${row.version}, you sent $version); reload it")
		}
		if (checkSource) readOnlyReason(row)?.let { throw ConflictException(it) }
		return row
	}

	private fun facts(row: Row) =
		Workflow.Facts(row.stage, row.executorKind, row.hasLiveRun, skippedStages(row.orgId), readOnlyReason(row), row.lastRunEnd, row.resultHeld)

	private fun readOnlyReason(row: Row): String? =
		if (row.sourceOfTruth == "git") {
			"정본이 Git 파일(${row.gitRef?.get("path") ?: "?"})이다. 원본에서 고치고 다시 가져온다"
		} else {
			null
		}

	private fun skippedStages(orgId: String): Set<String> {
		val raw = jdbc.sql("select settings -> 'workflow' -> 'skipped_stages' from organizations where id = :org")
			.param("org", orgId).query(String::class.java).optional().orElse(null) ?: return emptySet()
		return json.readValue(raw, object : TypeReference<List<String>>() {}).filter { it in Workflow.SKIPPABLE }.toSet()
	}

	private fun requireProject(id: String) {
		val found = jdbc.sql("select count(*) from projects where id = :id").param("id", id)
			.query(Long::class.javaObjectType).single() > 0
		if (!found) throw BadRequestException("no project $id")
	}

	private fun history(id: String, action: String, details: Map<String, Any?>, actor: String) =
		sessions.audit("person", actor, action, "wrk", id, details)

	/** A sharing scope from input; null when absent. */
	private fun visibility(raw: String?): Visibility? {
		val t = raw?.trim()?.ifEmpty { null } ?: return null
		return Visibility.parse(t) ?: throw BadRequestException("visibility must be one of ${Visibility.entries.map { it.wire }}")
	}

	private fun title(raw: String): String {
		val t = raw.trim()
		if (t.isEmpty() || t.length > TITLE_MAX) throw BadRequestException("title must be 1-$TITLE_MAX characters")
		return t
	}

	private fun text(raw: String?, field: String): String? {
		val t = raw?.trim()?.ifEmpty { null } ?: return null
		if (t.length > TEXT_MAX) throw BadRequestException("$field must be at most $TEXT_MAX characters")
		return t
	}

	private fun date(raw: String?, field: String): LocalDate? {
		val t = raw?.trim()?.ifEmpty { null } ?: return null
		return try {
			LocalDate.parse(t)
		} catch (e: DateTimeParseException) {
			throw BadRequestException("$field must be a date like 2026-10-13")
		}
	}

	private val SELECT_VIEW = """
		select w.id, w.title, w.stage, w.wait_reasons, w.generation, w.created_at, w.version, w.external_ref,
		       p.id as project_id, p.external_ref as project_ref, p.name as project_name, w.source_of_truth,
		       w.executor_kind, w.next_action, w.wait_note, w.opened_on, w.due_on, w.closed_at, w.updated_at, w.visibility
		  from work_items w left join projects p on p.id = w.project_id
	""".trimIndent()

	private fun view(rs: ResultSet): WorkItemView {
		@Suppress("UNCHECKED_CAST")
		val reasons = (rs.getArray("wait_reasons").array as Array<String>).toList()
		val project = rs.getString("project_id")?.let { ProjectRef(it, rs.getString("project_ref"), rs.getString("project_name")) }
		return WorkItemView(
			rs.getString("id"), rs.getString("title"), rs.getString("stage"), reasons, rs.getLong("generation"),
			rs.getObject("created_at", OffsetDateTime::class.java), rs.getLong("version"), rs.getString("external_ref"),
			project, rs.getString("source_of_truth"), rs.getString("executor_kind"), rs.getString("next_action"),
			rs.getString("wait_note"), rs.getObject("opened_on", LocalDate::class.java),
			rs.getObject("due_on", LocalDate::class.java), rs.getObject("closed_at", OffsetDateTime::class.java),
			rs.getObject("updated_at", OffsetDateTime::class.java), rs.getString("visibility"),
		)
	}
}
