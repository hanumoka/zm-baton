package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.Permissions
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// The export (FN-1404 "출처·권한을 담은 내보내기", G13.3): every record of the server as one JSON
// bundle, with where it came from. One snapshot: all collections are read in one repeatable-read
// transaction. Secrets are withheld (the session token hash). Personal records are the exporter's
// only (DEC-0103 2, G13.3): manager notes are never exported, another person's personal work items,
// decisions and documents are left out, and so is another person's unshared 「지금 하는 일」.
//
// Bundle format, version 1 (an outside promise: readers ignore fields they do not know, and a
// later version only adds):
//   format, format_version
//   provenance: exported_at, exported_by, server {name, version, commit},
//               database {migration_version, migrations[]}, access, source_of_truth,
//               withheld_fields {table: [columns]}, not_exported {table: why}
//   collections: {name: {table, source_of_truth, count, records[]}}
// Work items and decisions carry `source` per record: {of_truth: "zm-baton"} or
// {of_truth: "git", repo_key, path, commit, external_ref} for a copy whose source is a Git file.

@Service
class ExportService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	@Value("\${ZM_BATON_BUILD_COMMIT:unknown}") private val buildCommit: String,
	private val access: PermissionFacts,
) {
	companion object {
		const val FORMAT = "zm-baton.export"
		const val FORMAT_VERSION = 1

		/** Columns never exported, per table: secrets (G13.3). */
		val WITHHELD: Map<String, Set<String>> = mapOf("agent_sessions" to setOf("token_hash"))

		/** Tables not exported, and why. Every other table must be a collection (ExportTests checks it). */
		val NOT_EXPORTED: Map<String, String> = mapOf(
			"flyway_schema_history" to "provenance.database.migrations에 적는다",
			"manager_notes" to "매니저 기록의 개인 메모는 쓴 사람만 본다. 조직 관리자에게도 넘기지 않는다(DEC-0103 2항, G13.3)",
		)

		/** Rows left out per table: another person's personal records (`:me` is the exporter). */
		val PERSONAL_ROWS: Map<String, String> = mapOf(
			"work_items" to "t.visibility = 'personal' and t.responsible_user_id is distinct from :me",
			"decisions" to "t.visibility = 'personal' and t.owner_user_id is distinct from :me",
			"documents" to "t.visibility = 'personal' and t.owner_user_id is distinct from :me",
		)

		/** Collection name, table, order. The names are the export's, the tables the database's. */
		val COLLECTIONS: List<Triple<String, String, String>> = listOf(
			Triple("organizations", "organizations", "id"),
			// The person's own settings: focus mode, digest times and the first screen's visibility.
			Triple("users", "users", "id"),
			// The organisation's structure (FN-0101): roles, manager records (without their notes), teams,
			// products, repositories, environments, the links between them and the people of each project.
			Triple("memberships", "memberships", "org_id, user_id"),
			Triple("managers", "managers", "org_id, user_id"),
			Triple("teams", "teams", "created_at, id"),
			Triple("team_members", "team_members", "team_id, user_id"),
			Triple("products", "products", "created_at, id"),
			Triple("repositories", "repositories", "created_at, id"),
			Triple("environments", "environments", "created_at, id"),
			Triple("org_links", "org_links", "created_at, from_id, to_id"),
			Triple("project_members", "project_members", "project_id, user_id"),
			Triple("projects", "projects", "external_ref nulls last, id"),
			Triple("work_items", "work_items", "created_at, id"),
			Triple("decisions", "decisions", "decided_on nulls last, external_ref nulls last, id"),
			Triple("decision_projects", "decision_projects", "decision_id, project_id"),
			Triple("decision_work_items", "decision_work_items", "decision_id, work_item_id"),
			// Verdicts on decisions (and later on document versions): who approved what, under which rule.
			Triple("approvals", "approvals", "decided_at, id"),
			// Documents: a product document's versions carry its Markdown body; a Git document's carry a commit.
			Triple("documents", "documents", "created_at, id"),
			Triple("document_versions", "document_versions", "doc_id, number"),
			Triple("document_work_items", "document_work_items", "document_id, work_item_id"),
			Triple("document_decisions", "document_decisions", "document_id, decision_id"),
			Triple("devices", "devices", "created_at, id"),
			// AI accounts are references: kind, purpose, affiliation, terms, limits and where the device keeps
			// the sign-in (`device_login` or `env:ANTHROPIC_API_KEY`). No key or login value is ever stored.
			Triple("ai_accounts", "ai_accounts", "created_at, id"),
			// Remotes are stored cleaned (DelegationService.addRepository: SessionService.cleanRemote
			// drops user info, query and fragment, and local paths), so no credential is exported.
			Triple("device_repositories", "device_repositories", "device_id, repo_key"),
			Triple("sessions", "agent_sessions", "created_at, id"),
			Triple("session_events", "session_events", "received_at, event_id"),
			Triple("inbox_items", "inbox_items", "created_at, id"),
			Triple("notices", "notices", "created_at, id"),
			// Delegation scopes and run workspaces hold repository keys, branches and tool names, never a path.
			Triple("delegations", "delegations", "created_at, id"),
			Triple("runs", "runs", "created_at, id"),
			// Instructions a person sent to a running executor: the person's own words, no secret by contract.
			Triple("run_inputs", "run_inputs", "created_at, id"),
			Triple("run_events", "run_events", "received_at, event_id"),
			// Who had to see which result and what they decided; the evidence holds patterns, paths and draws.
			Triple("review_items", "review_items", "created_at, id"),
			Triple("work_item_links", "work_item_links", "created_at, from_id, to_id"),
			// What executors said runs used and the provider's limit signals: counts, ratios and estimates, no secret.
			Triple("usage_signals", "usage_signals", "received_at, id"),
			// Questions with their answers and routing: what people and runs asked, in their own words.
			Triple("questions", "questions", "created_at, id"),
			Triple("audit_log", "audit_log", "at, id"),
		)

		private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
	}

	private val rowsType = object : TypeReference<List<LinkedHashMap<String, Any?>>>() {}

	/** The server's version from its jar (`Implementation-Version`), or `unknown` outside a jar. */
	private val serverVersion: String = ServerApplication::class.java.`package`?.implementationVersion ?: "unknown"

	/** Everything [exporter]'s organisation keeps, as [exporter] may take it. */
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	fun export(exporter: String = Requester.STAGE1_PERSON): Map<String, Any?> {
		// Times in the bundle are UTC whatever the server's zone is.
		jdbc.sql("set local time zone 'UTC'").update()
		val exportedAt = jdbc.sql("select now()").query(OffsetDateTime::class.java).single().withOffsetSameInstant(ZoneOffset.UTC)
		val migrations = jdbc.sql(
			"""
			select version, description, script, checksum, to_json(installed_on)::text, success from flyway_schema_history
			 order by installed_rank
			""".trimIndent(),
		).query { rs, _ ->
			linkedMapOf<String, Any?>(
				"version" to rs.getString(1), "description" to rs.getString(2), "script" to rs.getString(3),
				// Flyway keeps the install time without a zone; it is shown as stored.
				"checksum" to rs.getObject(4), "installed_on" to rs.getString(5)?.trim('"'),
				"success" to rs.getBoolean(6),
			)
		}.list()
		val collections = linkedMapOf<String, Any?>()
		// Sessions whose unshared summaries are another person's: their summaries stay out.
		val othersPrivate = mutableSetOf<String>()
		for ((name, table, order) in COLLECTIONS) {
			val withheld = WITHHELD[table].orEmpty()
			val leftOut = PERSONAL_ROWS[table]
			// Table, order and filter come from the fixed lists above, never from input.
			val stmt = jdbc.sql("select coalesce(json_agg(t order by $order), '[]')::text from $table t" + (leftOut?.let { " where not ($it)" } ?: ""))
			if (leftOut != null) stmt.param("me", exporter)
			val raw = stmt.query(String::class.java).single()
			val records = json.readValue(raw, rowsType).onEach { row ->
				withheld.forEach { row.remove(it) }
				if (table == "work_items" || table == "decisions") row["source"] = source(row)
				if (table == "agent_sessions" && row["current_work_visibility"] == "personal" && row["user_id"] != exporter) {
					othersPrivate += row["id"] as String
					row["current_work"] = null
					row["current_work_refs"] = null
				}
				if (table == "session_events" && row["kind"] == "current_work" && row["session_id"] in othersPrivate) {
					@Suppress("UNCHECKED_CAST")
					val payload = row["payload"] as? MutableMap<String, Any?>
					if (payload?.get("share") == false) {
						payload.remove("summary")
						payload.remove("refs")
					}
				}
			}
			collections[name] = linkedMapOf(
				"table" to table,
				"source_of_truth" to if (table == "work_items" || table == "decisions") "per record: source" else "zm-baton",
				"count" to records.size,
				"records" to records,
			)
		}
		return linkedMapOf(
			"format" to FORMAT,
			"format_version" to FORMAT_VERSION,
			"provenance" to linkedMapOf(
				"exported_at" to exportedAt.toString(),
				"exported_by" to exporter,
				"server" to linkedMapOf("name" to "zm-baton-server", "version" to serverVersion, "commit" to buildCommit),
				"database" to linkedMapOf(
					"migration_version" to migrations.lastOrNull { it["success"] == true && it["version"] != null }?.get("version"),
					"migrations" to migrations,
				),
				"access" to linkedMapOf(
					"model" to "organization",
					"organization" to access.orgOf(exporter),
					"people" to jdbc.sql("select user_id from memberships where org_id = :org order by created_at, user_id")
						.param("org", access.orgOf(exporter)).query(String::class.java).list(),
					"note" to "내보낸 사람이 운영자 역할로 내보냈다. 개인 범위 기록은 그 사람 것만 담고, 매니저 기록의 개인 메모는 담지 않는다" +
						"(DEC-0103). 1단계 서버는 이 PC(127.0.0.1)에서만 받는다",
				),
				"source_of_truth" to "zm-baton 서버의 데이터베이스. 작업과 결정은 기록마다 source 칸이 정본 위치를 적는다. " +
					"source.of_truth가 git이면 그 저장소·파일·커밋의 Git 파일이 정본이고 이 기록은 가져온 복사본이다",
				"withheld_fields" to WITHHELD.mapValues { it.value.sorted() },
				"not_exported" to NOT_EXPORTED,
			),
			"collections" to collections,
		)
	}

	/** A file name for the bundle, from its export time. */
	fun fileName(bundle: Map<String, Any?>): String {
		@Suppress("UNCHECKED_CAST")
		val at = OffsetDateTime.parse((bundle["provenance"] as Map<String, Any?>)["exported_at"] as String)
		return "zm-baton-export-${STAMP.format(at.withOffsetSameInstant(ZoneOffset.UTC))}.json"
	}

	/** Counts per collection, for the audit row of an export. */
	@Suppress("UNCHECKED_CAST")
	fun counts(bundle: Map<String, Any?>): Map<String, Any?> =
		(bundle["collections"] as Map<String, Map<String, Any?>>).mapValues { it.value["count"] }

	/** Where a work item or decision record is kept: here, or a Git file it was imported from. */
	private fun source(row: Map<String, Any?>): Map<String, Any?> {
		if (row["source_of_truth"] != "git") return mapOf("of_truth" to "zm-baton")
		@Suppress("UNCHECKED_CAST")
		val git = row["git_ref"] as? Map<String, Any?> ?: emptyMap()
		return linkedMapOf(
			"of_truth" to "git", "repo_key" to git["repo_key"], "path" to git["path"], "commit" to git["commit"],
			"external_ref" to row["external_ref"],
		)
	}
}

@RestController
@RequestMapping("/api")
class ExportController(
	private val exports: ExportService,
	private val permissions: Permissions,
	private val sessions: SessionService,
) {

	/** `zm-baton export --out <file>` and a browser download. A person action, written to the audit log. */
	@GetMapping("/export", produces = [MediaType.APPLICATION_JSON_VALUE])
	fun export(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): ResponseEntity<Map<String, Any?>> {
		val me = permissions.person(authorization, "export", Act.EXPORT)
		val bundle = exports.export(me.person!!)
		sessions.audit(
			"person", me.person!!, "export.downloaded", null, null,
			mapOf("format_version" to ExportService.FORMAT_VERSION, "counts" to exports.counts(bundle)),
		)
		val disposition = ContentDisposition.attachment().filename(exports.fileName(bundle)).build()
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString()).body(bundle)
	}
}
