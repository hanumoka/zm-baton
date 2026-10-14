package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.permission.Act
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
import java.time.format.DateTimeParseException

// Reading the audit log (FN-1404 "감사 기록", data model "audit_log"). Rows are written in one
// place, SessionService.audit, by every request that changes the server's state; this file only
// reads them. Details never hold a secret: callers put ids, counts and short reasons there.

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
)

@Service
class AuditService(private val jdbc: JdbcClient, private val json: ObjectMapper) {

	companion object {
		const val DEFAULT_LIMIT = 100
		const val MAX_LIMIT = 500
		val ACTOR_KINDS = setOf("person", "session", "system")
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	/** Newest first. Rows of one transaction share `at`, so the id breaks ties and pages stay stable. */
	fun list(q: AuditQuery): List<AuditEntry> {
		val where = mutableListOf("true")
		val params = linkedMapOf<String, Any?>()
		fun eq(column: String, value: String?) {
			val v = value?.trim()?.ifEmpty { null } ?: return
			where += "$column = :$column"
			params[column] = v
		}
		q.actorKind?.trim()?.ifEmpty { null }?.let {
			if (it !in ACTOR_KINDS) throw BadRequestException("actor_kind must be one of $ACTOR_KINDS")
		}
		// Column names are the fixed ones below, never input.
		eq("target_kind", q.targetKind)
		eq("target_id", q.targetId)
		eq("actor_kind", q.actorKind)
		eq("actor_id", q.actorId)
		q.action?.trim()?.ifEmpty { null }?.let { action ->
			if (action.endsWith(".")) {
				where += "action like :action escape '\\'"
				params["action"] = action.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
			} else {
				where += "action = :action"
				params["action"] = action
			}
		}
		q.before?.trim()?.ifEmpty { null }?.let { before ->
			if (before.startsWith("aud_")) {
				val at = jdbc.sql("select at from audit_log where id = :id").param("id", before)
					.query(OffsetDateTime::class.java).optional()
					.orElseThrow { BadRequestException("no audit row $before to page from") }
				where += "(at, id) < (:before_at, :before_id)"
				params["before_at"] = at
				params["before_id"] = before
			} else {
				where += "at < :before_at"
				params["before_at"] = try {
					OffsetDateTime.parse(before)
				} catch (e: DateTimeParseException) {
					throw BadRequestException("before must be an audit id (aud_…) or a time like 2026-10-14T09:00:00+09:00")
				}
			}
		}
		val stmt = jdbc.sql(
			"""
			select id, actor_kind, actor_id, on_behalf_of_user_id, action, target_kind, target_id, details::text, at
			  from audit_log where ${where.joinToString(" and ")}
			 order by at desc, id desc limit :limit
			""".trimIndent(),
		).param("limit", q.limit.coerceIn(1, MAX_LIMIT))
		params.forEach { (k, v) -> stmt.param(k, v) }
		return stmt.query { rs, _ ->
			AuditEntry(
				rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
				rs.getString(7), json.readValue(rs.getString(8), mapType), rs.getObject(9, OffsetDateTime::class.java),
			)
		}.list()
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
	): List<AuditEntry> {
		permissions.person(authorization, "read the audit log", Act.AUDIT_READ)
		return audit.list(AuditQuery(targetKind, targetId, actorKind, actorId, action, before, limit))
	}
}
