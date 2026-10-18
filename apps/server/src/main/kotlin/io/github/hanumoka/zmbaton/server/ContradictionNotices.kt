package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.RecordVisibility
import io.github.hanumoka.zmbaton.server.permission.Permissions
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Contradiction candidates as notices (contract v1 "문서 모순 후보" 3-4, "알림 처리"), and the reads
// the document and decision screens use. A candidate is a `notices` row of kind `doc_contradiction`
// whose evidence holds the two sides: which document version or decision, and the quoted sentence.
// The same two items with the same two sentences are the same evidence (G05.2): a repeat only counts
// up, a resolved one opens again, and "acknowledged" or "not a contradiction" stay as judged. It goes
// to the first screen in bucket 5 「나머지 알림」, alerted with the digest (contract "알림과 집중 모드" 2).
// A person judges it the way a conflict is judged: POST /api/notices/{id}/status (InboxApi.kt).

data class ContradictionSide(
	/** `document` or `decision`. */
	val kind: String,
	val id: String,
	@JsonProperty("version_id") val versionId: String?,
	val number: Int?,
	val ref: String?,
	val title: String?,
	val label: String?,
	val quote: String?,
)

data class ContradictionView(
	val id: String,
	/** `open`, `acknowledged`, `not_a_conflict` or `resolved`. */
	val status: String,
	val reason: String?,
	val sides: List<ContradictionSide>,
	@JsonProperty("check_id") val checkId: String?,
	@JsonProperty("run_id") val runId: String?,
	/** The master's work item whose run found it: its run record is there. */
	@JsonProperty("work_item_id") val workItemId: String?,
	/** `api` or `local`: which path the master ran on. */
	val path: String?,
	@JsonProperty("repeat_count") val repeatCount: Int,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("last_seen_at") val lastSeenAt: OffsetDateTime,
	@JsonProperty("status_by") val statusBy: String?,
	@JsonProperty("status_at") val statusAt: OffsetDateTime?,
	@JsonProperty("status_note") val statusNote: String?,
	/** The open first-screen item for it, when there is one. */
	@JsonProperty("inbox_item_id") val inboxItemId: String?,
)

data class MasterCheckView(
	val id: String,
	val trigger: String,
	/** `requested`, `running`, `done`, `failed` or `skipped`. */
	val state: String,
	/** `api`, `local`, or null when skipped before a path. */
	val path: String?,
	val reason: String?,
	val subjects: List<Map<String, Any?>>,
	/** What it was compared with: key, kind, id, label and whether it was linked rather than found by search. */
	val compared: List<Map<String, Any?>>,
	@JsonProperty("work_item_id") val workItemId: String?,
	@JsonProperty("run_id") val runId: String?,
	@JsonProperty("api_run_id") val apiRunId: String?,
	val outcome: Map<String, Any?>?,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("finished_at") val finishedAt: OffsetDateTime?,
)

data class ContradictionsAbout(
	/** Candidates about it that are open or acknowledged, newest first. */
	val open: List<ContradictionView>,
	/** How many were judged not a contradiction or resolved. */
	@JsonProperty("closed_count") val closedCount: Int,
	/** Its newest checks, newest first. */
	val checks: List<MasterCheckView>,
)

/** The master's settings and today's use, for the screens (read-only: they are server settings). */
data class MasterSettingsView(
	@JsonProperty("daily_limit") val dailyLimit: Int,
	@JsonProperty("used_today") val usedToday: Int,
	@JsonProperty("compare_limit") val compareLimit: Int,
	@JsonProperty("import_limit") val importLimit: Int,
	/** True only when the server's key variable is set (MasterApiSettings). */
	@JsonProperty("api_path") val apiPath: Boolean,
	/** The name of the variable the API path reads its key from; never a value. */
	@JsonProperty("api_key_env") val apiKeyEnv: String,
	@JsonProperty("screen_model") val screenModel: String,
	@JsonProperty("confirm_model") val confirmModel: String,
	@JsonProperty("run_limits") val runLimits: Map<String, Any?>,
)

@Service
class ContradictionNotices(private val jdbc: JdbcClient, private val json: ObjectMapper, private val sessions: SessionService,
	private val visibility: RecordVisibility) {

	companion object {
		const val CHECKS_SHOWN = 5
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val listType = object : TypeReference<List<Map<String, Any?>>>() {}

	/** Raises (or repeats) the notice of one verified candidate; returns its id. */
	fun raise(checkId: String, runId: String, workItemId: String?, path: String, c: Candidate): String {
		val evidence = linkedMapOf(
			"notice_kind" to "doc_contradiction", "check_id" to checkId, "run_id" to runId, "work_item_id" to workItemId,
			// Not `path`: a conflict's evidence uses that key for the file, and the first screen reads it so.
			"master_path" to path,
			"reason" to c.reason,
			"sides" to listOf(side(c.left, c.leftQuote), side(c.right, c.rightQuote)),
		)
		val evidenceJson = json.writeValueAsString(evidence)
		val key = ContradictionPrompt.dedupeKey(c)
		val existing = jdbc.sql("select id, status from notices where dedupe_key = :key for update").param("key", key)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)
		val details = mapOf("kind" to "doc_contradiction", "check_id" to checkId, "sides" to listOf(c.left.id, c.right.id))
		val id: String
		if (existing == null) {
			id = Ids.notice()
			jdbc.sql(
				"""
				insert into notices (id, kind, evidence, dedupe_key, recipients, source_run_id)
				values (:id, 'doc_contradiction', cast(:e as jsonb), :key, cast(:to as text[]), :run)
				""".trimIndent(),
			).param("id", id).param("e", evidenceJson).param("key", key).param("to", "{" + recipients(c).joinToString(",") + "}")
				.param("run", runId).update()
			sessions.audit("system", MasterCheckService.ACTOR, "notice.raised", "ntc", id, details)
		} else {
			id = existing.first
			jdbc.sql(
				"""
				update notices
				   set repeat_count = repeat_count + 1, last_seen_at = now(), evidence = cast(:e as jsonb), source_run_id = :run,
				       status = case when status = 'resolved' then 'open' else status end,
				       status_by = case when status = 'resolved' then null else status_by end,
				       status_at = case when status = 'resolved' then null else status_at end,
				       status_note = case when status = 'resolved' then null else status_note end
				 where id = :id
				""".trimIndent(),
			).param("e", evidenceJson).param("run", runId).param("id", id).update()
			if (existing.second == "resolved") sessions.audit("system", MasterCheckService.ACTOR, "notice.reopened", "ntc", id, details)
			if (existing.second != "open" && existing.second != "resolved") return id
		}
		jdbc.sql(
			"""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, detail, notify)
			values (:id, :user, 5, 'notice', 'ntc', :notice, :summary, cast(:detail as jsonb), 'digest')
			on conflict (source_id) where state = 'open' and kind = 'notice' do nothing
			""".trimIndent(),
		).param("id", Ids.inbox()).param("user", recipients(c).first()).param("notice", id)
			.param("summary", "모순 후보: ${c.left.label}, ${c.right.label}".take(300)).param("detail", evidenceJson).update()
		return id
	}

	private fun side(item: MaterialItem, quote: String) = linkedMapOf(
		"kind" to item.kind, "id" to item.id, "version_id" to item.versionId, "number" to item.number, "ref" to item.ref,
		"title" to item.title, "label" to item.label, "quote" to quote,
	)

	/**
	 * The owners of the two items (contract "문서 모순 후보" 4): a document's owner, a decision's driver
	 * when the driver is a person of the organisation, else the decision's owner (V13). With neither,
	 * the owner of the items' organisation. The first one gets the notice's first-screen item.
	 */
	private fun recipients(c: Candidate): List<String> {
		val people = listOf(c.left, c.right).mapNotNull { item ->
			if (item.kind == "document") {
				jdbc.sql("select owner_user_id from documents where id = :id").param("id", item.id).query(String::class.java).optional().orElse(null)
			} else {
				jdbc.sql(
					"""
					select case when exists (select 1 from memberships m where m.org_id = d.org_id and m.user_id = d.roles ->> 'driver')
					            then d.roles ->> 'driver' else d.owner_user_id end
					  from decisions d where d.id = :id
					""".trimIndent(),
				).param("id", item.id).query(String::class.java).optional().orElse(null)
			}
		// Person ids are `usr_<uuidv7>` (dashes included); the pattern keeps the array literal below safe.
		}.filter { it.matches(Regex("^[A-Za-z0-9_-]{1,80}$")) }.distinct()
		return people.ifEmpty { listOf(orgOwner(c)) }
	}

	/** The owner of the organisation the two items belong to. */
	private fun orgOwner(c: Candidate): String =
		listOf(c.left, c.right).firstNotNullOfOrNull { item ->
			jdbc.sql(
				"select o.owner_user_id from organizations o join ${if (item.kind == "document") "documents" else "decisions"} x on x.org_id = o.id where x.id = :id",
			).param("id", item.id).query(String::class.java).optional().orElse(null)
		} ?: jdbc.sql("select owner_user_id from organizations order by created_at, id limit 1").query(String::class.java).single()

	// ---------------------------------------------------------------- reads

	/** Candidates and checks about one document (`doc_`) or decision (`dcs_`). */
	fun about(id: String, includeClosed: Boolean, viewer: Actor? = null): ContradictionsAbout {
		val reader = viewer?.let { visibility.reader(it) }
		val side = json.writeValueAsString(listOf(mapOf("id" to id)))
		val all = jdbc.sql(
			"""
			select n.id, n.status, n.evidence::text, n.repeat_count, n.created_at, n.last_seen_at, n.status_by, n.status_at, n.status_note,
			       (select i.id from inbox_items i where i.source_kind = 'ntc' and i.source_id = n.id and i.state = 'open' limit 1)
			  from notices n
			 where n.kind = 'doc_contradiction' and n.evidence -> 'sides' @> cast(:side as jsonb)
			 order by n.last_seen_at desc, n.id desc limit 200
			""".trimIndent(),
		).param("side", side).query { rs, _ -> view(rs) }.list().filter { reader?.allows(it.id) != false }
		val open = all.filter { includeClosed || it.status in setOf("open", "acknowledged") }
		val checks = jdbc.sql(
			"""
			select $CHECK_COLUMNS from master_checks
			 where subjects @> cast(:side as jsonb) or subjects @> cast(:doc as jsonb)
			 order by created_at desc, id desc limit :n
			""".trimIndent(),
		).param("side", side).param("doc", json.writeValueAsString(listOf(mapOf("document_id" to id)))).param("n", CHECKS_SHOWN)
			.query { rs, _ -> check(rs) }.list().filter { reader?.allows(it.id) != false }
		return ContradictionsAbout(open, all.count { it.status !in setOf("open", "acknowledged") }, checks)
	}

	fun check(id: String): MasterCheckView =
		jdbc.sql("select $CHECK_COLUMNS from master_checks where id = :id").param("id", id).query { rs, _ -> check(rs) }.optional()
			.orElseThrow { NotFoundException("no master check $id") }

	private val CHECK_COLUMNS = """
		id, trigger, state, path, reason, subjects::text, material::text, work_item_id, run_id, api_run_id, outcome::text, created_at, finished_at
	""".trimIndent()

	private fun view(rs: java.sql.ResultSet): ContradictionView {
		val e = json.readValue(rs.getString(3), mapType)
		val sides = (e["sides"] as? List<*>).orEmpty().mapNotNull { s ->
			val m = s as? Map<*, *> ?: return@mapNotNull null
			ContradictionSide(m["kind"] as? String ?: "", m["id"] as? String ?: "", m["version_id"] as? String, (m["number"] as? Number)?.toInt(),
				m["ref"] as? String, m["title"] as? String, m["label"] as? String, m["quote"] as? String)
		}
		return ContradictionView(
			rs.getString(1), rs.getString(2), e["reason"] as? String, sides, e["check_id"] as? String, e["run_id"] as? String,
			e["work_item_id"] as? String, e["master_path"] as? String, rs.getInt(4),
			rs.getObject(5, OffsetDateTime::class.java), rs.getObject(6, OffsetDateTime::class.java), rs.getString(7),
			rs.getObject(8, OffsetDateTime::class.java), rs.getString(9), rs.getString(10),
		)
	}

	private fun check(rs: java.sql.ResultSet): MasterCheckView {
		val material = json.readValue(rs.getString(7), listType)
		return MasterCheckView(
			rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), json.readValue(rs.getString(6), listType),
			// The texts stay in the table; the screen needs only which items they were.
			material.filter { (it["key"] as? String)?.startsWith("C") == true }.map { m ->
				linkedMapOf("key" to m["key"], "kind" to m["kind"], "id" to m["id"], "version_id" to m["version_id"], "label" to m["label"],
					"linked" to m["linked"], "cut" to m["cut"])
			},
			rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)?.let { json.readValue(it, mapType) },
			rs.getObject(12, OffsetDateTime::class.java), rs.getObject(13, OffsetDateTime::class.java),
		)
	}
}

