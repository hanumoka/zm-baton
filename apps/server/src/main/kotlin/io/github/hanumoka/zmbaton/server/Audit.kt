package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Permissions
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
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// Reading the audit log (FN-1404 "감사 기록", data model "audit_log"). Rows are written in one
// place, SessionService.audit, by every request that changes the server's state; this file only
// reads them. Details never hold a secret: callers put ids, counts and short reasons there.
//
// Added with the audit screen, all read-only and additive: each row's actor class (`run` is the
// system acting as one run), the work item a run or master check target belongs to, filters by
// actor class and from a time on, one row by id, and the counts the screen's filter chips show.

data class AuditEntry(
	val id: String,
	@JsonProperty("actor_kind") val actorKind: String,
	@JsonProperty("actor_id") val actorId: String,
	@JsonProperty("on_behalf_of_user_id") val onBehalfOfUserId: String?,
	val action: String,
	@JsonProperty("target_kind") val targetKind: String?,
	@JsonProperty("target_id") val targetId: String?,
	val details: Map<String, Any?>,
	val at: OffsetDateTime,
	/**
	 * Added with the audit screen: `person`, `session`, `run` (actor_kind `system` acting as a run,
	 * `actor_id` `run_…`) or `system` (the server, a device's connector, the master, retention).
	 */
	@JsonProperty("actor_class") val actorClass: String = AuditService.actorClass(actorKind, actorId),
	/** Added with the audit screen: the work item of a `run` or `mck` target, so a screen can link to its run record. */
	@JsonProperty("target_work_item_id") val targetWorkItemId: String? = null,
)

/** How many rows each actor class and target kind has under the other filters (the screen's chips). */
data class AuditFacets(
	val total: Long,
	@JsonProperty("actor_classes") val actorClasses: Map<String, Long>,
	/** Keyed by target kind; rows without a target are counted under `none`. */
	@JsonProperty("target_kinds") val targetKinds: Map<String, Long>,
)

/**
 * Filters of [AuditService.list]. Every present field narrows the list. [action] matches exactly,
 * or, when it ends in `.`, every action that starts with it (`notice.` finds `notice.raised` and
 * `notice.resolved`). [before] is an audit id (the last row of the previous page) or a time.
 */
data class AuditQuery(
	val targetKind: String? = null,
	val targetId: String? = null,
	val actorKind: String? = null,
	val actorId: String? = null,
	val action: String? = null,
	val before: String? = null,
	val limit: Int = AuditService.DEFAULT_LIMIT,
	/** Added with the audit screen: `person`, `session`, `run` or `system` ([AuditEntry.actorClass]). */
	val actorClass: String? = null,
	/** Added with the audit screen: rows at or after this time. */
	val after: String? = null,
	/** One row by id ([AuditService.one]). */
	val id: String? = null,
)

@Service
class AuditService(private val jdbc: JdbcClient, private val json: ObjectMapper) {

