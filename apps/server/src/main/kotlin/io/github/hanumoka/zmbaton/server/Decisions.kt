package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.Permissions
import io.github.hanumoka.zmbaton.server.permission.ScopeColumns
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime

// Decision records (FN-0604), read side. Two kinds share one table and one shape here:
// - copies of zm-docs decisions, whose Git files stay their source of truth (G20.1, FN-0605
//   "1단계는 zm-docs 결정 기록을 Git에서 고친다"); only an import (ZmDocsImport) writes them;
// - decisions written here (`source_of_truth = product`, numbered `DCS-0001`), which
//   DecisionWriteService proposes, edits, accepts, rejects and replaces.

data class DecisionRef(
	val id: String,
	@JsonProperty("external_ref") val externalRef: String?,
	val question: String,
	/** The number people call it: `DEC-0106` for a Git copy, `DCS-0001` for one made here. */
	val ref: String? = externalRef,
)

data class DecisionView(
	val id: String,
	@JsonProperty("external_ref") val externalRef: String?,
	val question: String,
	val status: String,
	@JsonProperty("decided_on") val decidedOn: LocalDate?,
	val projects: List<ProjectRef>,
	/** The start of the "결정" section, for a list. */
	@JsonProperty("choice_preview") val choicePreview: String?,
	val supersedes: DecisionRef?,
	@JsonProperty("superseded_by") val supersededBy: DecisionRef?,
	@JsonProperty("updated_at") val updatedAt: OffsetDateTime,
	// Added with decisions written here; the fields above are what the read-only screen used.
	val number: Int? = null,
	val ref: String? = externalRef,
	@JsonProperty("source_of_truth") val sourceOfTruth: String = "git",
	val version: Long = 0,
	/** Added with permissions: the sharing scope (G06.1) and who owns the record. */
	val visibility: String = "org",
	@JsonProperty("owner_user_id") val ownerUserId: String? = null,
)

data class DecisionSection(val heading: String, val body: String)

data class DecisionOption(val key: String, val title: String, val body: String? = null)

/** Decision roles (DACI). In stage 1 the approver is always [ApprovalPolicy.approverFor]. */
data class DecisionRoles(
	val driver: String? = null,
	val approver: String? = null,
	val contributors: List<String> = emptyList(),
	val informed: List<String> = emptyList(),
)

/** A work item a decision made here is about, as much as a link to it needs. */
data class LinkedWork(
	val id: String,
	@JsonProperty("external_ref") val externalRef: String?,
	val title: String,
	val stage: String,
	@JsonProperty("wait_reasons") val waitReasons: List<String>,
)

data class DecisionActionView(val name: String, val allowed: Boolean, val reason: String?)

data class DecisionDetail(
	val decision: DecisionView,
	val choice: String?,
	val sections: List<DecisionSection>,
	@JsonProperty("source_of_truth") val sourceOfTruth: String,
	@JsonProperty("git_ref") val gitRef: Map<String, Any?>?,
	// Decisions written here (empty for a Git copy, whose text is in `sections`).
	val context: String? = null,
	val options: List<DecisionOption> = emptyList(),
	@JsonProperty("chosen_option") val chosenOption: String? = null,
	val rationale: String? = null,
	@JsonProperty("review_conditions") val reviewConditions: String? = null,
	val roles: DecisionRoles = DecisionRoles(),
	/** Who may accept or reject it now. */
	val approver: String = Requester.STAGE1_PERSON,
	@JsonProperty("work_items") val workItems: List<LinkedWork> = emptyList(),
	val approvals: List<ApprovalView> = emptyList(),
	/** Non-null when nothing here may change it, e.g. its source of truth is a Git file. */
	@JsonProperty("read_only_reason") val readOnlyReason: String? = null,
	/** Edit, accept, reject and supersede, each allowed or not with the reason. */
	val actions: List<DecisionActionView> = emptyList(),
	val history: List<HistoryEntry> = emptyList(),
)