@RestController
@RequestMapping("/api")
class ContradictionController(
	private val notices: ContradictionNotices,
	private val checks: MasterCheckService,
	private val settings: MasterSettings,
	private val api: MasterApiSettings,
	private val permissions: Permissions,
	private val visibility: RecordVisibility,
) {

	/** `GET /api/contradictions?about=doc_…` (or `dcs_…`): its open candidates and its newest checks. */
	@GetMapping("/contradictions")
	fun about(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam about: String,
		@RequestParam(name = "include_closed", defaultValue = "false") includeClosed: Boolean,
	): ContradictionsAbout {
		val me = permissions.person(authorization, "read contradiction candidates", Act.READ)
		val id = about.trim()
		if (!(id.startsWith("doc_") || id.startsWith("dcs_")) || id.length > 100) throw BadRequestException("about must be a document (doc_…) or decision (dcs_…)")
		permissions.see(me, if (id.startsWith("doc_")) permissions.document(id) else permissions.decision(id))
		return notices.about(id, includeClosed, me)
	}

	@GetMapping("/master-checks/{id}")
	fun check(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@org.springframework.web.bind.annotation.PathVariable id: String,
	): MasterCheckView {
		val me = permissions.person(authorization, "read a master check", Act.READ)
		if (!visibility.reader(me).allows(id)) throw NotFoundException("no master check $id")
		return notices.check(id)
	}

	/** The master's limits, today's use and whether the API path is on. Read-only: these are server settings. */
	@GetMapping("/settings/master")
	fun settings(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): MasterSettingsView {
		permissions.person(authorization, "read the master settings", Act.READ)
		return MasterSettingsView(
			settings.dailyLimit, checks.usedToday(), settings.compareLimit, settings.importLimit, api.enabled(), api.keyEnv,
			api.screenModel, api.confirmModel,
			mapOf("max_turns" to settings.maxTurns, "max_minutes" to settings.maxMinutes, "max_budget_usd" to settings.maxBudgetUsd),
		)
	}
}
