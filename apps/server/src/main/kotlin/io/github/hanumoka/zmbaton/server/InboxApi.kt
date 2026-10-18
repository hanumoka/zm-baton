package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.Permissions
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.RecordVisibility
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// The person's side: the first screen ("나를 기다리는 것", contract v1 "첫 화면"), the session
// list and the judgement of a conflict notice. These are person actions, so a session token is
// refused here and the refusal is audited (contract "등록과 세션 토큰" 6; Requester). There is no
// person authentication yet; the server listens on loopback only.

data class SessionView(
	val id: String,
	val executor: String,
	@JsonProperty("executor_version") val executorVersion: String?,
	@JsonProperty("device_name") val deviceName: String,
	val repository: Map<String, Any?>?,
	@JsonProperty("repo_key") val repoKey: String?,
	/** Null when the session does not share what it works on. */
	@JsonProperty("current_work") val currentWork: String?,
	val shared: Boolean,
	@JsonProperty("current_work_at") val currentWorkAt: OffsetDateTime?,
	/** `live`, `ended`, `deregistered` or `expired`. */
	val state: String,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("last_seen_at") val lastSeenAt: OffsetDateTime,
	@JsonProperty("touched_files") val touchedFiles: List<String>,
	@JsonProperty("conflicted_files") val conflictedFiles: List<String>,
)

data class InboxItemView(
	val id: String,
	val bucket: Int,
	val kind: String,
	@JsonProperty("source_kind") val sourceKind: String,
	@JsonProperty("source_id") val sourceId: String,
	val summary: String,
	val detail: Map<String, Any?>?,
	@JsonProperty("wait_kind") val waitKind: String?,
	@JsonProperty("repeat_count") val repeatCount: Int,
	@JsonProperty("waiting_since") val waitingSince: OffsetDateTime,
	val notify: String,
	// Relays (contract "권한 요청과 선택형 질문을 첫 화면으로 중계한다"); null for the other kinds.
	/** `open`, or for a relay also `answered`, `delivered`, `moved_to_terminal`, `undelivered` or `closed`. */
	val state: String = "open",
	/** Until when an answer can be given here. Null: not relayed, answered in the session's terminal. */
	@JsonProperty("relay_deadline") val relayDeadline: OffsetDateTime? = null,
	/** Why it was not relayed or how it ended (RelayService). */
	@JsonProperty("relay_reason") val relayReason: String? = null,
	/** What the person pressed: decision and reason, or the chosen answers. */
	val answer: Map<String, Any?>? = null,
	@JsonProperty("answered_at") val answeredAt: OffsetDateTime? = null,
	@JsonProperty("delivered_at") val deliveredAt: OffsetDateTime? = null,
	@JsonProperty("closed_at") val closedAt: OffsetDateTime? = null,
	/** Added with human review picking: a review that waited too long and is raised to bucket 1. */
	@JsonProperty("promoted_at") val promotedAt: OffsetDateTime? = null,
)

data class NoticeStatusIn(val status: String, val note: String? = null)

data class NoticeStatusOut(val id: String, val status: String)