@Service
class DecisionService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val approvals: ApprovalService,
	private val access: PermissionFacts,
) {

	companion object {
		const val PREVIEW_MAX = 200
		const val HISTORY_MAX = 50
		val STATUSES = setOf("proposed", "accepted", "rejected", "superseded")

		/** `DCS-0001`: the name of a decision made here. */
		fun refOf(number: Int): String = "DCS-%04d".format(number)

		/** The same in SQL, for searching by the name. */
		const val REF_SQL = "('DCS-' || case when d.number >= 10000 then d.number::text else lpad(d.number::text, 4, '0') end)"
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val sectionsType = object : TypeReference<List<DecisionSection>>() {}
	private val optionsType = object : TypeReference<List<DecisionOption>>() {}

	/**
	 * Proposals first (so a long list never cuts off what is still to decide), then the newest
	 * decision. [query] matches the number, the question and the decision. With [viewer], only those
	 * its scope lets them see.
	 */
	fun list(limit: Int, projectId: String?, status: String?, query: String?, viewer: Actor? = null): List<DecisionView> {
		if (status != null && status !in STATUSES) throw BadRequestException("status must be one of $STATUSES")
		val where = mutableListOf("true")
		val scope = viewer?.let { PermissionRules.filter(it, ScopeColumns.DECISIONS) }
		if (scope != null) where += scope.sql
		if (projectId != null) where += "exists (select 1 from decision_projects dp where dp.decision_id = d.id and dp.project_id = :project)"
		if (status != null) where += "d.status = :status"
		val q = query?.trim()?.take(100)?.ifEmpty { null }
		if (q != null) where += "(d.external_ref ilike :q or $REF_SQL ilike :q or d.question ilike :q or d.choice ilike :q)"
		val stmt = jdbc.sql("$SELECT_VIEW where ${where.joinToString(" and ")} $ORDER limit :limit").param("limit", limit)
		if (projectId != null) stmt.param("project", projectId)
		if (status != null) stmt.param("status", status)
		if (q != null) stmt.param("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
		scope?.params?.forEach { (k, v) -> stmt.param(k, v) }
		return withProjects(stmt.query { rs, _ -> view(rs) }.list())
	}

	/** One decision with everything its screen shows; [actor] is the person asking, for what they may do. */
	fun detail(id: String, actor: String = Requester.STAGE1_PERSON): DecisionDetail {
		val view = withProjects(listOf(load(id) ?: throw NotFoundException("no decision $id"))).single()
		return jdbc.sql(
			"""
			select choice, sections::text, source_of_truth, git_ref::text, context, options::text, chosen_option,
			       rationale, review_conditions, roles::text, org_id
			  from decisions where id = :id
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			val gitRef = rs.getString(4)?.let { json.readValue(it, mapType) }
			val options = json.readValue(rs.getString(6), optionsType)
			val facts = facts(view, gitRef, rs.getString(1), rs.getString(7), rs.getString(11))
			val product = view.sourceOfTruth == "product"
			DecisionDetail(
				view, rs.getString(1), json.readValue(rs.getString(2), sectionsType), rs.getString(3), gitRef,
				rs.getString(5), options, rs.getString(7), rs.getString(8), rs.getString(9),
				json.readValue(rs.getString(10), DecisionRoles::class.java), facts.approver,
				if (product) linkedWork(id) else emptyList(),
				approvals.list("decision", id),
				if (product) null else DecisionRules.gitReason(gitRef?.get("path") as String?),
				DecisionRules.actions(facts, actor).map { DecisionActionView(it.name, it.allowed, it.reason) },
				if (product) history(id) else emptyList(),
			)
		}.single()
	}

	/** The decisions linked to a work item, as a list shows them; with [viewer], those they may see. */
	fun forWorkItem(workItemId: String, viewer: Actor? = null): List<DecisionView> {
		val exists = jdbc.sql("select count(*) from work_items where id = :id").param("id", workItemId)
			.query(Long::class.javaObjectType).single() > 0
		if (!exists) throw NotFoundException("no work item $workItemId")
		val scope = viewer?.let { PermissionRules.filter(it, ScopeColumns.DECISIONS) }
		val stmt = jdbc.sql(
			"$SELECT_VIEW where d.id in (select decision_id from decision_work_items where work_item_id = :id) and ${scope?.sql ?: "true"} $ORDER",
		).param("id", workItemId)
		scope?.params?.forEach { (k, v) -> stmt.param(k, v) }
		return withProjects(stmt.query { rs, _ -> view(rs) }.list())
	}

	/** The newest decisions recorded in one repository, for a session's status card. */
	fun recentInRepository(repoKey: String, limit: Int): List<DecisionView> =
		jdbc.sql("$SELECT_VIEW where d.git_ref ->> 'repo_key' = :repo order by d.decided_on desc nulls last, d.external_ref desc limit :limit")
			.param("repo", repoKey).param("limit", limit).query { rs, _ -> view(rs) }.list()

	internal fun load(id: String): DecisionView? =
		jdbc.sql("$SELECT_VIEW where d.id = :id").param("id", id).query { rs, _ -> view(rs) }.optional().orElse(null)

	/** The facts [DecisionRules] needs, from a loaded decision. */
	internal fun facts(view: DecisionView, gitRef: Map<String, Any?>?, choice: String?, chosenOption: String?, orgId: String) =
		DecisionRules.Facts(
			view.status, view.sourceOfTruth, gitRef?.get("path") as String?,
			!choice.isNullOrBlank() || chosenOption != null,
			access.approverFor(orgId, "decision"),
		)

	private val ORDER = "order by (d.status = 'proposed') desc, d.decided_on desc nulls last, d.external_ref desc nulls last, d.number desc nulls last"

	private val SELECT_VIEW = """
		select d.id, d.external_ref, d.question, d.status, d.decided_on, d.updated_at, d.number, d.source_of_truth, d.version,
		       d.visibility, d.owner_user_id,
		       coalesce(d.choice, (select o ->> 'title' from jsonb_array_elements(d.options) o where o ->> 'key' = d.chosen_option limit 1)) as choice,
		       s.id as s_id, s.external_ref as s_ref, s.number as s_number, s.question as s_question,
		       b.id as b_id, b.external_ref as b_ref, b.number as b_number, b.question as b_question
		  from decisions d
		  left join decisions s on s.id = d.supersedes_id
		  left join decisions b on b.id = d.superseded_by_id
	""".trimIndent()

	private fun view(rs: ResultSet): DecisionView {
		val number = rs.getObject("number") as Int?
		val externalRef = rs.getString("external_ref")
		return DecisionView(
			rs.getString("id"), externalRef, rs.getString("question"), rs.getString("status"),
			rs.getObject("decided_on", LocalDate::class.java), emptyList(),
			rs.getString("choice")?.let { preview(it) },
			ref(rs, "s"), ref(rs, "b"),
			rs.getObject("updated_at", OffsetDateTime::class.java),
			number, externalRef ?: number?.let(::refOf), rs.getString("source_of_truth"), rs.getLong("version"),
			rs.getString("visibility"), rs.getString("owner_user_id"),
		)
	}

	private fun ref(rs: ResultSet, p: String): DecisionRef? {
		val id = rs.getString("${p}_id") ?: return null
		val externalRef = rs.getString("${p}_ref")
		val number = rs.getObject("${p}_number") as Int?
		return DecisionRef(id, externalRef, rs.getString("${p}_question"), externalRef ?: number?.let(::refOf))
	}

	private fun withProjects(views: List<DecisionView>): List<DecisionView> {
		if (views.isEmpty()) return views
		val byDecision = jdbc.sql(
			"""
			select dp.decision_id, p.id, p.external_ref, p.name from decision_projects dp join projects p on p.id = dp.project_id
			 where dp.decision_id in (:ids) order by p.external_ref nulls last, p.name
			""".trimIndent(),
		).param("ids", views.map { it.id }).query { rs, _ ->
			rs.getString(1) to ProjectRef(rs.getString(2), rs.getString(3), rs.getString(4))
		}.list().groupBy({ it.first }, { it.second })
		return views.map { it.copy(projects = byDecision[it.id].orEmpty()) }
	}

	private fun linkedWork(id: String): List<LinkedWork> =
		jdbc.sql(
			"""
			select w.id, w.external_ref, w.title, w.stage, w.wait_reasons from decision_work_items dw join work_items w on w.id = dw.work_item_id
			 where dw.decision_id = :id order by w.external_ref nulls last, w.created_at
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			LinkedWork(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), (rs.getArray(5).array as Array<String>).toList())
		}.list()

	private fun history(id: String): List<HistoryEntry> =
		jdbc.sql(
			"""
			select action, actor_kind, actor_id, details::text, at from audit_log
			 where target_kind = 'dcs' and target_id = :id order by at desc, id desc limit :limit
			""".trimIndent(),
		).param("id", id).param("limit", HISTORY_MAX).query { rs, _ ->
			HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), json.readValue(rs.getString(4), mapType),
				rs.getObject(5, OffsetDateTime::class.java))
		}.list()

	/** The first paragraph, flattened to one line and cut to [PREVIEW_MAX] characters. */
	private fun preview(choice: String): String? {
		val first = choice.trim().split(Regex("\\n\\s*\\n")).firstOrNull()?.replace(Regex("\\s+"), " ")?.trim() ?: return null
		if (first.isEmpty()) return null
		return if (first.length > PREVIEW_MAX) first.take(PREVIEW_MAX - 1) + "…" else first
	}
}

@RestController
@RequestMapping("/api")
class DecisionController(private val decisions: DecisionService, private val permissions: Permissions) {

	@GetMapping("/decisions")
	fun list(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(defaultValue = "50") limit: Int,
		@RequestParam(name = "project_id", required = false) projectId: String?,
		@RequestParam(required = false) status: String?,
		@RequestParam(required = false) q: String?,
	): List<DecisionView> {
		val me = permissions.person(authorization, "list decisions", Act.READ)
		return decisions.list(limit.coerceIn(1, 500), projectId?.trim()?.ifEmpty { null }, status?.trim()?.ifEmpty { null }, q, me)
	}

	@GetMapping("/decisions/{id}")
	fun detail(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): DecisionDetail {
		val me = permissions.person(authorization, "read a decision")
		permissions.see(me, permissions.decision(id))
		return decisions.detail(id, me.person!!)
	}

	/** The decisions that name this work item among the work they are about. */
	@GetMapping("/work-items/{id}/decisions")
	fun forWorkItem(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): List<DecisionView> {
		val me = permissions.person(authorization, "list a work item's decisions")
		permissions.see(me, permissions.workItem(id))
		return decisions.forWorkItem(id, me)
	}
}
