package io.github.hanumoka.zmbaton.server

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
	)

	private fun load(id: String, forUpdate: Boolean = false): Row? =
		jdbc.sql(
			"""
			select w.id, w.stage, w.wait_reasons, w.version, w.responsible_user_id, w.executor_kind, w.executor_id,
			       w.acceptance, w.source_ref, w.source_of_truth, w.git_ref::text,
			       exists (select 1 from runs r where r.work_item_id = w.id and r.state <> 'ended') as live_run
			  from work_items w where w.id = :id
			""".trimIndent() + if (forUpdate) " for update of w" else "",
		).param("id", id).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			Row(
				rs.getString(1), rs.getString(2), (rs.getArray(3).array as Array<String>).toList(), rs.getLong(4),
				rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10),
				rs.getString(11)?.let { json.readValue(it, mapType) }, rs.getBoolean(12),
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

	private fun facts(row: Row) = Workflow.Facts(row.stage, row.executorKind, row.hasLiveRun, skippedStages(), readOnlyReason(row))

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