@Service
class PersonService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val sessions: SessionService,
	private val access: PermissionFacts,
	private val visibility: RecordVisibility,
) {
	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val listType = object : TypeReference<List<String>>() {}

	/**
	 * The organisation's session board: every session of [viewer]'s organisation (all without one).
	 * What a session works on shows only when it is shared with the organisation; a person sees their
	 * own unshared summaries in their manager record, not here.
	 */
	fun sessions(includeEnded: Boolean, limit: Int, viewer: Actor? = null): List<SessionView> =
		jdbc.sql(
			"""
			select a.id, a.executor, a.executor_version, d.name as device_name, a.repository::text as repository, a.repo_key,
			       a.current_work, a.current_work_visibility, a.current_work_at, a.created_at, a.last_seen_at,
			       case when a.deregistered_at is not null then 'deregistered'
			            when a.ended_at is not null then 'ended'
			            when a.token_expires_at <= now() then 'expired'
			            else 'live' end as state,
			       coalesce((select json_agg(t.path order by t.last_at desc)::text from (
			           select e.payload ->> 'path' as path, max(e.received_at) as last_at
			             from session_events e where e.session_id = a.id and e.kind = 'file_touch'
			            group by e.payload ->> 'path' order by last_at desc limit 20) t), '[]') as touched,
			       coalesce((select json_agg(distinct n.evidence ->> 'path')::text from notices n
			          where n.status in ('open', 'acknowledged')
			            and n.evidence -> 'sessions' @> jsonb_build_array(jsonb_build_object('session_id', a.id))), '[]') as conflicted
			  from agent_sessions a join devices d on d.id = a.device_id
			 where (cast(:all as boolean) or (a.ended_at is null and a.deregistered_at is null and a.token_expires_at > now()))
			   and (cast(:org as text) is null or a.org_id = :org)
			 order by a.last_seen_at desc
			 limit :limit
			""".trimIndent(),
		).param("all", includeEnded).param("limit", limit).param("org", viewer?.orgId).query { rs, _ ->
			val shared = rs.getString("current_work_visibility") != "personal"
			SessionView(
				id = rs.getString("id"),
				executor = rs.getString("executor"),
				executorVersion = rs.getString("executor_version"),
				deviceName = rs.getString("device_name"),
				repository = rs.getString("repository")?.let { json.readValue(it, mapType) },
				repoKey = rs.getString("repo_key"),
				currentWork = if (shared) rs.getString("current_work") else null,
				shared = shared,
				currentWorkAt = if (shared) rs.getObject("current_work_at", OffsetDateTime::class.java) else null,
				state = rs.getString("state"),
				createdAt = rs.getObject("created_at", OffsetDateTime::class.java),
				lastSeenAt = rs.getObject("last_seen_at", OffsetDateTime::class.java),
				touchedFiles = json.readValue(rs.getString("touched"), listType),
				conflictedFiles = json.readValue(rs.getString("conflicted"), listType),
			)
		}.list()

	/**
	 * Open items, in the contract's order: bucket (a review raised after waiting too long counts as 1),
	 * then who waited longest. A relay also shows while its answer is on the way and for
	 * [RelayService.RECENT_MINUTES] after it closed, so the person sees whether the answer reached the
	 * executor; those come after the open ones of their bucket.
	 */
	fun inbox(person: String): List<InboxItemView> {
		val viewer = access.person(person)
		val reader = visibility.reader(viewer)
		return jdbc.sql(
			"""
			select id, bucket, kind, source_kind, source_id, summary, detail::text as detail, wait_kind, repeat_count,
			       waiting_since, notify, state, relay_deadline, relay_reason, answer::text as answer, answered_at,
			       delivered_at, closed_at, promoted_at
			  from inbox_items
			 where user_id = :user
			   and (state = 'open'
			        or (kind in ('permission_request', 'session_question')
			            and (state in ('answered', 'delivered')
			                 or (relay_deadline is not null and closed_at > now() - make_interval(mins => :recent)))))
			 order by case when promoted_at is null then bucket else 1 end, state not in ('open', 'answered'), waiting_since, id
			""".trimIndent(),
		).param("user", person).param("recent", RelayService.RECENT_MINUTES).query { rs, _ ->
			InboxItemView(
				id = rs.getString("id"),
				bucket = rs.getInt("bucket"),
				kind = rs.getString("kind"),
				sourceKind = rs.getString("source_kind"),
				sourceId = rs.getString("source_id"),
				summary = rs.getString("summary"),
				detail = rs.getString("detail")?.let { json.readValue(it, mapType) },
				waitKind = rs.getString("wait_kind"),
				repeatCount = rs.getInt("repeat_count"),
				waitingSince = rs.getObject("waiting_since", OffsetDateTime::class.java),
				notify = rs.getString("notify"),
				state = rs.getString("state"),
				relayDeadline = rs.getObject("relay_deadline", OffsetDateTime::class.java),
				relayReason = rs.getString("relay_reason"),
				answer = rs.getString("answer")?.let { json.readValue(it, mapType) },
				answeredAt = rs.getObject("answered_at", OffsetDateTime::class.java),
				deliveredAt = rs.getObject("delivered_at", OffsetDateTime::class.java),
				closedAt = rs.getObject("closed_at", OffsetDateTime::class.java),
				promotedAt = rs.getObject("promoted_at", OffsetDateTime::class.java),
			)
		}.list().filter { item ->
			access.inboxItem(item.id)?.let { PermissionRules.canSee(viewer, it) } == true && reader.allows(listOf(item.sourceId, item.detail))
		}
	}

	/**
	 * A person's judgement of a conflict notice (contract "알림 처리"). Any of the three takes it
	 * off the first screen in the same transaction; "not a conflict" needs a reason.
	 */
	@Transactional
	fun judgeNotice(noticeId: String, body: NoticeStatusIn, actor: String): NoticeStatusOut {
		val status = body.status.trim()
		if (status !in setOf("acknowledged", "not_a_conflict", "resolved")) {
			throw BadRequestException("status must be acknowledged, not_a_conflict or resolved")
		}
		val note = body.note?.trim()?.take(500)?.ifEmpty { null }
		if (status == "not_a_conflict" && note == null) throw BadRequestException("not_a_conflict needs a reason in note")
		val updated = jdbc.sql(
			"""
			update notices set status = :status, status_by = :user, status_at = now(), status_note = :note
			 where id = :id
			""".trimIndent(),
		).param("status", status).param("user", actor).param("note", note).param("id", noticeId).update()
		if (updated == 0) throw NotFoundException("no notice $noticeId")
		jdbc.sql(
			"""
			update inbox_items set state = 'closed', closed_at = now()
			 where source_kind = 'ntc' and source_id = :id and state = 'open'
			""".trimIndent(),
		).param("id", noticeId).update()
		sessions.audit("person", actor, "notice.$status", "ntc", noticeId, mapOf("note" to note))
		return NoticeStatusOut(noticeId, status)
	}
}

