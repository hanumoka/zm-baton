package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Retention rules per kind of record (FN-1404 "자료별 보존 규칙", G13.1). The organisation's duties
// are unknown, so no period is assumed: by default every kind is kept forever and nothing is
// deleted. A person sets days for a kind (application.properties, from the environment); then a
// check every hour deletes that kind's records older than that, at most once a day, and writes
// what it deleted to the audit log. Work items, decisions, projects, devices and run rows are not
// kinds here: they are never deleted by retention.

/** A kind of record a retention period can apply to, with the setting that holds its days. */
enum class RetentionKind(val key: String, val label: String, val setting: String) {
	AUDIT_LOG("audit_log", "감사 기록", "ZM_BATON_RETENTION_AUDIT_LOG_DAYS"),
	ENDED_SESSIONS("ended_sessions", "끝난 세션과 그 사건", "ZM_BATON_RETENTION_ENDED_SESSIONS_DAYS"),
	CLOSED_INBOX_ITEMS("closed_inbox_items", "닫힌 첫 화면 항목", "ZM_BATON_RETENTION_CLOSED_INBOX_ITEMS_DAYS"),
	CLOSED_NOTICES("closed_notices", "해결했거나 충돌 아님으로 판정한 충돌 알림", "ZM_BATON_RETENTION_CLOSED_NOTICES_DAYS"),
	ENDED_RUN_EVENTS("ended_run_events", "끝난 실행 시도의 사건", "ZM_BATON_RETENTION_ENDED_RUN_EVENTS_DAYS"),
}

/**
 * Days to keep each kind; null keeps it forever. No defaults on purpose: one constructor, so
 * Spring binds through it, and an empty setting (`${...:}`) binds as null.
 */
@ConfigurationProperties("zm-baton.retention")
data class RetentionProperties(
	val auditLogDays: Int?,
	val endedSessionsDays: Int?,
	val closedInboxItemsDays: Int?,
	val closedNoticesDays: Int?,
	val endedRunEventsDays: Int?,
) {
	init {
		for ((kind, days) in all()) {
			require(days == null || days >= 1) { "${kind.setting} must be empty (keep forever) or 1 or more days, not $days" }
		}
	}

	fun all(): Map<RetentionKind, Int?> = linkedMapOf(
		RetentionKind.AUDIT_LOG to auditLogDays,
		RetentionKind.ENDED_SESSIONS to endedSessionsDays,
		RetentionKind.CLOSED_INBOX_ITEMS to closedInboxItemsDays,
		RetentionKind.CLOSED_NOTICES to closedNoticesDays,
		RetentionKind.ENDED_RUN_EVENTS to endedRunEventsDays,
	)

	/** Only the kinds that have days. Empty is the default: nothing to delete. */
	fun configured(): Map<RetentionKind, Int> =
		all().mapNotNull { (kind, days) -> days?.let { kind to it } }.toMap()

	companion object {
		val KEEP_EVERYTHING = RetentionProperties(null, null, null, null, null)
	}
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetentionProperties::class)
class RetentionConfig

data class RetentionRun(
	@JsonProperty("ran_at") val ranAt: OffsetDateTime,
	/** Days per kind key, for the kinds that had days. */
	val days: Map<String, Int>,
	/** Rows deleted per table. */
	val deleted: Map<String, Int>,
)

data class RetentionKindView(
	val kind: String,
	val label: String,
	/** Null: kept forever. */
	val days: Int?,
	val setting: String,
)

data class RetentionView(
	val kinds: List<RetentionKindView>,
	@JsonProperty("last_run") val lastRun: RetentionRun?,
)

