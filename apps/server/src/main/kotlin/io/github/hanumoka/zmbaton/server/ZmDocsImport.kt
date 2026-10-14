package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.auth.Access
import io.github.hanumoka.zmbaton.server.auth.AccessKind
import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.Permissions
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

// The trial import of zm-docs (FN-1004 "목록 작성→시험 가져오기→대조→병행→문서별 정본 전환"; G21.1).
// The CLI reads three kinds of files in a zm-docs checkout and sends them as they are; this side
// knows their format. Git stays the source of truth: imported work items and decisions are copies
// that only the next import changes. A dry run applies everything inside a transaction and rolls
// it back, so the report it gives is exactly what an apply would do.

data class ImportFile(val path: String, val content: String)

data class ZmDocsImportIn(
	@JsonProperty("repo_key") val repoKey: String,
	val files: List<ImportFile>,
	/** False (the default) only reports what would change. */
	val apply: Boolean = false,
	/** The commit the files were read from. Stored with each copy that this import changes. */
	val commit: String? = null,
)

data class ImportCount(var created: Int = 0, var updated: Int = 0, var unchanged: Int = 0, var skipped: Int = 0)

data class ImportChange(
	val kind: String,
	val ref: String,
	/** `created`, `updated` or `skipped`. Unchanged items are only counted. */
	val action: String,
	val fields: List<String> = emptyList(),
	val note: String? = null,
)

data class ZmDocsImportOut(
	val applied: Boolean,
	val projects: ImportCount,
	@JsonProperty("work_items") val workItems: ImportCount,
	val decisions: ImportCount,
	val changes: List<ImportChange>,
	val warnings: List<String>,
)

