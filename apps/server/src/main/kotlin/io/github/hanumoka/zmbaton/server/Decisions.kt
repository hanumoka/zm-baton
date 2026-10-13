package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
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

// Decision records (FN-0604). In stage 1 they come from zm-docs, whose Git files stay their
// source of truth (G20.1, FN-0605 "1단계는 zm-docs 결정 기록을 Git에서 고친다"), so this side only
// reads them. An import (ZmDocsImport) writes them.

data class DecisionRef(
	val id: String,
	@JsonProperty("external_ref") val externalRef: String?,
	val question: String,
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
)

data class DecisionSection(val heading: String, val body: String)

data class DecisionDetail(
	val decision: DecisionView,
	val choice: String?,
	val sections: List<DecisionSection>,
	@JsonProperty("source_of_truth") val sourceOfTruth: String,
	@JsonProperty("git_ref") val gitRef: Map<String, Any?>?,
)

@Service
class DecisionService(private val jdbc: JdbcClient, private val json: ObjectMapper) {

	companion object {
		const val PREVIEW_MAX = 200
		val STATUSES = setOf("proposed", "accepted", "rejected", "superseded")
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val sectionsType = object : TypeReference<List<DecisionSection>>() {}

	/** Newest decision first. [query] matches the number, the question and the decision. */
	fun list(limit: Int, projectId: String?, status: String?, query: String?): List<DecisionView> {
		if (status != null && status !in STATUSES) throw BadRequestException("status must be one of $STATUSES")
		val where = mutableListOf("true")
		if (projectId != null) where += "exists (select 1 from decision_projects dp where dp.decision_id = d.id and dp.project_id = :project)"
		if (status != null) where += "d.status = :status"
		val q = query?.trim()?.take(100)?.ifEmpty { null }
		if (q != null) where += "(d.external_ref ilike :q or d.question ilike :q or d.choice ilike :q)"
		val stmt = jdbc.sql(
			"$SELECT_VIEW where ${where.joinToString(" and ")} order by d.decided_on desc nulls last, d.external_ref desc limit :limit",
		).param("limit", limit)
		if (projectId != null) stmt.param("project", projectId)
		if (status != null) stmt.param("status", status)
		if (q != null) stmt.param("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
		return withProjects(stmt.query { rs, _ -> view(rs) }.list())
	}

	fun detail(id: String): DecisionDetail {
		val view = jdbc.sql("$SELECT_VIEW where d.id = :id").param("id", id).query { rs, _ -> view(rs) }.optional()
			.orElseThrow { NotFoundException("no decision $id") }
		return jdbc.sql("select choice, sections::text, source_of_truth, git_ref::text from decisions where id = :id")
			.param("id", id).query { rs, _ ->
				DecisionDetail(
					withProjects(listOf(view)).single(), rs.getString(1), json.readValue(rs.getString(2), sectionsType),
					rs.getString(3), rs.getString(4)?.let { json.readValue(it, mapType) },
				)
			}.single()
	}

	/** The newest decisions recorded in one repository, for a session's status card. */
	fun recentInRepository(repoKey: String, limit: Int): List<DecisionView> =
		jdbc.sql("$SELECT_VIEW where d.git_ref ->> 'repo_key' = :repo order by d.decided_on desc nulls last, d.external_ref desc limit :limit")
			.param("repo", repoKey).param("limit", limit).query { rs, _ -> view(rs) }.list()

	private val SELECT_VIEW = """
		select d.id, d.external_ref, d.question, d.status, d.decided_on, d.choice, d.updated_at,
		       s.id as s_id, s.external_ref as s_ref, s.question as s_question,
		       b.id as b_id, b.external_ref as b_ref, b.question as b_question
		  from decisions d
		  left join decisions s on s.id = d.supersedes_id
		  left join decisions b on b.id = d.superseded_by_id
	""".trimIndent()

	private fun view(rs: ResultSet): DecisionView =
		DecisionView(
			rs.getString("id"), rs.getString("external_ref"), rs.getString("question"), rs.getString("status"),
			rs.getObject("decided_on", LocalDate::class.java), emptyList(),
			rs.getString("choice")?.let { preview(it) },
			rs.getString("s_id")?.let { DecisionRef(it, rs.getString("s_ref"), rs.getString("s_question")) },
			rs.getString("b_id")?.let { DecisionRef(it, rs.getString("b_ref"), rs.getString("b_question")) },
			rs.getObject("updated_at", OffsetDateTime::class.java),
		)

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

	/** The first paragraph, flattened to one line and cut to [PREVIEW_MAX] characters. */
	private fun preview(choice: String): String? {
		val first = choice.trim().split(Regex("\\n\\s*\\n")).firstOrNull()?.replace(Regex("\\s+"), " ")?.trim() ?: return null
		if (first.isEmpty()) return null
		return if (first.length > PREVIEW_MAX) first.take(PREVIEW_MAX - 1) + "…" else first
	}
}

@RestController
@RequestMapping("/api")
class DecisionController(private val decisions: DecisionService, private val people: PersonService) {

	@GetMapping("/decisions")
	fun list(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(defaultValue = "50") limit: Int,
		@RequestParam(name = "project_id", required = false) projectId: String?,
		@RequestParam(required = false) status: String?,
		@RequestParam(required = false) q: String?,
	): List<DecisionView> {
		people.refuseSessionToken(authorization, "list decisions")
		return decisions.list(limit.coerceIn(1, 500), projectId?.trim()?.ifEmpty { null }, status?.trim()?.ifEmpty { null }, q)
	}

	@GetMapping("/decisions/{id}")
	fun detail(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): DecisionDetail {
		people.refuseSessionToken(authorization, "read a decision")
		return decisions.detail(id)
	}
}