@Service
class RetentionService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val sessions: SessionService,
	private val props: RetentionProperties,
) {
	companion object {
		const val ACTION = "retention.purged"
		const val ACTOR = "retention"
		const val MIN_HOURS_BETWEEN_RUNS = 24L
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	fun view(): RetentionView = RetentionView(
		props.all().map { (kind, days) -> RetentionKindView(kind.key, kind.label, days, kind.setting) },
		lastRun(),
	)

	/**
	 * The hourly check: runs [purge] with the configured periods when at least one kind has days
	 * and the last run is [MIN_HOURS_BETWEEN_RUNS] old. Returns null when it did not run. With the
	 * default settings it never runs, so it deletes nothing and writes nothing.
	 */
	@Transactional
	fun runIfDue(now: OffsetDateTime = OffsetDateTime.now()): RetentionRun? {
		val policy = props.configured()
		if (policy.isEmpty()) return null
		// One run at a time even if two checks overlap; the second sees the first one's audit row.
		jdbc.sql("select pg_advisory_xact_lock(hashtext('zm-baton.retention'))").query().listOfRows()
		val last = lastRun()?.ranAt
		if (last != null && last.isAfter(now.minusHours(MIN_HOURS_BETWEEN_RUNS))) return null
		return purge(policy, now)
	}

	/** Deletes, per kind in [policy], the records older than its days before [now], and audits the counts. */
	@Transactional
	fun purge(policy: Map<RetentionKind, Int>, now: OffsetDateTime): RetentionRun {
		val deleted = linkedMapOf<String, Int>()
		for ((kind, days) in policy) {
			require(days >= 1) { "${kind.setting} must be 1 or more days" }
			val cutoff = now.minusDays(days.toLong())
			delete(kind, cutoff).forEach { (table, n) -> deleted[table] = (deleted[table] ?: 0) + n }
		}
		val run = RetentionRun(now, policy.mapKeys { it.key.key }, deleted)
		if (policy.isNotEmpty()) {
			sessions.audit(
				"system", ACTOR, ACTION, null, null,
				mapOf("ran_at" to now.toString(), "days" to run.days, "deleted" to deleted),
			)
		}
		return run
	}

	/** Rows deleted per table. Every statement keeps what is still open or still in use. */
	private fun delete(kind: RetentionKind, cutoff: OffsetDateTime): Map<String, Int> = when (kind) {
		RetentionKind.AUDIT_LOG -> mapOf(
			"audit_log" to jdbc.sql("delete from audit_log where at < :cutoff").param("cutoff", cutoff).update(),
		)
		// A session is over when it ended, was turned off or its token expired; live sessions and
		// their file reports (conflict checks read them) are never touched.
		RetentionKind.ENDED_SESSIONS -> {
			val over = """
				select id from agent_sessions
				 where coalesce(deregistered_at, ended_at, case when token_expires_at <= now() then token_expires_at end) < :cutoff
			""".trimIndent()
			val events = jdbc.sql("delete from session_events where session_id in ($over)").param("cutoff", cutoff).update()
			val rows = jdbc.sql("delete from agent_sessions where id in ($over)").param("cutoff", cutoff).update()
			mapOf("session_events" to events, "agent_sessions" to rows)
		}
		RetentionKind.CLOSED_INBOX_ITEMS -> mapOf(
			"inbox_items" to jdbc.sql("delete from inbox_items where state <> 'open' and coalesce(closed_at, created_at) < :cutoff")
				.param("cutoff", cutoff).update(),
		)
		// A judged conflict that keeps coming back (last_seen_at moves) stays, so it is not raised again.
		RetentionKind.CLOSED_NOTICES -> mapOf(
			"notices" to jdbc.sql(
				"""
				delete from notices
				 where status in ('resolved', 'not_a_conflict') and greatest(coalesce(status_at, last_seen_at), last_seen_at) < :cutoff
				""".trimIndent(),
			).param("cutoff", cutoff).update(),
		)
		// The run rows stay (work history); only the events of runs that ended before the cutoff go.
		RetentionKind.ENDED_RUN_EVENTS -> mapOf(
			"run_events" to jdbc.sql(
				"delete from run_events where run_id in (select id from runs where state = 'ended' and ended_at < :cutoff)",
			).param("cutoff", cutoff).update(),
		)
	}

	private fun lastRun(): RetentionRun? =
		jdbc.sql("select details::text from audit_log where action = :action order by at desc, id desc limit 1")
			.param("action", ACTION).query(String::class.java).optional().orElse(null)?.let { raw ->
				val d = json.readValue(raw, mapType)
				@Suppress("UNCHECKED_CAST")
				RetentionRun(
					OffsetDateTime.parse(d["ran_at"] as String),
					(d["days"] as Map<String, Number>).mapValues { it.value.toInt() },
					(d["deleted"] as Map<String, Number>).mapValues { it.value.toInt() },
				)
			}
}

/** The hourly retention check. It runs inside the server, like the session sweeper. */
@Component
class RetentionJob(private val retention: RetentionService) {
	@Scheduled(
		fixedDelayString = "\${zm-baton.retention.check-ms:3600000}",
		initialDelayString = "\${zm-baton.retention.first-check-ms:300000}",
	)
	fun check() {
		retention.runIfDue()
	}
}

@RestController
@RequestMapping("/api")
class RetentionController(private val retention: RetentionService, private val people: PersonService) {

	/** The retention period of each kind (null: kept forever) and the last run. Read-only: periods are settings. */
	@GetMapping("/settings/retention")
	fun view(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): RetentionView {
		people.refuseSessionToken(authorization, "read the retention setting")
		return retention.view()
	}
}