@Service
class ZmDocsImportService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val tx: TransactionTemplate,
	private val sessions: SessionService,
	private val access: PermissionFacts,
	private val org: OrgService,
	private val events: ApplicationEventPublisher,
) {
	companion object {
		const val TASKS_PATH = "registry/tasks.json"
		const val PROJECTS_PATH = "registry/projects.json"
		const val MAX_FILES = 2000
		const val MAX_FILE_CHARS = 1_000_000
		/**
		 * Part of every stored decision hash. Raise it when the way a decision file is read
		 * changes, so the next import rewrites files whose text did not change.
		 */
		const val DECISION_FORMAT = 2
		private val DECISION_PATH = Regex("^docs/governance/decisions/(DEC-[0-9]{4,})-[^/]+\\.md$")
		private val TASK_REF = Regex("^TSK-[0-9]{3,}$")
		private val PROJECT_REF = Regex("^PRJ-[0-9]{3,}$")
		private val COMMIT = Regex("^[0-9a-f]{7,64}$")

		/** zm-docs task states (registry/schemas/tasks.schema.json) as a stage and wait reasons. */
		val STATE_TO_STAGE = mapOf(
			"open" to "ready", "doing" to "in_progress", "blocked" to "in_progress", "done" to "done", "dropped" to "cancelled",
		)
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	/** A decision this import saw, with the numbers its supersession fields name. */
	private data class Imported(val id: String, val ref: String, val supersedes: String?, val supersededBy: String?)

	/** Imports into [actor]'s organisation; the copies are theirs (responsible person, decision owner). */
	fun import(req: ZmDocsImportIn, actor: String): ZmDocsImportOut {
		val repoKey = req.repoKey.trim()
		if (!SessionService.isRepoKey(repoKey)) throw BadRequestException("repo_key is missing or not a key")
		if (req.files.size > MAX_FILES) throw BadRequestException("at most $MAX_FILES files")
		val commit = req.commit?.trim()?.lowercase()?.ifEmpty { null }
		if (commit != null && !COMMIT.matches(commit)) throw BadRequestException("commit must be a hex commit id")
		val run = Run(repoKey, commit, actor, access.orgOf(actor))
		for (file in req.files) {
			if (file.content.length > MAX_FILE_CHARS) throw BadRequestException("${file.path} is larger than $MAX_FILE_CHARS characters")
		}
		val result = tx.execute { status ->
			run.go(req.files)
			sessions.audit(
				"person", actor, if (req.apply) "import.zm_docs.applied" else "import.zm_docs.dry_run", null, null,
				mapOf("repo_key" to repoKey, "commit" to commit, "projects" to run.projects, "work_items" to run.workItems, "decisions" to run.decisions),
			)
			if (!req.apply) status.setRollbackOnly()
			// One contradiction check for the whole import, about the accepted decisions it created or
			// whose text changed (MasterChecks.kt bundles a few and skips more). A dry run rolls back,
			// so the check, which starts only after a commit, never starts for it.
			if (req.apply && run.changedAccepted.isNotEmpty()) {
				events.publishEvent(ContradictionTrigger("zm_docs_import", run.changedAccepted.map { CheckSubject("decision", it) }))
			}
			ZmDocsImportOut(req.apply, run.projects, run.workItems, run.decisions, run.changes, run.warnings)
		}
		return result
	}

	/** One import: counts, changes and warnings collected while it runs. */
	private inner class Run(val repoKey: String, val commit: String?, val person: String, val orgId: String) {
		val projects = ImportCount()
		val workItems = ImportCount()
		val decisions = ImportCount()
		val changes = mutableListOf<ImportChange>()
		val warnings = mutableListOf<String>()
		/** Accepted decisions this import created or whose text changed: what a contradiction check looks at. */
		val changedAccepted = mutableListOf<String>()

		fun go(files: List<ImportFile>) {
			val byPath = linkedMapOf<String, String>()
			for (f in files) {
				val path = f.path.trim().replace('\\', '/')
				when {
					path == PROJECTS_PATH || path == TASKS_PATH || DECISION_PATH.matches(path) -> byPath[path] = f.content
					else -> warnings += "$path: zm-docs 가져오기 대상이 아니어서 건너뛴다"
				}
			}
			// The repository the copies come from is one of the organisation's (V13 named it `zm-docs`).
			org.ensureRepository(orgId, repoKey, "zm-docs", null)
			byPath[PROJECTS_PATH]?.let { importProjects(it) }
			byPath[TASKS_PATH]?.let { importTasks(it) }
			val decisionFiles = byPath.filterKeys { DECISION_PATH.matches(it) }
			val imported = decisionFiles.mapNotNull { (path, content) -> importDecision(path, content) }
			resolveSupersession(imported)
		}

		// ------------------------------------------------------------ projects

		private fun importProjects(content: String) {
			val doc = parseJson(content, PROJECTS_PATH) ?: return
			val list = doc["projects"] as? List<*> ?: run {
				warnings += "$PROJECTS_PATH: projects 목록이 없다"
				return
			}
			for (p in list) {
				val m = p as? Map<*, *> ?: continue
				val ref = (m["id"] as? String)?.trim()
				val name = (m["name"] as? String)?.trim()?.take(200)
				if (ref == null || !PROJECT_REF.matches(ref) || name.isNullOrEmpty()) {
					warnings += "$PROJECTS_PATH: id 또는 name이 맞지 않는 프로젝트를 건너뛴다"
					continue
				}
				val existing = jdbc.sql("select id, name from projects where external_ref = :ref for update").param("ref", ref)
					.query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)
				when {
					existing == null -> {
						val id = Ids.project()
						jdbc.sql("insert into projects (id, org_id, external_ref, name) values (:id, :org, :ref, :name)")
							.param("id", id).param("org", orgId).param("ref", ref).param("name", name)
							.update()
						audit("project.imported", "prj", id, ref, emptyList())
						projects.created++
						changes += ImportChange("project", ref, "created")
					}
					existing.second != name -> {
						jdbc.sql("update projects set name = :name, updated_at = now() where id = :id")
							.param("name", name).param("id", existing.first).update()
						audit("project.synced", "prj", existing.first, ref, listOf("name"))
						projects.updated++
						changes += ImportChange("project", ref, "updated", listOf("name"))
					}
					else -> projects.unchanged++
				}
			}
		}

		// ------------------------------------------------------------ work items

		private fun importTasks(content: String) {
			val doc = parseJson(content, TASKS_PATH) ?: return
			val list = doc["tasks"] as? List<*> ?: run {
				warnings += "$TASKS_PATH: tasks 목록이 없다"
				return
			}
			for (t in list) {
				val m = t as? Map<*, *> ?: continue
				val ref = (m["id"] as? String)?.trim()
				if (ref == null || !TASK_REF.matches(ref)) {
					warnings += "$TASKS_PATH: id가 TSK-NNN이 아닌 작업을 건너뛴다"
					continue
				}
				val task = taskFields(ref, m) ?: continue
				upsertTask(ref, task)
			}
		}

		private fun taskFields(ref: String, m: Map<*, *>): Map<String, Any?>? {
			val title = (m["title"] as? String)?.trim()?.take(WorkItemService.TITLE_MAX)
			val state = (m["state"] as? String)?.trim()
			val stage = STATE_TO_STAGE[state]
			if (title.isNullOrEmpty() || stage == null) {
				warnings += "$ref: title이 없거나 state(${state ?: "없음"})를 알 수 없어 건너뛴다"
				return null
			}
			val blocked = state == "blocked"
			val blocker = (m["blocker"] as? String)?.trim()?.ifEmpty { null }?.take(WorkItemService.NOTE_MAX)
			val projectRef = (m["project"] as? String)?.trim()?.ifEmpty { null }
			val projectId = projectRef?.let { pr ->
				jdbc.sql("select id from projects where external_ref = :ref").param("ref", pr).query(String::class.java)
					.optional().orElse(null) ?: null.also { warnings += "$ref: 프로젝트 $pr 이(가) 없어 프로젝트 없이 가져온다" }
			}
			val closed = date(m["closed"], ref, "closed")
			return linkedMapOf(
				"title" to title,
				"stage" to stage,
				"wait_reasons" to if (blocked) listOf("waiting_other") else emptyList(),
				"wait_note" to if (blocked) blocker ?: "막힘(사유 없음)" else null,
				"project_id" to projectId,
				"next_action" to (m["next_action"] as? String)?.trim()?.ifEmpty { null }?.take(WorkItemService.TEXT_MAX),
				"source_ref" to (m["source"] as? String)?.trim()?.ifEmpty { null }?.take(500),
				"opened_on" to date(m["opened"], ref, "opened"),
				"due_on" to date(m["due"], ref, "due"),
				"closed_at" to if (stage in Workflow.CLOSED) closed?.atStartOfDay()?.atOffset(ZoneOffset.UTC) else null,
			)
		}

		private fun upsertTask(ref: String, fields: Map<String, Any?>) {
			val gitRef = json.writeValueAsString(gitRef(TASKS_PATH))
			val existing = jdbc.sql(
				"""
				select id, source_of_truth, git_ref ->> 'repo_key', title, stage, wait_reasons, wait_note, project_id,
				       next_action, source_ref, opened_on, due_on, closed_at
				  from work_items where external_ref = :ref for update
				""".trimIndent(),
			).param("ref", ref).query { rs, _ ->
				@Suppress("UNCHECKED_CAST")
				Triple(rs.getString(1), rs.getString(2) to rs.getString(3), linkedMapOf<String, Any?>(
					"title" to rs.getString(4), "stage" to rs.getString(5),
					"wait_reasons" to (rs.getArray(6).array as Array<String>).toList(), "wait_note" to rs.getString(7),
					"project_id" to rs.getString(8), "next_action" to rs.getString(9), "source_ref" to rs.getString(10),
					"opened_on" to rs.getObject(11, LocalDate::class.java), "due_on" to rs.getObject(12, LocalDate::class.java),
					"closed_at" to rs.getObject(13, OffsetDateTime::class.java),
				))
			}.optional().orElse(null)

			if (existing == null) {
				val id = Ids.workItem()
				jdbc.sql(
					"""
					insert into work_items (id, org_id, title, stage, wait_reasons, wait_note, responsible_user_id, executor_kind, executor_id,
					                        project_id, next_action, source_ref, opened_on, due_on, closed_at,
					                        external_ref, source_of_truth, git_ref)
					values (:id, :org, :title, :stage, cast(:waits as text[]), :wait_note, :owner, 'human', :owner,
					        :project_id, :next_action, :source_ref, :opened_on, :due_on, :closed_at,
					        :ref, 'git', cast(:git as jsonb))
					""".trimIndent(),
				).param("id", id).param("org", orgId).param("owner", person).param("ref", ref).param("git", gitRef)
					.also { stmt -> bindTask(stmt, fields) }.update()
				sessions.audit("person", person, "work_item.imported", "wrk", id, mapOf("ref" to ref, "repo_key" to repoKey))
				workItems.created++
				changes += ImportChange("work_item", ref, "created")
				return
			}
			val (id, source, current) = existing
			if (source.first != "git") {
				workItems.skipped++
				changes += ImportChange("work_item", ref, "skipped", note = "정본이 zm-baton으로 넘어온 작업이라 가져오지 않는다")
				return
			}
			if (source.second != repoKey) {
				workItems.skipped++
				changes += ImportChange("work_item", ref, "skipped", note = "다른 저장소에서 가져온 같은 번호가 있다")
				return
			}
			val changed = fields.keys.filter { !sameValue(fields[it], current[it]) }
			if (changed.isEmpty()) {
				workItems.unchanged++
				return
			}
			jdbc.sql(
				"""
				update work_items
				   set title = :title, stage = :stage, wait_reasons = cast(:waits as text[]), wait_note = :wait_note,
				       project_id = :project_id, next_action = :next_action, source_ref = :source_ref,
				       opened_on = :opened_on, due_on = :due_on, closed_at = :closed_at, git_ref = cast(:git as jsonb),
				       version = version + 1, updated_at = now()
				 where id = :id
				""".trimIndent(),
			).param("id", id).param("git", gitRef).also { stmt -> bindTask(stmt, fields) }.update()
			sessions.audit("person", person, "work_item.synced", "wrk", id, mapOf("ref" to ref, "fields" to changed))
			workItems.updated++
			changes += ImportChange("work_item", ref, "updated", changed)
		}

		private fun bindTask(stmt: JdbcClient.StatementSpec, f: Map<String, Any?>) {
			@Suppress("UNCHECKED_CAST")
			val waits = f["wait_reasons"] as List<String>
			stmt.param("title", f["title"]).param("stage", f["stage"]).param("waits", "{" + waits.joinToString(",") + "}")
				.param("wait_note", f["wait_note"]).param("project_id", f["project_id"]).param("next_action", f["next_action"])
				.param("source_ref", f["source_ref"]).param("opened_on", f["opened_on"]).param("due_on", f["due_on"])
				.param("closed_at", f["closed_at"])
		}

		// ------------------------------------------------------------ decisions

		private fun importDecision(path: String, content: String): Imported? {
			val fileRef = DECISION_PATH.matchEntire(path)!!.groupValues[1]
			val doc = DecisionDocument.parse(content)
			if (doc == null) {
				warnings += "$path: 머리말(---)이 없어 건너뛴다"
				return null
			}
			val ref = doc.front["id"]?.trim()
			if (ref != fileRef) {
				warnings += "$path: 머리말 id(${ref ?: "없음"})가 파일 이름과 달라 건너뛴다"
				return null
			}
			val question = doc.front["title"]?.trim()?.ifEmpty { null }
			val status = doc.front["status"]?.trim()
			if (question == null || status !in DecisionService.STATUSES) {
				warnings += "$path: title이 없거나 status(${status ?: "없음"})를 알 수 없어 건너뛴다"
				return null
			}
			val sha = sha256("zm-docs-decision-$DECISION_FORMAT\n$content")
			val projectIds = DecisionDocument.list(doc.front["projects"]).mapNotNull { pr ->
				jdbc.sql("select id from projects where external_ref = :ref").param("ref", pr).query(String::class.java)
					.optional().orElse(null) ?: null.also { warnings += "$ref: 프로젝트 $pr 이(가) 없어 연결하지 않는다" }
			}.distinct().sorted()
			val supersedes = DecisionDocument.ref(doc.front["supersedes"])
			val supersededBy = DecisionDocument.ref(doc.front["superseded_by"])
			val existing = jdbc.sql("select id, body_sha256 from decisions where external_ref = :ref for update").param("ref", ref)
				.query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)
			val currentProjects = existing?.let { e ->
				jdbc.sql("select project_id from decision_projects where decision_id = :id order by project_id").param("id", e.first)
					.query(String::class.java).list()
			}
			if (existing != null && existing.second == sha && currentProjects == projectIds) {
				decisions.unchanged++
				return Imported(existing.first, ref, supersedes, supersededBy)
			}
			val id = existing?.first ?: Ids.decision()
			val params = mapOf(
				"id" to id, "org" to orgId, "owner" to person, "ref" to ref, "question" to question.take(1000), "status" to status,
				"decided_on" to date(doc.front["date"], ref, "date"), "choice" to doc.choice(),
				"sections" to json.writeValueAsString(doc.sections), "git" to json.writeValueAsString(gitRef(path)),
				"sha" to sha,
			)
			if (existing == null) {
				jdbc.sql(
					"""
					insert into decisions (id, org_id, owner_user_id, external_ref, question, status, decided_on, choice, sections, git_ref, body_sha256)
					values (:id, :org, :owner, :ref, :question, :status, :decided_on, :choice, cast(:sections as jsonb), cast(:git as jsonb), :sha)
					""".trimIndent(),
				).params(params).update()
				audit("decision.imported", "dcs", id, ref, emptyList())
				if (status == "accepted") changedAccepted += id
				decisions.created++
				changes += ImportChange("decision", ref, "created")
			} else {
				jdbc.sql(
					"""
					update decisions
					   set question = :question, status = :status, decided_on = :decided_on, choice = :choice,
					       sections = cast(:sections as jsonb), git_ref = cast(:git as jsonb), body_sha256 = :sha, updated_at = now()
					 where id = :id
					""".trimIndent(),
				).params(params - "org" - "owner" - "ref").update()
				jdbc.sql("delete from decision_projects where decision_id = :id").param("id", id).update()
				val fields = if (existing.second == sha) listOf("projects") else listOf("content")
				if (status == "accepted" && existing.second != sha) changedAccepted += id
				audit("decision.synced", "dcs", id, ref, fields)
				decisions.updated++
				changes += ImportChange("decision", ref, "updated", fields)
			}
			for (p in projectIds) {
				jdbc.sql("insert into decision_projects (decision_id, project_id) values (:d, :p)").param("d", id).param("p", p).update()
			}
			return Imported(id, ref, supersedes, supersededBy)
		}

		/** After every file is in: `supersedes` and `superseded_by` name decisions by number. */
		private fun resolveSupersession(imported: List<Imported>) {
			for (d in imported) {
				val supersedesId = d.supersedes?.let { idOf(it, d.ref) }
				val supersededById = d.supersededBy?.let { idOf(it, d.ref) }
				jdbc.sql(
					"""
					update decisions set supersedes_id = :s, superseded_by_id = :b
					 where id = :id and (supersedes_id is distinct from :s or superseded_by_id is distinct from :b)
					""".trimIndent(),
				).param("s", supersedesId).param("b", supersededById).param("id", d.id).update()
			}
		}

		private fun idOf(ref: String, from: String): String? =
			jdbc.sql("select id from decisions where external_ref = :ref").param("ref", ref).query(String::class.java)
				.optional().orElse(null) ?: null.also { warnings += "$from: 대체 관계의 $ref 을(를) 찾지 못했다" }

		// ------------------------------------------------------------ helpers

		/** One audit row per project or decision copy this import creates or changes (a dry run rolls it back). */
		private fun audit(action: String, targetKind: String, id: String, ref: String, fields: List<String>) =
			sessions.audit(
				"person", person, action, targetKind, id,
				mapOf("ref" to ref, "repo_key" to repoKey, "commit" to commit, "fields" to fields),
			)

		/** Where a copy came from: the repository, the file and, when known, the commit read. */
		private fun gitRef(path: String): Map<String, String> =
			listOfNotNull("repo_key" to repoKey, "path" to path, commit?.let { "commit" to it }).toMap()

		private fun parseJson(content: String, path: String): Map<String, Any?>? =
			try {
				json.readValue(content, mapType)
			} catch (e: Exception) {
				warnings += "$path: JSON으로 읽지 못했다"
				null
			}

		private fun date(raw: Any?, ref: String, field: String): LocalDate? {
			val s = (raw as? String)?.trim()?.removeSurrounding("\"")?.ifEmpty { null } ?: return null
			if (s == "null") return null
			return try {
				LocalDate.parse(s)
			} catch (e: DateTimeParseException) {
				warnings += "$ref: $field($s)가 날짜가 아니라 비워 둔다"
				null
			}
		}
	}

	private fun sameValue(a: Any?, b: Any?): Boolean = when {
		a is OffsetDateTime && b is OffsetDateTime -> a.isEqual(b)
		else -> a == b
	}

	private fun sha256(s: String): String =
		MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/**
 * A zm-docs decision file: a front matter block between `---` lines (a small subset of YAML:
 * `key: value` and `[a, b]` lists) and second-level sections. Headings inside fenced code blocks
 * are not sections.
 */
