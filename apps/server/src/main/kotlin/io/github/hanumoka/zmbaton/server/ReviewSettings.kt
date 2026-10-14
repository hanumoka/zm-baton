package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.Permissions
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// The rules that pick what people must see (FN-0411): the risk path list, the sample rate and the
// daily cap, and the risk path candidates people put forward when they rejected a sample. The
// organisation's list (`organizations.settings.risk_paths`, else the starting list in RiskPaths)
// applies to every project that has not set its own (`projects.settings.risk_paths`, data model
// "projects"). Only a person changes them, and every change goes to the audit log (DEC-0105 4);
// AI cannot take a path off the list.

data class RiskScope(
	val patterns: List<String>,
	/** True when the organisation or project set its own list; false when it uses the one above it. */
	val customized: Boolean,
)

data class ProjectRiskScope(
	val project: ProjectRef,
	val patterns: List<String>,
	/** True while the project uses the organisation's list. */
	val inherited: Boolean,
	@JsonProperty("open_proposals") val openProposals: Int,
)

data class RiskProposal(
	@JsonProperty("review_id") val reviewId: String,
	/** Null: the work item has no project, so the pattern goes to the organisation's list. */
	@JsonProperty("project_id") val projectId: String?,
	val pattern: String,
	@JsonProperty("work_item_id") val workItemId: String?,
	@JsonProperty("work_title") val workTitle: String?,
	/** Why the person rejected the sample. */
	val note: String?,
	@JsonProperty("proposed_at") val proposedAt: OffsetDateTime,
)

data class ReviewSettingsView(
	val org: RiskScope,
	@JsonProperty("default_patterns") val defaultPatterns: List<String>,
	@JsonProperty("sample_rate") val sampleRate: Double,
	@JsonProperty("sample_daily_cap") val sampleDailyCap: Int,
	@JsonProperty("promote_after_hours") val promoteAfterHours: Int,
	val projects: List<ProjectRiskScope>,
	val proposals: List<RiskProposal>,
)

/** Absent fields stay as they are. */
data class OrgReviewSettingsIn(
	@JsonProperty("risk_paths") val riskPaths: List<String>? = null,
	@JsonProperty("sample_rate") val sampleRate: Double? = null,
	@JsonProperty("sample_daily_cap") val sampleDailyCap: Int? = null,
)

/** `patterns` null: the project goes back to the organisation's list. */
data class ProjectRiskPathsIn(val patterns: List<String>?)

data class ProposalIn(val action: String)

/** The risk path list that applies to one result, and where it came from. */
data class EffectiveRiskPaths(val patterns: List<String>, val source: String)

@Service
class ReviewSettingsService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val sessions: SessionService,
	private val access: PermissionFacts,
) {
	companion object {
		const val DEFAULT_PROMOTE_HOURS = 24
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	private fun orgSettings(orgId: String): Map<String, Any?> =
		jdbc.sql("select settings::text from organizations where id = :org").param("org", orgId)
			.query(String::class.java).optional().orElse(null)?.let { json.readValue(it, mapType) } ?: emptyMap()

	private fun strings(v: Any?): List<String>? = (v as? List<*>)?.mapNotNull { it as? String }

	/** The organisation's own list, or null while it uses the starting list. */
	private fun orgOwn(settings: Map<String, Any?>): List<String>? = strings(settings["risk_paths"])

	fun orgPatterns(orgId: String): List<String> = orgOwn(orgSettings(orgId)) ?: RiskPaths.ORG_DEFAULT

	// No default arguments on these: Kotlin's default-argument helper would run on the Spring proxy.
	fun sampleRate(orgId: String): Double = sampleRateOf(orgSettings(orgId))

	fun sampleDailyCap(orgId: String): Int = sampleDailyCapOf(orgSettings(orgId))

	fun promoteAfterHours(orgId: String): Int = promoteAfterHoursOf(orgSettings(orgId))

	private fun sampleRateOf(settings: Map<String, Any?>): Double =
		(settings["sample_rate"] as? Number)?.toDouble()?.takeIf { it in 0.0..1.0 } ?: Sampler.DEFAULT_RATE

	private fun sampleDailyCapOf(settings: Map<String, Any?>): Int =
		(settings["sample_daily_cap"] as? Number)?.toInt()?.takeIf { it >= 0 } ?: Sampler.DEFAULT_DAILY_CAP

	private fun promoteAfterHoursOf(settings: Map<String, Any?>): Int =
		(settings["review_promote_after_hours"] as? Number)?.toInt()?.takeIf { it >= 1 } ?: DEFAULT_PROMOTE_HOURS

	/**
	 * A project's own entries, `[{"repo": null | repo_key, "patterns": [...]}]`; null while it uses
	 * the organisation's list. An entry with no repository applies to all of the project's repositories.
	 */
	private fun projectEntries(projectId: String): List<Map<*, *>>? =
		jdbc.sql("select (settings -> 'risk_paths')::text from projects where id = :id").param("id", projectId)
			.query(String::class.java).optional().orElse(null)
			?.let { json.readValue(it, object : TypeReference<List<Map<String, Any?>>>() {}) }

	private fun projectPatterns(entries: List<Map<*, *>>, repoKey: String?): List<String> =
		entries.filter { it["repo"] == null || it["repo"] == repoKey }.flatMap { strings(it["patterns"]).orEmpty() }.distinct()

	/** The list that applies to a result of [projectId]'s work in [repoKey], in [orgId]. */
	fun effective(projectId: String?, repoKey: String?, orgId: String = Requester.STAGE1_ORG): EffectiveRiskPaths {
		val entries = projectId?.let { projectEntries(it) }
		return if (entries != null) EffectiveRiskPaths(projectPatterns(entries, repoKey), "project")
		else EffectiveRiskPaths(orgPatterns(orgId), "org")
	}

	fun view(orgId: String = Requester.STAGE1_ORG): ReviewSettingsView {
		val settings = orgSettings(orgId)
		val own = orgOwn(settings)
		val orgList = own ?: RiskPaths.ORG_DEFAULT
		val open = proposals(orgId)
		val projects = jdbc.sql(
			"select id, external_ref, name, (settings -> 'risk_paths')::text from projects where org_id = :org order by external_ref nulls last, name",
		).param("org", orgId)
			.query { rs, _ ->
				val ref = ProjectRef(rs.getString(1), rs.getString(2), rs.getString(3))
				val entries = rs.getString(4)?.let { json.readValue(it, object : TypeReference<List<Map<String, Any?>>>() {}) }
				ProjectRiskScope(ref, entries?.let { projectPatterns(it, null) } ?: orgList, entries == null, open.count { it.projectId == ref.id })
			}.list()
		return ReviewSettingsView(
			RiskScope(orgList, own != null), RiskPaths.ORG_DEFAULT, sampleRateOf(settings), sampleDailyCapOf(settings),
			promoteAfterHoursOf(settings), projects, open,
		)
	}

	/** The candidates people of [orgId] put forward and nobody added or dismissed yet, oldest first. */
	fun proposals(orgId: String): List<RiskProposal> =
		jdbc.sql(
			"""
			select r.id, r.project_id, r.propose_risk_path, r.work_item_id, w.title, r.note, r.decided_at
			  from review_items r left join work_items w on w.id = r.work_item_id
			 where r.proposal_state = 'open' and r.org_id = :org order by r.decided_at, r.id
			""".trimIndent(),
		).param("org", orgId).query { rs, _ ->
			RiskProposal(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
				rs.getObject(7, OffsetDateTime::class.java))
		}.list()

	/** Changes the settings of [actor]'s organisation. */
	@Transactional
	fun setOrg(body: OrgReviewSettingsIn, actor: String): ReviewSettingsView {
		if (body.riskPaths == null && body.sampleRate == null && body.sampleDailyCap == null) throw BadRequestException("nothing to change")
		val org = access.orgOf(actor)
		val before = orgSettings(org)
		val changes = linkedMapOf<String, Any?>()
		body.riskPaths?.let { changes["risk_paths"] = RiskPaths.clean(it) }
		body.sampleRate?.let {
			if (it.isNaN() || it !in 0.0..1.0) throw BadRequestException("sample_rate는 0에서 1 사이다(0.1은 10건 중 1건)")
			changes["sample_rate"] = it
		}
		body.sampleDailyCap?.let {
			if (it !in 0..1000) throw BadRequestException("sample_daily_cap은 0에서 1000 사이다")
			changes["sample_daily_cap"] = it
		}
		jdbc.sql("update organizations set settings = settings || cast(:patch as jsonb) where id = :org")
			.param("patch", json.writeValueAsString(changes)).param("org", org).update()
		sessions.audit("person", actor, "review_settings.changed", "org", org,
			changes.mapValues { (k, v) -> mapOf("before" to (before[k] ?: if (k == "risk_paths") "starting list" else null), "after" to v) })
		return view(org)
	}

	@Transactional
	fun setProject(projectId: String, body: ProjectRiskPathsIn, actor: String): ReviewSettingsView {
		val before = jdbc.sql("select (settings -> 'risk_paths')::text from projects where id = :id and org_id = :org for update")
			.param("id", projectId).param("org", access.orgOf(actor)).query(String::class.java).list()
		if (before.isEmpty()) throw NotFoundException("no project $projectId")
		val patterns = body.patterns?.let { RiskPaths.clean(it) }
		if (patterns == null) {
			jdbc.sql("update projects set settings = settings - 'risk_paths', updated_at = now() where id = :id").param("id", projectId).update()
		} else {
			jdbc.sql("update projects set settings = jsonb_set(settings, '{risk_paths}', cast(:v as jsonb), true), updated_at = now() where id = :id")
				.param("v", json.writeValueAsString(listOf(mapOf("repo" to null, "patterns" to patterns)))).param("id", projectId).update()
		}
		sessions.audit("person", actor, "review_settings.project_risk_paths", "prj", projectId,
			mapOf("before" to before.single()?.let { json.readValue(it, Any::class.java) }, "after" to patterns, "inherits" to (patterns == null)))
		return view(access.orgOf(actor))
	}

	/**
	 * Adds a candidate to the list it belongs to (its project's own list, made from the
	 * organisation's when the project had none, or the organisation's list) or dismisses it.
	 */
	@Transactional
	fun decideProposal(reviewId: String, body: ProposalIn, actor: String): ReviewSettingsView {
		val action = body.action.trim()
		if (action !in setOf("add", "dismiss")) throw BadRequestException("action must be add or dismiss")
		val (projectId, pattern) = jdbc.sql(
			"select project_id, propose_risk_path from review_items where id = :id and proposal_state = 'open' for update",
		).param("id", reviewId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional()
			.orElseThrow { ConflictException("열린 위험 경로 후보가 아니다. 이미 더했거나 뺐다") }
		val org = access.orgOf(actor)
		if (action == "add") {
			if (projectId == null) {
				val list = orgPatterns(org)
				if (pattern !in list) setOrg(OrgReviewSettingsIn(riskPaths = list + pattern), actor)
			} else {
				val entries = projectEntries(projectId)
				val list = entries?.let { projectPatterns(it, null) } ?: orgPatterns(org)
				if (entries == null || pattern !in list) setProject(projectId, ProjectRiskPathsIn(list + pattern), actor)
			}
		}
		jdbc.sql("update review_items set proposal_state = :state, proposal_decided_at = now() where id = :id")
			.param("state", if (action == "add") "added" else "dismissed").param("id", reviewId).update()
		sessions.audit("person", actor, if (action == "add") "review.proposal_added" else "review.proposal_dismissed", "rvw", reviewId,
			mapOf("pattern" to pattern, "project_id" to projectId))
		return view(org)
	}
}

@RestController
@RequestMapping("/api")
class ReviewSettingsController(private val settings: ReviewSettingsService, private val permissions: Permissions) {

	@GetMapping("/review-settings")
	fun view(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): ReviewSettingsView {
		val me = permissions.person(authorization, "read the review settings", Act.READ)
		return settings.view(me.orgId!!)
	}

	@PutMapping("/review-settings")
	fun setOrg(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: OrgReviewSettingsIn,
	): ReviewSettingsView {
		val me = permissions.person(authorization, "change the review settings", Act.SETTINGS)
		return settings.setOrg(body, me.person!!)
	}

	@PutMapping("/projects/{id}/risk-paths")
	fun setProject(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: ProjectRiskPathsIn,
	): ReviewSettingsView {
		val me = permissions.person(authorization, "change a project's risk paths", Act.SETTINGS)
		return settings.setProject(id, body, me.person!!)
	}

	@PostMapping("/reviews/{id}/proposal")
	fun proposal(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: ProposalIn,
	): ReviewSettingsView {
		val me = permissions.person(authorization, "add or dismiss a risk path candidate", Act.SETTINGS)
		return settings.decideProposal(id, body, me.person!!)
	}
}
