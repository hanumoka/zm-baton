package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper

// Documents written and registered in zm-baton (FN-0601, FN-0602): a person registers a document
// with its one source of truth, changes its own fields with the version they saw, adds versions
// (a product document's Markdown body, or the commit a Git document's version points at) and its
// approver approves a version. Every change is written to audit_log, which is also the document's
// history; every approval is an approvals row.

data class DocumentVersionIn(
	/** The newest version number the writer saw (null for the first), so a newer one is not overwritten. */
	@JsonProperty("based_on") val basedOn: Int? = null,
	@JsonProperty("body_markdown") val bodyMarkdown: String? = null,
	@JsonProperty("git_commit_sha") val gitCommitSha: String? = null,
	val note: String? = null,
)

data class DocumentApprovalIn(
	/** Only `approved` in stage 1: a version goes from draft to approved. */
	val decision: String,
	val note: String? = null,
)

@Service
class DocumentWriteService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val documents: DocumentService,
	private val approvals: ApprovalService,
	private val sessions: SessionService,
) {
	companion object {
		const val TITLE_MAX = 300
		const val BODY_MAX = 200_000
		const val NOTE_MAX = 1000
		const val REPO_MAX = 300
		const val PATH_MAX = 500
		const val BRANCH_MAX = 200
		const val LINKS_MAX = 50
		val CREATE_FIELDS = setOf("title", "source_of_truth", "project_id", "body_markdown", "note", "git", "work_item_ids", "decision_ids")
		val UPDATE_FIELDS = setOf("title", "project_id", "git", "work_item_ids", "decision_ids")
		private val COMMIT = Regex("^[0-9a-f]{7,64}$")
		private val CREDENTIALS = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*@")
		private val DRIVE = Regex("^[A-Za-z]:")
	}

	// ---------------------------------------------------------------- register and change

	/**
	 * A new document. A product document with a body gets version 1 at once; a Git document gets
	 * version 1 when a commit is given. Without either it has no version until one is added.
	 */
	@Transactional
	fun register(body: Map<String, Any?>, actor: String): DocumentDetail {
		val unknown = body.keys - CREATE_FIELDS
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown")
		val title = title(body["title"])
		val source = text(body["source_of_truth"], "source_of_truth", 10)
		if (source !in DocumentService.SOURCES) throw BadRequestException("source_of_truth must be product or git")
		val git = if (source == "git") gitRef(body["git"] ?: throw BadRequestException("a Git document needs git.repo and git.path")) else null
		if (source == "product" && body["git"] != null) throw BadRequestException("a product document has no Git location")
		val projectId = project(body["project_id"])
		val id = Ids.document()
		jdbc.sql(
			"""
			insert into documents (id, org_id, project_id, title, source_of_truth, git_ref, owner_user_id)
			values (:id, :org, :project, :title, :source, cast(:git as jsonb), :owner)
			""".trimIndent(),
		).param("id", id).param("org", WorkItemService.ORG).param("project", projectId).param("title", title).param("source", source)
			.param("git", git?.first?.let { json.writeValueAsString(it) }).param("owner", actor).update()
		links(id, body, replace = false)
		audit(actor, "document.registered", id, mapOf("title" to title, "source_of_truth" to source, "path" to git?.first?.get("path")))
		val note = note(body["note"])
		when {
			source == "product" && body["body_markdown"] != null -> insertVersion(id, 1, body(body["body_markdown"]), null, note, actor)
			source == "git" && git?.second != null -> insertVersion(id, 1, null, git.second, note, actor)
			source == "git" && body["body_markdown"] != null -> throw BadRequestException("a Git document's body stays in Git")
		}
		return documents.detail(id, null, actor)
	}

	/** Changes the document's own fields present in [patch]; `version` must be the version the person saw. */
	@Transactional
	fun update(id: String, patch: Map<String, Any?>, actor: String): DocumentDetail {
		val version = (patch["version"] as? Number)?.toLong() ?: throw BadRequestException("version is required")
		if ("source_of_truth" in patch) throw BadRequestException("a document keeps its source of truth; moving it is not built yet")
		val unknown = patch.keys - UPDATE_FIELDS - "version"
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown")
		val doc = lock(id)
		if (doc.version != version) throw ConflictException("the document changed (version ${doc.version}, you sent $version); reload it")
		val changed = mutableListOf<String>()
		if ("title" in patch) {
			val t = title(patch["title"])
			if (t != doc.title) changed += "title".also { set(id, "title = :v", t) }
		}
		if ("project_id" in patch) {
			val p = project(patch["project_id"])
			if (p != doc.project?.id) changed += "project_id".also { set(id, "project_id = :v", p) }
		}
		if ("git" in patch) {
			if (doc.sourceOfTruth != "git") throw BadRequestException("a product document has no Git location")
			val (ref, commit) = gitRef(patch["git"] ?: throw BadRequestException("git cannot be empty"))
			if (commit != null) throw BadRequestException("record a commit as a new version, not as the location")
			if (ref != doc.gitRef) changed += "git".also { set(id, "git_ref = cast(:v as jsonb)", json.writeValueAsString(ref)) }
		}
		changed += links(id, patch, replace = true)
		if (changed.isEmpty()) throw BadRequestException("nothing to change")
		jdbc.sql("update documents set version = version + 1, updated_at = now() where id = :id").param("id", id).update()
		audit(actor, "document.updated", id, mapOf("fields" to changed))
		return documents.detail(id, null, actor)
	}

	// ---------------------------------------------------------------- versions and approval

	/**
	 * The next version. A product document takes a new body; a Git document takes the commit the
	 * version points at, and its body stays in Git. [DocumentVersionIn.basedOn] must be the newest
	 * number now, so two writers do not silently overwrite each other.
	 */
	@Transactional
	fun addVersion(id: String, input: DocumentVersionIn, actor: String): DocumentDetail {
		val doc = lock(id)
		val newest = doc.latest?.number
		if (input.basedOn != newest) {
			throw ConflictException("version ${newest ?: "없음"} is the newest now, not ${input.basedOn ?: "none"}; reload it")
		}
		val note = note(input.note)
		if (doc.sourceOfTruth == "git") {
			if (input.bodyMarkdown != null) throw BadRequestException(DocumentRules.gitReason(doc.gitRef?.get("path") as String?))
			val commit = commit(input.gitCommitSha) ?: throw BadRequestException("git_commit_sha is required for a Git document")
			if (commit == doc.latest?.gitCommitSha) throw BadRequestException("the newest version already points at $commit")
			insertVersion(id, (newest ?: 0) + 1, null, commit, note, actor)
		} else {
			if (input.gitCommitSha != null) throw BadRequestException("a product document's version has a body, not a commit")
			val text = body(input.bodyMarkdown ?: throw BadRequestException("body_markdown is required"))
			if (newest != null && text == bodyOf(id, newest)) throw BadRequestException("the body is the same as version $newest")
			insertVersion(id, (newest ?: 0) + 1, text, null, note, actor)
		}
		return documents.detail(id, null, actor)
	}

	/** The approver approves one version. This is where a contradiction check will start (contract "문서 모순 후보" 1). */
	@Transactional
	fun approve(id: String, number: Int, input: DocumentApprovalIn, actor: String): DocumentDetail {
		if (input.decision.trim() != "approved") throw BadRequestException("decision must be approved")
		lock(id)
		val row = jdbc.sql("select id, approved_by from document_versions where doc_id = :id and number = :n for update")
			.param("id", id).param("n", number).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)
			?: throw NotFoundException("document $id has no version $number")
		val approver = ApprovalPolicy.approverFor(documents.orgOf(id), "document_version")
		val action = DocumentRules.approve(DocumentRules.state(row.second), approver, actor)
		if (!action.allowed) throw ConflictException(action.reason ?: "not allowed")
		val note = note(input.note)
		jdbc.sql("update document_versions set approved_by = :by, approved_at = now() where id = :id").param("by", actor).param("id", row.first).update()
		val approval = approvals.record("document_version", row.first, number.toLong(), actor, "approved", note)
		jdbc.sql("update documents set updated_at = now() where id = :id").param("id", id).update()
		audit(actor, "document.version_approved", id, mapOf("number" to number, "approval" to approval, "note" to note))
		return documents.detail(id, number, actor)
	}

	// ---------------------------------------------------------------- internals

	private fun lock(id: String): DocumentView {
		jdbc.sql("select id from documents where id = :id for update").param("id", id).query(String::class.java).optional()
			.orElseThrow { NotFoundException("no document $id") }
		return documents.load(id)!!
	}

	/** Sets one column; [assignment] is one of the fixed strings above, never input. */
	private fun set(id: String, assignment: String, value: Any?) {
		jdbc.sql("update documents set $assignment where id = :id").param("v", value).param("id", id).update()
	}

	private fun insertVersion(id: String, number: Int, body: String?, commit: String?, note: String?, actor: String) {
		jdbc.sql(
			"""
			insert into document_versions (id, doc_id, number, body_markdown, git_commit_sha, note, created_by)
			values (:vid, :id, :number, :body, :commit, :note, :by)
			""".trimIndent(),
		).param("vid", Ids.documentVersion()).param("id", id).param("number", number).param("body", body).param("commit", commit)
			.param("note", note).param("by", actor).update()
		jdbc.sql("update documents set updated_at = now() where id = :id").param("id", id).update()
		audit(actor, "document.version_added", id, mapOf("number" to number, "commit" to commit, "note" to note))
	}

	private fun bodyOf(id: String, number: Int): String? =
		jdbc.sql("select body_markdown from document_versions where doc_id = :id and number = :n").param("id", id).param("n", number)
			.query(String::class.java).optional().orElse(null)

	/** Replaces the links present in [fields]; returns the names of those that changed. */
	private fun links(id: String, fields: Map<String, Any?>, replace: Boolean): List<String> {
		val changed = mutableListOf<String>()
		for ((field, table, column, target) in listOf(
			listOf("work_item_ids", "document_work_items", "work_item_id", "work_items"),
			listOf("decision_ids", "document_decisions", "decision_id", "decisions"),
		)) {
			if (field !in fields) continue
			val ids = ids(fields[field], field, target)
			val before = jdbc.sql("select $column from $table where document_id = :id order by created_at, $column").param("id", id)
				.query(String::class.java).list()
			if (replace && before.toSet() == ids.toSet()) continue
			jdbc.sql("delete from $table where document_id = :id").param("id", id).update()
			for (x in ids) jdbc.sql("insert into $table (document_id, $column) values (:d, :x)").param("d", id).param("x", x).update()
			changed += field
		}
		return changed
	}

	/** Ids that must exist in [table], one of two fixed names; order kept, repeats dropped. */
	private fun ids(raw: Any?, field: String, table: String): List<String> {
		if (raw == null) return emptyList()
		val list = raw as? List<*> ?: throw BadRequestException("$field must be a list")
		val ids = list.map { (it as? String)?.trim()?.ifEmpty { null } ?: throw BadRequestException("$field must hold ids") }.distinct()
		if (ids.size > LINKS_MAX) throw BadRequestException("at most $LINKS_MAX $field")
		if (ids.isEmpty()) return ids
		check(table == "work_items" || table == "decisions")
		val found = jdbc.sql("select id from $table where id in (:ids)").param("ids", ids).query(String::class.java).list().toSet()
		val missing = ids.filter { it !in found }
		if (missing.isNotEmpty()) throw BadRequestException("$field: no such ids $missing")
		return ids
	}

	private fun project(raw: Any?): String? {
		val id = text(raw, "project_id", 100) ?: return null
		val found = jdbc.sql("select count(*) from projects where id = :id").param("id", id).query(Long::class.javaObjectType).single() > 0
		if (!found) throw BadRequestException("no project $id")
		return id
	}

	/**
	 * `{"repo", "path", "branch", "commit"}` checked: a repository without credentials, a path from
	 * its root (never absolute, never leaving it), an optional branch and commit.
	 */
	private fun gitRef(raw: Any): Pair<Map<String, String>, String?> {
		val m = raw as? Map<*, *> ?: throw BadRequestException("git must be an object")
		val unknown = m.keys.map { it.toString() } - setOf("repo", "path", "branch", "commit")
		if (unknown.isNotEmpty()) throw BadRequestException("unknown git fields $unknown")
		val repo = text(m["repo"], "git.repo", REPO_MAX) ?: throw BadRequestException("git.repo is required")
		if (CREDENTIALS.containsMatchIn(repo)) throw BadRequestException("git.repo must not carry a user name or password")
		val path = text(m["path"], "git.path", PATH_MAX)?.replace('\\', '/') ?: throw BadRequestException("git.path is required")
		if (path.startsWith("/") || path.startsWith("~") || DRIVE.containsMatchIn(path) || path.split('/').any { it == ".." }) {
			throw BadRequestException("git.path must be a path from the repository root, not an absolute path")
		}
		val branch = text(m["branch"], "git.branch", BRANCH_MAX)
		val ref = listOfNotNull("repo" to repo, "path" to path, branch?.let { "branch" to it }).toMap()
		return ref to commit(m["commit"])
	}

	private fun commit(raw: Any?): String? {
		val c = text(raw, "git_commit_sha", 64)?.lowercase() ?: return null
		if (!COMMIT.matches(c)) throw BadRequestException("a commit must be 7 to 64 hex characters")
		return c
	}

	private fun title(raw: Any?): String =
		text(raw, "title", TITLE_MAX) ?: throw BadRequestException("title must be 1-$TITLE_MAX characters")

	private fun note(raw: Any?): String? = text(raw, "note", NOTE_MAX)

	/** A body is kept as written (only line ends made uniform); an empty one is allowed. */
	private fun body(raw: Any?): String {
		val b = raw as? String ?: throw BadRequestException("body_markdown must be text")
		if (b.length > BODY_MAX) throw BadRequestException("body_markdown must be at most $BODY_MAX characters")
		return b.replace("\r\n", "\n")
	}

	private fun text(raw: Any?, field: String, max: Int): String? {
		if (raw != null && raw !is String) throw BadRequestException("$field must be text")
		val t = (raw as String?)?.trim()?.ifEmpty { null } ?: return null
		if (t.length > max) throw BadRequestException("$field must be at most $max characters")
		return t
	}

	private fun audit(actor: String, action: String, id: String, details: Map<String, Any?>) =
		sessions.audit("person", actor, action, "doc", id, details)
}