data class DecisionDocument(val front: Map<String, String>, val sections: List<DecisionSection>) {

	fun section(heading: String): String? = sections.firstOrNull { it.heading == heading }?.body?.ifEmpty { null }

	/**
	 * The decision itself: the section "결정", or else the first section whose heading ends in
	 * "결정", as older documents join it with the criteria ("판단 기준과 결정").
	 */
	fun choice(): String? =
		section("결정") ?: sections.firstOrNull { it.heading.endsWith("결정") }?.body?.ifEmpty { null }

	companion object {
		private val KEY = Regex("^([A-Za-z_][A-Za-z0-9_]*):\\s*(.*)$")
		private val REF = Regex("DEC-[0-9]{4,}")

		fun parse(content: String): DecisionDocument? {
			val lines = content.replace("\r\n", "\n").split('\n')
			if (lines.firstOrNull()?.trim() != "---") return null
			val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
			if (end < 0) return null
			val front = linkedMapOf<String, String>()
			var lastKey: String? = null
			for (line in lines.subList(1, end + 1)) {
				val m = KEY.matchEntire(line)
				when {
					m != null -> {
						lastKey = m.groupValues[1]
						front[lastKey] = m.groupValues[2].trim()
					}
					// A multi-line list item belongs to the key above it: `projects:` then `  - PRJ-000`.
					line.trim().startsWith("- ") && lastKey != null ->
						front[lastKey] = (front[lastKey].orEmpty() + "," + line.trim().removePrefix("- ").trim()).trimStart(',')
				}
			}
			val sections = mutableListOf<DecisionSection>()
			var heading: String? = null
			val body = StringBuilder()
			var fenced = false
			for (line in lines.drop(end + 2)) {
				if (line.trimStart().startsWith("```")) fenced = !fenced
				if (!fenced && line.startsWith("## ")) {
					heading?.let { sections += DecisionSection(it, body.toString().trim()) }
					heading = line.removePrefix("## ").trim()
					body.clear()
				} else if (heading != null) {
					body.append(line).append('\n')
				}
			}
			heading?.let { sections += DecisionSection(it, body.toString().trim()) }
			return DecisionDocument(front, sections)
		}

		/** `[PRJ-000, PRJ-009]`, `PRJ-000` or a joined multi-line list, as values without quotes. */
		fun list(raw: String?): List<String> =
			raw.orEmpty().trim().removePrefix("[").removeSuffix("]").split(',')
				.map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
				.filter { it.isNotEmpty() && it != "null" }

		/** The first `DEC-NNNN` in a front matter value, or null for `null` and empty values. */
		fun ref(raw: String?): String? = raw?.let { REF.find(it)?.value }
	}
}

@RestController
@RequestMapping("/api")
class ZmDocsImportController(private val imports: ZmDocsImportService, private val permissions: Permissions) {

	/** `zm-baton import zm-docs` (with the device's token, or signed in): a dry run unless `apply` is true. */
	@Access(AccessKind.PERSON_OR_DEVICE)
	@PostMapping("/imports/zm-docs")
	fun import(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: ZmDocsImportIn,
	): ZmDocsImportOut {
		val me = permissions.personOrDevice(authorization, "import zm-docs", Act.IMPORT).first
		return imports.import(body, me.person!!)
	}
}
