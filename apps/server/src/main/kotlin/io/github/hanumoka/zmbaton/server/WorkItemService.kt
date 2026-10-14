package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
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
) {
	companion object {
		const val ORG = "org_owner"
		const val TITLE_MAX = 300
		const val TEXT_MAX = 4000
		const val NOTE_MAX = 1000
		const val HISTORY_MAX = 50
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	// ---------------------------------------------------------------- create and edit

	@Transactional
	fun create(body: CreateWorkItem): WorkItemCreated {
		val owner = body.responsibleUserId.trim()
		if (owner.isEmpty()) throw BadRequestException("responsible_user_id is required")
		val title = title(body.title)
		val stage = body.initialStage?.trim() ?: "ready"
		if (stage !in setOf("draft", "ready")) throw BadRequestException("initial_stage must be draft or ready")
		val executorKind = body.executorKind?.trim()
		if (executorKind != null && executorKind != "human") throw BadRequestException("executor_kind must be human")
		val projectId = body.projectId?.trim()?.ifEmpty { null }?.also(::requireProject)
		val id = Ids.workItem()
		jdbc.sql(
			"""
			insert into work_items (id, title, stage, responsible_user_id, executor_kind, executor_id, project_id,
			                        acceptance, next_action, due_on, opened_on)
			values (:id, :title, :stage, :owner, :ekind, :eid, :project, :acceptance, :next, :due, current_date)
			""".trimIndent(),
		).param("id", id).param("title", title).param("stage", stage).param("owner", owner)
			.param("ekind", executorKind).param("eid", if (executorKind == "human") owner else null)
			.param("project", projectId).param("acceptance", text(body.acceptance, "acceptance"))
			.param("next", text(body.nextAction, "next_action")).param("due", date(body.dueOn, "due_on"))
			.update()
		history(id, "work_item.created", mapOf("stage" to stage, "title" to title))
		return WorkItemCreated(id, stage)
	}

	/**
	 * Changes the fields present in [patch]. A present key with null clears the field; `title`
	 * cannot be cleared. `version` must be the version the caller saw.
	 */
	@Transactional
	fun update(id: String, patch: Map<String, Any?>): WorkItemDetail {
		val version = (patch["version"] as? Number)?.toLong() ?: throw BadRequestException("version is required")
		val known = setOf("version", "title", "acceptance", "next_action", "due_on", "project_id")
		val unknown = patch.keys - known
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown")
		val row = lockEditable(id, version)
		val changes = linkedMapOf<String, Any?>()
		if ("title" in patch) changes["title"] = title(patch["title"] as? String ?: throw BadRequestException("title must be text"))
		if ("acceptance" in patch) changes["acceptance"] = text(patch["acceptance"] as String?, "acceptance")
		if ("next_action" in patch) changes["next_action"] = text(patch["next_action"] as String?, "next_action")
		if ("due_on" in patch) changes["due_on"] = date(patch["due_on"] as String?, "due_on")
		if ("project_id" in patch) changes["project_id"] = (patch["project_id"] as String?)?.trim()?.ifEmpty { null }?.also(::requireProject)
		if (changes.isEmpty()) throw BadRequestException("nothing to change")
		// Column names come from the fixed set above, never from input.
		val sets = changes.keys.joinToString(", ") { "$it = :$it" }
		val stmt = jdbc.sql("update work_items set $sets, version = version + 1, updated_at = now() where id = :id")
			.param("id", row.id)
		changes.forEach { (k, v) -> stmt.param(k, v) }
		stmt.update()
		history(id, "work_item.updated", changes.mapValues { it.value?.toString() })
		return detail(id)
	}

	// ---------------------------------------------------------------- stages and wait reasons

	@Transactional
	fun transition(id: String, body: TransitionIn): WorkItemDetail {
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
			mapOf("from" to row.stage, "to" to move.finalStage, "path" to move.path, "note" to note),
		)
		return detail(id)
	}

	@Transactional
	fun addWait(id: String, body: WaitReasonIn): WorkItemDetail {
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
		history(id, "work_item.wait_added", mapOf("reason" to reason, "note" to note))
		return detail(id)
	}

	@Transactional
	fun removeWait(id: String, reason: String, version: Long): WorkItemDetail {
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
		history(id, "work_item.wait_removed", mapOf("reason" to reason))
		return detail(id)
	}

	// ---------------------------------------------------------------- reads

	/**
	 * Work items, newest first. [open] keeps only those not done or cancelled; [query] matches
	 * the title, external number and next action.
	 */
	fun list(limit: Int, projectId: String?, open: Boolean, query: String?): List<WorkItemView> {
		val where = mutableListOf("true")
		if (projectId != null) where += "w.project_id = :project"
		if (open) where += "w.stage not in ('done', 'cancelled')"
		val q = query?.trim()?.take(100)?.ifEmpty { null }
		if (q != null) where += "(w.title ilike :q or w.external_ref ilike :q or w.next_action ilike :q)"
		val stmt = jdbc.sql(
			"$SELECT_VIEW where ${where.joinToString(" and ")} order by w.created_at desc, w.id desc limit :limit",
		).param("limit", limit)
		if (projectId != null) stmt.param("project", projectId)
		if (q != null) stmt.param("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
		return stmt.query { rs, _ -> view(rs) }.list()
	}

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
			),
		)
	}

	fun projects(): List<ProjectView> =
		jdbc.sql(
			"""
			select p.id, p.external_ref, p.name,
			       (select count(*) from work_items w where w.project_id = p.id and w.stage not in ('done', 'cancelled'))
			  from projects p order by p.external_ref nulls last, p.name
			""".trimIndent(),
		).query { rs, _ -> ProjectView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)) }.list()

	fun workflow(): WorkflowSettings = WorkflowSettings(skippedStages().sortedBy { Workflow.STAGES.indexOf(it) })

	@Transactional
	fun setWorkflow(body: WorkflowSettings): WorkflowSettings {
		val skipped = body.skippedStages.map { it.trim() }.toSet()
		val bad = skipped - Workflow.SKIPPABLE
		if (bad.isNotEmpty()) throw BadRequestException("only ${Workflow.SKIPPABLE} can be skipped, not $bad")
		val before = skippedStages()
		jdbc.sql(
			"""
			update organizations
			   set settings = jsonb_set(settings, '{workflow,skipped_stages}', cast(:stages as jsonb), true)
			 where id = :org
			""".trimIndent(),
		).param("stages", json.writeValueAsString(skipped.sortedBy { Workflow.STAGES.indexOf(it) })).param("org", ORG).update()
		sessions.audit("person", SessionService.OWNER, "workflow.skipped_stages", "org", ORG,
			mapOf("before" to before.sorted(), "after" to skipped.sorted()))
		return workflow()
	}

	// ---------------------------------------------------------------- delegated runs

	/** What a run reported that moves its work item (contract "단계"). */
	enum class RunStep { STARTED, SUBMITTED }

	/**
	 * Locks the work item, checks the version the caller saw and returns the facts its moves and
	 * its handoff depend on. For DelegationService, which writes in the same transaction.
	 */
	fun lockFacts(id: String, version: Long): Workflow.Facts = facts(lockEditable(id, version, checkSource = false))

	/** Writes one line of the work item's history, acted by the owner. */
	fun record(id: String, action: String, details: Map<String, Any?>) = history(id, action, details)

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
				rs.getString(1), rs.getString(2), rs.getString(3), scope["repo_key"] as String, scope["base_branch"] as String?,
				(scope["allowed_tools"] as? List<*>).orEmpty().map { it.toString() }, rs.getString(5),
				rs.getString(6)?.let { json.readValue(it, mapType) }, rs.getString(7),
			)
		}.optional().orElse(null)

	/** Whether the organisation skips result review (contract "단계": `workflow.skipped_stages`). */
	fun skipsResultReview(): Boolean = "result_review" in skippedStages()

	/**
	 * A result held in result review only because of an open risk review request goes on to done
	 * once that request is decided, where the organisation skips result review (contract "반려하면
	 * 작업이 어떻게 되나" 4: skipping applies when no risk request is open). Written as the person's
	 * move, since their approval let it go. Returns whether it moved. The caller holds the lock.
	 */
	fun releaseHeldResult(id: String): Boolean {
		val row = load(id, forUpdate = true) ?: return false
		if (row.stage != "result_review" || row.sourceOfTruth != "product" || !skipsResultReview()) return false
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
			"note" to "위험 목록 검토를 승인해, 결과 검토를 쓰지 않는 조직의 단계 생략을 적용했다"))
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
			                                   order by r.created_at desc, r.id desc limit 1)) as result_held
			  from work_items w where w.id = :id
			""".trimIndent() + if (forUpdate) " for update of w" else "",
		).param("id", id).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			Row(
				rs.getString(1), rs.getString(2), (rs.getArray(3).array as Array<String>).toList(), rs.getLong(4),
				rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
				rs.getString(11)?.let { json.readValue(it, mapType) }, rs.getBoolean(12), rs.getString(13), rs.getBoolean(14),
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
		Workflow.Facts(row.stage, row.executorKind, row.hasLiveRun, skippedStages(), readOnlyReason(row), row.lastRunEnd, row.resultHeld)

	private fun readOnlyReason(row: Row): String? =
		if (row.sourceOfTruth == "git") {
			"정본이 Git 파일(${row.gitRef?.get("path") ?: "?"})이다. 원본에서 고치고 다시 가져온다"
		} else {
			null
		}

	private fun skippedStages(): Set<String> {
		val raw = jdbc.sql("select settings -> 'workflow' -> 'skipped_stages' from organizations where id = :org")
			.param("org", ORG).query(String::class.java).optional().orElse(null) ?: return emptySet()
		return json.readValue(raw, object : TypeReference<List<String>>() {}).filter { it in Workflow.SKIPPABLE }.toSet()
	}

	private fun requireProject(id: String) {
		val found = jdbc.sql("select count(*) from projects where id = :id").param("id", id)
			.query(Long::class.javaObjectType).single() > 0
		if (!found) throw BadRequestException("no project $id")
	}

	private fun history(id: String, action: String, details: Map<String, Any?>) =
		sessions.audit("person", SessionService.OWNER, action, "wrk", id, details)

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
		       w.executor_kind, w.next_action, w.wait_note, w.opened_on, w.due_on, w.closed_at, w.updated_at
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
			rs.getObject("updated_at", OffsetDateTime::class.java),
		)
	}
}