@RestController
@RequestMapping("/api")
class DocumentWriteController(private val writes: DocumentWriteService, private val people: PersonService) {

	@PostMapping("/documents")
	@ResponseStatus(HttpStatus.CREATED)
	fun register(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: Map<String, Any?>,
	): DocumentDetail {
		people.refuseSessionToken(authorization, "register a document")
		return writes.register(body, Requester.person())
	}

	/** Changes only the document's own fields present in the body; `version` is required. */
	@PatchMapping("/documents/{id}")
	fun update(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody patch: Map<String, Any?>,
	): DocumentDetail {
		people.refuseSessionToken(authorization, "change a document")
		return writes.update(id, patch, Requester.person())
	}

	@PostMapping("/documents/{id}/versions")
	@ResponseStatus(HttpStatus.CREATED)
	fun addVersion(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: DocumentVersionIn,
	): DocumentDetail {
		people.refuseSessionToken(authorization, "add a document version")
		return writes.addVersion(id, body, Requester.person())
	}

	@PostMapping("/documents/{id}/versions/{number}/approvals")
	fun approve(
		@PathVariable id: String,
		@PathVariable number: Int,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: DocumentApprovalIn,
	): DocumentDetail {
		people.refuseSessionToken(authorization, "approve a document version")
		return writes.approve(id, number, body, Requester.person())
	}
}
