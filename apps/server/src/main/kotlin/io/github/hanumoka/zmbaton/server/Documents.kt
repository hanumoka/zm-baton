package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
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
import java.time.OffsetDateTime

// Documents (FN-0601, FN-0602), read side: each with its one source of truth, its versions and
// their approval, and the work and decisions it is linked to. DocumentWriteService changes them.

/** The newest version of a document, for a list. */
data class VersionSummary(
	val number: Int,
	/** `draft` or `approved`. */
	val state: String,
	@JsonProperty("git_commit_sha") val gitCommitSha: String?,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
)

data class DocumentView(
	val id: String,
	val title: String,
	val project: ProjectRef?,
	@JsonProperty("source_of_truth") val sourceOfTruth: String,
	/** A Git document's place: `repo`, `path` and, when given, `branch`. Null for a product document. */
	@JsonProperty("git_ref") val gitRef: Map<String, Any?>?,
	@JsonProperty("owner_user_id") val ownerUserId: String,
	val version: Long,
	/** The newest version; null before the first one. */
	val latest: VersionSummary?,
	/** The highest version number that is approved; null when none is. */
	@JsonProperty("approved_number") val approvedNumber: Int?,
	@JsonProperty("updated_at") val updatedAt: OffsetDateTime,
	/** Added with permissions: the sharing scope (G06.1). */
	val visibility: String = "org",
)

data class DocumentActionView(val allowed: Boolean, val reason: String?)

data class DocumentVersionView(
	val id: String,
	val number: Int,
	val state: String,
	val note: String?,
	/** The body of a product document's version; only on the version a detail shows. */
	@JsonProperty("body_markdown") val bodyMarkdown: String?,
	@JsonProperty("git_commit_sha") val gitCommitSha: String?,
	@JsonProperty("created_by") val createdBy: String,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("approved_by") val approvedBy: String?,
	@JsonProperty("approved_at") val approvedAt: OffsetDateTime?,
	/** Whether the person asking may approve it, and why not. */
	val approve: DocumentActionView,
)

/** A decision a document is linked to, as much as a link to it needs. */
data class LinkedDecision(val id: String, val ref: String?, val question: String, val status: String)

data class DocumentDetail(
	val document: DocumentView,
	/** Newest first, without bodies. */
	val versions: List<DocumentVersionView>,
	/** The version asked for (the newest by default), with its body; null before the first one. */
	val shown: DocumentVersionView?,
	@JsonProperty("work_items") val workItems: List<LinkedWork>,
	val decisions: List<LinkedDecision>,
	/** Who may approve its versions now. */
	val approver: String,
	/** Non-null when the body is not written here: a Git document. */
	@JsonProperty("read_only_reason") val readOnlyReason: String?,
	val history: List<HistoryEntry>,
)

@Service
class DocumentService(private val jdbc: JdbcClient, private val json: ObjectMapper, private val access: PermissionFacts) {

	companion object {
		const val HISTORY_MAX = 50
		val SOURCES = setOf("product", "git")
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	/** Most recently changed first. [query] matches the title and a Git document's path. With [viewer], those they may see. */
	fun list(limit: Int, projectId: String?, source: String?, query: String?, viewer: Actor? = null): List<DocumentView> {
		if (source != null && source !in SOURCES) throw BadRequestException("source must be one of $SOURCES")
		val where = mutableListOf("true")
		val scope = viewer?.let { PermissionRules.filter(it, ScopeColumns.DOCUMENTS) }
		if (scope != null) where += scope.sql
		if (projectId != null) where += "d.project_id = :project"
		if (source != null) where += "d.source_of_truth = :source"
		val q = query?.trim()?.take(100)?.ifEmpty { null }
		if (q != null) where += "(d.title ilike :q or d.git_ref ->> 'path' ilike :q)"
		val stmt = jdbc.sql("$SELECT_VIEW where ${where.joinToString(" and ")} order by d.updated_at desc, d.id desc limit :limit")
			.param("limit", limit)
		if (projectId != null) stmt.param("project", projectId)
		if (source != null) stmt.param("source", source)
		if (q != null) stmt.param("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
		scope?.params?.forEach { (k, v) -> stmt.param(k, v) }
		return stmt.query { rs, _ -> view(rs) }.list()
	}

	/** One document with its versions; [number] picks the version shown, the newest by default. */
	fun detail(id: String, number: Int?, actor: String): DocumentDetail {
		val view = load(id) ?: throw NotFoundException("no document $id")
		val approver = access.approverFor(orgOf(id), "document_version")
		val versions = jdbc.sql(
			"""
			select id, number, note, git_commit_sha, created_by, created_at, approved_by, approved_at
			  from document_versions where doc_id = :id order by number desc
			""".trimIndent(),
		).param("id", id).query { rs, _ -> version(rs, null, approver, actor) }.list()
		val want = number ?: versions.firstOrNull()?.number
		val shown = want?.let { n ->
			if (versions.none { it.number == n }) throw NotFoundException("document $id has no version $n")
			val body = jdbc.sql("select body_markdown from document_versions where doc_id = :id and number = :n")
				.param("id", id).param("n", n).query(String::class.java).optional().orElse(null)
			versions.first { it.number == n }.copy(bodyMarkdown = body)
		}
		return DocumentDetail(
			view, versions, shown, linkedWork(id), linkedDecisions(id), approver,
			if (view.sourceOfTruth == "git") DocumentRules.gitReason(view.gitRef?.get("path") as String?) else null,
			history(id),
		)
	}

	internal fun load(id: String): DocumentView? =
		jdbc.sql("$SELECT_VIEW where d.id = :id").param("id", id).query { rs, _ -> view(rs) }.optional().orElse(null)

	internal fun orgOf(id: String): String =
		jdbc.sql("select org_id from documents where id = :id").param("id", id).query(String::class.java).single()

	private val SELECT_VIEW = """
		select d.id, d.title, d.source_of_truth, d.git_ref::text as git_ref, d.owner_user_id, d.version, d.updated_at, d.visibility,
		       p.id as project_id, p.external_ref as project_ref, p.name as project_name,
		       v.number as v_number, v.approved_by as v_approved_by, v.git_commit_sha as v_commit, v.created_at as v_created_at,
		       (select max(a.number) from document_versions a where a.doc_id = d.id and a.approved_by is not null) as approved_number
		  from documents d
		  left join projects p on p.id = d.project_id
		  left join lateral (
		       select number, approved_by, git_commit_sha, created_at from document_versions
		        where doc_id = d.id order by number desc limit 1) v on true
	""".trimIndent()

	private fun view(rs: ResultSet): DocumentView {
		val number = rs.getObject("v_number") as Int?
		return DocumentView(
			rs.getString("id"), rs.getString("title"),
			rs.getString("project_id")?.let { ProjectRef(it, rs.getString("project_ref"), rs.getString("project_name")) },
			rs.getString("source_of_truth"), rs.getString("git_ref")?.let { json.readValue(it, mapType) },
			rs.getString("owner_user_id"), rs.getLong("version"),
			number?.let {
				VersionSummary(it, DocumentRules.state(rs.getString("v_approved_by")), rs.getString("v_commit"),
					rs.getObject("v_created_at", OffsetDateTime::class.java))
			},
			rs.getObject("approved_number") as Int?,
			rs.getObject("updated_at", OffsetDateTime::class.java), rs.getString("visibility"),
		)
	}

	private fun version(rs: ResultSet, body: String?, approver: String, actor: String): DocumentVersionView {
		val state = DocumentRules.state(rs.getString("approved_by"))
		val approve = DocumentRules.approve(state, approver, actor)
		return DocumentVersionView(
			rs.getString("id"), rs.getInt("number"), state, rs.getString("note"), body, rs.getString("git_commit_sha"),
			rs.getString("created_by"), rs.getObject("created_at", OffsetDateTime::class.java), rs.getString("approved_by"),
			rs.getObject("approved_at", OffsetDateTime::class.java), DocumentActionView(approve.allowed, approve.reason),
		)
	}

	private fun linkedWork(id: String): List<LinkedWork> =
		jdbc.sql(
			"""
			select w.id, w.external_ref, w.title, w.stage, w.wait_reasons from document_work_items dw join work_items w on w.id = dw.work_item_id
			 where dw.document_id = :id order by w.external_ref nulls last, w.created_at
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			LinkedWork(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), (rs.getArray(5).array as Array<String>).toList())
		}.list()

	private fun linkedDecisions(id: String): List<LinkedDecision> =
		jdbc.sql(
			"""
			select d.id, d.external_ref, d.number, d.question, d.status from document_decisions dd join decisions d on d.id = dd.decision_id
			 where dd.document_id = :id order by d.decided_on desc nulls first, d.external_ref desc nulls last, d.number desc nulls last
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			val number = rs.getObject(3) as Int?
			LinkedDecision(rs.getString(1), rs.getString(2) ?: number?.let(DecisionService::refOf), rs.getString(4), rs.getString(5))
		}.list()

	private fun history(id: String): List<HistoryEntry> =
		jdbc.sql(
			"""
			select action, actor_kind, actor_id, details::text, at from audit_log
			 where target_kind = 'doc' and target_id = :id order by at desc, id desc limit :limit
			""".trimIndent(),
		).param("id", id).param("limit", HISTORY_MAX).query { rs, _ ->
			HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), json.readValue(rs.getString(4), mapType),
				rs.getObject(5, OffsetDateTime::class.java))
		}.list()
}

@RestController
@RequestMapping("/api")
class DocumentController(private val documents: DocumentService, private val permissions: Permissions) {

	@GetMapping("/documents")
	fun list(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(defaultValue = "50") limit: Int,
		@RequestParam(name = "project_id", required = false) projectId: String?,
		@RequestParam(required = false) source: String?,
		@RequestParam(required = false) q: String?,
	): List<DocumentView> {
		val me = permissions.person(authorization, "list documents", Act.READ)
		return documents.list(limit.coerceIn(1, 500), projectId?.trim()?.ifEmpty { null }, source?.trim()?.ifEmpty { null }, q, me)
	}

	/** `version` picks the version whose body is shown; the newest by default. */
	@GetMapping("/documents/{id}")
	fun detail(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(required = false) version: Int?,
	): DocumentDetail {
		val me = permissions.person(authorization, "read a document")
		permissions.see(me, permissions.document(id))
		return documents.detail(id, version, me.person!!)
	}
}