	companion object {
		const val DEFAULT_LIMIT = 100
		const val MAX_LIMIT = 500
		val ACTOR_KINDS = setOf("person", "session", "system")
		val ACTOR_CLASSES = listOf("person", "session", "run", "system")
		private val TARGET_KIND = Regex("^[a-z_]{1,20}$")

		fun actorClass(kind: String, id: String): String = if (kind == "system" && id.startsWith("run_")) "run" else kind

		/** The same in SQL, for filtering and counting. */
		private const val CLASS_SQL = "(case when a.actor_kind = 'system' and left(a.actor_id, 4) = 'run_' then 'run' else a.actor_kind end)"
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	/** Newest first. Rows of one transaction share `at`, so the id breaks ties and pages stay stable. */
	fun list(q: AuditQuery): List<AuditEntry> {
		val (where, params) = conditions(q)
		val stmt = jdbc.sql(
			"""
			select a.id, a.actor_kind, a.actor_id, a.on_behalf_of_user_id, a.action, a.target_kind, a.target_id, a.details::text, a.at,
			       case a.target_kind
			         when 'run' then (select r.work_item_id from runs r where r.id = a.target_id)
			         when 'mck' then (select c.work_item_id from master_checks c where c.id = a.target_id)
			       end
			  from audit_log a where ${where.joinToString(" and ")}
			 order by a.at desc, a.id desc limit :limit
			""".trimIndent(),
		).param("limit", q.limit.coerceIn(1, MAX_LIMIT))
		params.forEach { (k, v) -> stmt.param(k, v) }
		return stmt.query { rs, _ -> entry(rs) }.list()
	}

	/** One row by its id, for a screen opened on a row that is not on the page it read. */
	fun one(id: String): AuditEntry = list(AuditQuery(limit = 1, id = id)).firstOrNull() ?: throw NotFoundException("no audit row $id")

	/**
	 * The counts of the screen's chips: each actor class under the other filters but not the actor
	 * class, each target kind under the other filters but not the target kind, and the total.
	 */
	fun facets(q: AuditQuery): AuditFacets {
		fun counts(query: AuditQuery, column: String): Map<String, Long> {
			val (where, params) = conditions(query)
			val stmt = jdbc.sql(
				"select coalesce($column, 'none'), count(*) from audit_log a where ${where.joinToString(" and ")} group by 1 order by 2 desc, 1",
			)
			params.forEach { (k, v) -> stmt.param(k, v) }
			return stmt.query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()
		}
		val classes = counts(q.copy(actorClass = null), CLASS_SQL)
		val kinds = counts(q.copy(targetKind = null), "a.target_kind")
		val (where, params) = conditions(q)
		val stmt = jdbc.sql("select count(*) from audit_log a where ${where.joinToString(" and ")}")
		params.forEach { (k, v) -> stmt.param(k, v) }
		return AuditFacets(stmt.query(Long::class.javaObjectType).single(), ACTOR_CLASSES.associateWith { classes[it] ?: 0L }, kinds)
	}

	private fun entry(rs: java.sql.ResultSet) = AuditEntry(
		rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
		rs.getString(7), json.readValue(rs.getString(8), mapType), rs.getObject(9, OffsetDateTime::class.java),
		targetWorkItemId = rs.getString(10),
	)

	/** The where clause of [q] and its parameters. Column names are the fixed ones here, never input. */
	private fun conditions(q: AuditQuery): Pair<List<String>, Map<String, Any?>> {
		val where = mutableListOf("true")
		val params = linkedMapOf<String, Any?>()
		fun eq(column: String, value: String?) {
			val v = value?.trim()?.ifEmpty { null } ?: return
			where += "a.$column = :$column"
			params[column] = v
		}
		q.actorKind?.trim()?.ifEmpty { null }?.let {
			if (it !in ACTOR_KINDS) throw BadRequestException("actor_kind must be one of $ACTOR_KINDS")
		}
		eq("id", q.id)
		val kind = q.targetKind?.trim()?.ifEmpty { null }
		when {
			kind == null -> {}
			kind == "none" -> where += "a.target_kind is null"
			TARGET_KIND.matches(kind) -> eq("target_kind", kind)
			else -> throw BadRequestException("target_kind is not a kind")
		}
		eq("target_id", q.targetId)
		eq("actor_kind", q.actorKind)
		eq("actor_id", q.actorId)
		q.action?.trim()?.ifEmpty { null }?.let { action ->
			if (action.endsWith(".")) {
				where += "a.action like :action escape '#'"
				params["action"] = action.replace("#", "##").replace("%", "#%").replace("_", "#_") + "%"
			} else {
				where += "a.action = :action"
				params["action"] = action
			}
		}
		q.actorClass?.trim()?.ifEmpty { null }?.let {
			if (it !in ACTOR_CLASSES) throw BadRequestException("actor_class must be one of $ACTOR_CLASSES")
			where += "$CLASS_SQL = :actor_class"
			params["actor_class"] = it
		}
		q.after?.trim()?.ifEmpty { null }?.let { after ->
			where += "a.at >= :after_at"
			params["after_at"] = time(after, "after")
		}
		q.before?.trim()?.ifEmpty { null }?.let { before ->
			if (before.startsWith("aud_")) {
				val at = jdbc.sql("select at from audit_log where id = :id").param("id", before)
					.query(OffsetDateTime::class.java).optional()
					.orElseThrow { BadRequestException("no audit row $before to page from") }
				where += "(a.at, a.id) < (:before_at, :before_id)"
				params["before_at"] = at
				params["before_id"] = before
			} else {
				where += "a.at < :before_at"
				params["before_at"] = time(before, "before")
			}
		}
		return where to params
	}

	private fun time(raw: String, name: String): OffsetDateTime =
		try {
			OffsetDateTime.parse(raw)
		} catch (e: DateTimeParseException) {
			throw BadRequestException(
				if (name == "before") "before must be an audit id (aud_…) or a time like 2026-10-14T09:00:00+09:00"
				else "$name must be a time like 2026-10-14T09:00:00+09:00",
			)
		}
}

@RestController
@RequestMapping("/api")
class AuditController(private val audit: AuditService, private val permissions: Permissions) {

	/** `GET /api/audit?target_kind=wrk&target_id=wrk_…&limit=50`; the next page passes `before=<last id>`. */
	@GetMapping("/audit")
	fun list(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(name = "target_kind", required = false) targetKind: String?,
		@RequestParam(name = "target_id", required = false) targetId: String?,
		@RequestParam(name = "actor_kind", required = false) actorKind: String?,
		@RequestParam(name = "actor_id", required = false) actorId: String?,
		@RequestParam(required = false) action: String?,
		@RequestParam(required = false) before: String?,
		@RequestParam(defaultValue = "100") limit: Int,
		@RequestParam(name = "actor_class", required = false) actorClass: String?,
		@RequestParam(required = false) after: String?,
	): List<AuditEntry> {
		permissions.person(authorization, "read the audit log", Act.AUDIT_READ)
		return audit.list(AuditQuery(targetKind, targetId, actorKind, actorId, action, before, limit, actorClass, after))
	}

	/** `GET /api/audit/facets?after=…&actor_class=…&target_kind=…`: the counts the audit screen's chips show. */
	@GetMapping("/audit/facets")
	fun facets(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(name = "target_kind", required = false) targetKind: String?,
		@RequestParam(name = "target_id", required = false) targetId: String?,
		@RequestParam(name = "actor_class", required = false) actorClass: String?,
		@RequestParam(required = false) after: String?,
	): AuditFacets {
		permissions.person(authorization, "read the audit log", Act.AUDIT_READ)
		return audit.facets(AuditQuery(targetKind = targetKind, targetId = targetId, actorClass = actorClass, after = after))
	}

	@GetMapping("/audit/{id}")
	fun one(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): AuditEntry {
		permissions.person(authorization, "read the audit log", Act.AUDIT_READ)
		return audit.one(id)
	}
}