@RestController
@RequestMapping("/api")
class PersonController(private val people: PersonService, private val permissions: Permissions) {

	@GetMapping("/sessions")
	fun sessions(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(name = "include_ended", defaultValue = "false") includeEnded: Boolean,
		@RequestParam(defaultValue = "50") limit: Int,
	): List<SessionView> {
		val me = permissions.person(authorization, "list sessions", Act.READ)
		return people.sessions(includeEnded, limit.coerceIn(1, 200), me)
	}

	/** My first screen: only what waits for me. */
	@GetMapping("/inbox")
	fun inbox(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): List<InboxItemView> {
		val me = permissions.person(authorization, "read the first screen", Act.READ)
		return people.inbox(me.person!!)
	}

	@PostMapping("/notices/{id}/status")
	fun judgeNotice(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: NoticeStatusIn,
	): NoticeStatusOut {
		val me = permissions.person(authorization, "judge a notice")
		permissions.require(me, Act.NOTICE_JUDGE, permissions.notice(id))
		return people.judgeNotice(id, body, me.person!!)
	}
}

/**
 * Ends registrations whose token expired and closes their waits. Runs inside the server for
 * now; whether stage 1 keeps such jobs here or in a separate background worker is still open.
 */
@Component
class SessionSweeper(private val sessions: SessionService) {
	@Scheduled(fixedDelayString = "\${zm-baton.session-sweep-ms:60000}", initialDelayString = "\${zm-baton.session-sweep-ms:60000}")
	fun sweep() {
		sessions.endExpired()
	}
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfig
