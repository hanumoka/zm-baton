package io.github.hanumoka.zmbaton.server

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URISyntaxException
import java.time.OffsetDateTime

/**
 * Directly opened AI sessions (contract v1 "직접 연 AI 세션을 서버에 등록한다"), the file
 * conflicts they reveal ("작업 충돌" 6) and the first-screen items they create.
 *
 * Until person authentication exists there is one person, [OWNER], and the server listens on
 * loopback only (stage-1 exit criterion 4).
 */
@Service
class SessionService(private val jdbc: JdbcClient, private val json: ObjectMapper) {

	companion object {
		const val OWNER = "usr_owner"
		const val PROTOCOL_VERSION = 2
		const val TOKEN_HOURS = 12
		/** A live session's token is pushed forward again when fewer hours than this remain. */
		const val REFRESH_BELOW_HOURS = 6
		const val CURRENT_WORK_MAX = 300
		const val STATUS_CARD_MAX = 2000
		const val CARD_WORK_ITEMS = 5
		const val CARD_DECISIONS = 5
		const val MAX_BATCH = 100
		/** The first key of an advisory lock; each kind of lock has its own number space. */
		private const val LOCK_REGISTER = 1
		private const val LOCK_FILE = 2
		val EXECUTORS = setOf("claude_code", "codex")
		val CLIENT_KINDS = setOf("current_work", "waiting", "file_touch", "resumed", "ended", "deregistered")
		/** Waits that stop work until a person answers are raised at once; the rest wait for the digest. */
		val IMMEDIATE_WAITS = setOf("permission_prompt", "agent_needs_input", "elicitation_dialog", "elicitation_url_dialog")
		private val WAIT_KIND = Regex("^[a-z][a-z0-9_]{0,49}$")
		private val REPO_KEY = Regex("^[A-Za-z0-9._:@/+-]{1,200}$")
		private val HEX = Regex("^[0-9a-f]{7,128}$")
		private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
		/** `[user@]host:path` without whitespace, query or fragment; the user part is dropped. */
		private val SCP_REMOTE = Regex("^(?:[^@/\\s]+@)?([A-Za-z0-9._-]+):([^\\s?#]+)$")
		private val DRIVE = Regex("^[A-Za-z]:")

		/** A repository key such as `git:<root commit>`: a token, never a path on this machine. */
		fun isRepoKey(s: String): Boolean = REPO_KEY.matches(s) && !looksAbsolute(s)

		private fun looksAbsolute(s: String): Boolean =
			s.startsWith("/") || s.startsWith("\\") || DRIVE.containsMatchIn(s) || s.startsWith("~")
	}

	private data class SessionRow(
		val id: String,
		val executor: String,
		val repoKey: String?,
		val branch: String?,
		var maxResumedSeq: Long,
	)

	// ---------------------------------------------------------------- devices and registration

	@Transactional
	fun registerDevice(body: RegisterDevice): DeviceRegistered {
		val name = body.name.trim()
		if (name.isEmpty() || name.length > 100) throw BadRequestException("name must be 1-100 characters")
		val os = body.os?.trim()?.take(50)
		val id = Ids.device()
		jdbc.sql("insert into devices (id, user_id, name, os, last_seen_at) values (:id, :user, :name, :os, now())")
			.param("id", id).param("user", OWNER).param("name", name).param("os", os).update()
		audit("person", OWNER, "device.registered", "dev", id, mapOf("name" to name, "os" to os))
		return DeviceRegistered(id)
	}

	@Transactional
	fun register(req: RegisterSession): SessionRegistered {
		requireProtocol(req.protocolVersion)
		if (req.executor !in EXECUTORS) throw BadRequestException("executor must be one of $EXECUTORS")
		val executorSessionId = req.executorSessionId.trim()
		if (executorSessionId.isEmpty() || executorSessionId.length > 200) {
			throw BadRequestException("executor_session_id must be 1-200 characters")
		}
		val repository = cleanRepository(req.repository)
		val deviceId = req.deviceId.trim()
		jdbc.sql("select id from devices where id = :id and revoked_at is null for update")
			.param("id", deviceId).query(String::class.java).optional()
			.orElseThrow { NotFoundException("no active device $deviceId; run zm-baton login") }

		// Two start hooks of one executor session must not race into two registrations.
		lock(LOCK_REGISTER, "$deviceId|${req.executor}|$executorSessionId")
		// An expired registration is over; ending it frees the one-live-registration index and,
		// like the sweeper, closes its waits.
		endExpiredWhere(
			"device_id = :device and executor = :executor and executor_session_id = :esid",
			mapOf("device" to deviceId, "executor" to req.executor, "esid" to executorSessionId),
		)

		val token = SessionTokens.newToken()
		val existing = jdbc.sql(
			"""
			select id from agent_sessions
			 where device_id = :device and executor = :executor and executor_session_id = :esid
			   and ended_at is null and deregistered_at is null
			   for update
			""".trimIndent(),
		).param("device", deviceId).param("executor", req.executor).param("esid", executorSessionId)
			.query(String::class.java).optional()

		val repositoryJson = repository?.let { json.writeValueAsString(it.view) }
		val sessionId: String
		if (existing.isPresent) {
			// A resume (or a repeated start hook) of a live session: same record, new token.
			sessionId = existing.get()
			jdbc.sql(
				"""
				update agent_sessions
				   set token_hash = :hash, token_expires_at = now() + make_interval(hours => :hours),
				       executor_version = :version, repository = cast(:repo as jsonb), repo_key = :repoKey,
				       last_seen_at = now()
				 where id = :id
				""".trimIndent(),
			).param("hash", SessionTokens.hash(token)).param("hours", TOKEN_HOURS)
				.param("version", req.executorVersion?.take(50)).param("repo", repositoryJson)
				.param("repoKey", repository?.repoKey).param("id", sessionId).update()
		} else {
			sessionId = Ids.session()
			jdbc.sql(
				"""
				insert into agent_sessions (id, user_id, device_id, executor, executor_version, executor_session_id,
				                            repository, repo_key, token_hash, token_expires_at)
				values (:id, :user, :device, :executor, :version, :esid, cast(:repo as jsonb), :repoKey, :hash,
				        now() + make_interval(hours => :hours))
				""".trimIndent(),
			).param("id", sessionId).param("user", OWNER).param("device", deviceId).param("executor", req.executor)
				.param("version", req.executorVersion?.take(50)).param("esid", executorSessionId)
				.param("repo", repositoryJson).param("repoKey", repository?.repoKey)
				.param("hash", SessionTokens.hash(token)).param("hours", TOKEN_HOURS).update()
		}
		insertEvent(
			sessionId, Ids.event(), 0, "registered",
			mapOf(
				"executor" to req.executor, "executor_version" to req.executorVersion?.take(50),
				"executor_session_id" to executorSessionId, "repository" to repository?.view,
				"reused" to existing.isPresent,
			),
		)
		jdbc.sql("update devices set last_seen_at = now() where id = :id").param("id", deviceId).update()
		audit(
			"session", sessionId, if (existing.isPresent) "session.reregistered" else "session.registered",
			"ses", sessionId, mapOf("device_id" to deviceId, "executor" to req.executor),
		)
		val expiresAt = jdbc.sql("select token_expires_at from agent_sessions where id = :id")
			.param("id", sessionId).query(OffsetDateTime::class.java).single()
		return SessionRegistered(sessionId, token, expiresAt, existing.isPresent, statusCard(sessionId))
	}

	// ---------------------------------------------------------------- events

	@Transactional
	fun ingest(sessionId: String, token: String, batch: SessionEventBatch): SessionEventResult {
		requireProtocol(batch.protocolVersion)
		if (batch.events.size > MAX_BATCH) throw BadRequestException("at most $MAX_BATCH events per batch")
		val session = authenticate(sessionId, token, forUpdate = true)
		// Every file lock this batch needs is taken up front, so two batches that touch the same
		// files in opposite orders cannot wait for each other.
		lockFiles(
			batch.events.filter { it.kind == "file_touch" }.mapNotNull { event ->
				try {
					validate(event).let { fileLockKey(it["repo_key"] as String, it["path"] as String) }
				} catch (e: BadRequestException) {
					null
				}
			},
		)
		var stored = 0
		var duplicates = 0
		val reasons = mutableListOf<String>()
		var open = true
		for (event in batch.events.sortedBy { it.localSeq }) {
			if (!open) {
				reasons += "${event.localSeq}: the session ended earlier in this batch"
				continue
			}
			val clean = try {
				validate(event)
			} catch (e: BadRequestException) {
				reasons += "${event.localSeq}: ${e.message}"
				continue
			}
			when (insertEvent(sessionId, event.eventId.trim(), event.localSeq, event.kind, clean)) {
				InsertResult.DUPLICATE -> {
					duplicates++
					continue
				}
				InsertResult.FOREIGN -> {
					reasons += "${event.localSeq}: event_id is already used by another session"
					continue
				}
				InsertResult.STORED -> stored++
			}
			apply(session, event.localSeq, event.kind, clean)
			if (event.kind == "ended" || event.kind == "deregistered") open = false
		}
		if (open) {
			jdbc.sql(
				"""
				update agent_sessions
				   set last_seen_at = now(),
				       token_expires_at = case
				         when token_expires_at < now() + make_interval(hours => :refresh)
				         then now() + make_interval(hours => :hours) else token_expires_at end
				 where id = :id
				""".trimIndent(),
			).param("refresh", REFRESH_BELOW_HOURS).param("hours", TOKEN_HOURS).param("id", sessionId).update()
		}
		return SessionEventResult(stored, duplicates, reasons.size, reasons)
	}

	fun statusCardFor(sessionId: String, token: String): String {
		authenticate(sessionId, token, forUpdate = false)
		return statusCard(sessionId)
	}

	/** Ends registrations whose token expired and closes their waits. The sweeper calls this. */
	@Transactional
	fun endExpired(): Int = endExpiredWhere("true", emptyMap())

	/**
	 * The one way an expired registration ends: `ended_at` and the closing of its waits happen
	 * together, whether the sweeper or a new registration finds it first.
	 */
	private fun endExpiredWhere(condition: String, params: Map<String, Any?>): Int {
		var statement = jdbc.sql(
			"""
			update agent_sessions set ended_at = token_expires_at
			 where ($condition) and ended_at is null and deregistered_at is null and token_expires_at <= now()
			returning id
			""".trimIndent(),
		)
		for ((name, value) in params) statement = statement.param(name, value)
		val ended = statement.query(String::class.java).list()
		for (id in ended) closeWaits(id!!)
		return ended.size
	}

	private fun apply(session: SessionRow, seq: Long, kind: String, payload: Map<String, Any?>) {
		when (kind) {
			// Only a newer summary replaces the current one. Each summary carries its own sharing
			// setting, so no event depends on another one having arrived first; the CLI remembers a
			// session's choice and states it every time.
			"current_work" -> jdbc.sql(
				"""
				update agent_sessions
				   set current_work = :summary, current_work_refs = cast(:refs as jsonb), current_work_at = now(),
				       share_current_work = :share, current_work_seq = :seq
				 where id = :id and current_work_seq < :seq
				""".trimIndent(),
			).param("summary", payload["summary"]).param("refs", json.writeValueAsString(payload["refs"] ?: emptyList<String>()))
				.param("share", payload["share"] as Boolean).param("seq", seq).param("id", session.id).update()

			// A report older than the newest resume is already over (contract "세션이 보내는 사건" 6).
			"waiting" -> if (seq > session.maxResumedSeq) refreshWait(session, payload["wait_kind"] as String)

			"resumed" -> {
				session.maxResumedSeq = maxOf(session.maxResumedSeq, seq)
				jdbc.sql("update agent_sessions set max_resumed_seq = greatest(max_resumed_seq, :seq) where id = :id")
					.param("seq", seq).param("id", session.id).update()
				val kinds = jdbc.sql(
					"select distinct wait_kind from inbox_items where source_id = :session and kind = 'session_waiting' and state = 'open'",
				).param("session", session.id).query(String::class.java).list()
				for (kind in kinds) refreshWait(session, kind!!)
			}

			"file_touch" -> checkConflicts(session, payload["repo_key"] as String, payload["path"] as String)

			"ended" -> {
				jdbc.sql("update agent_sessions set ended_at = now() where id = :id").param("id", session.id).update()
				closeWaits(session.id)
			}

			"deregistered" -> {
				jdbc.sql("update agent_sessions set deregistered_at = now() where id = :id").param("id", session.id).update()
				closeWaits(session.id)
				audit("session", session.id, "session.deregistered", "ses", session.id, emptyMap())
			}
		}
	}

	// ---------------------------------------------------------------- waits ("나를 기다리는 것" 2)

	/**
	 * Recomputes the session's open wait of one kind from the event log instead of adjusting it
	 * step by step, so the order in which reports and resumes arrive does not matter. The wait
	 * is made of the reports after the newest resume: it waits since the earliest of them
	 * reached the server and shows the words of the newest one. No such report: the wait is over.
	 */
	private fun refreshWait(session: SessionRow, waitKind: String) {
		data class Span(val first: Long, val last: Long, val count: Long, val since: OffsetDateTime)
		val span = jdbc.sql(
			"""
			select min(local_seq), max(local_seq), count(*), min(received_at) from session_events
			 where session_id = :session and kind = 'waiting' and payload ->> 'wait_kind' = :kind and local_seq > :resumed
			""".trimIndent(),
		).param("session", session.id).param("kind", waitKind).param("resumed", session.maxResumedSeq).query { rs, _ ->
			if (rs.getObject(1) == null) null
			else Span(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getObject(4, OffsetDateTime::class.java))
		}.list().firstOrNull()
		if (span == null) {
			jdbc.sql(
				"""
				update inbox_items set state = 'closed', closed_at = now()
				 where source_id = :session and kind = 'session_waiting' and wait_kind = :kind and state = 'open'
				""".trimIndent(),
			).param("session", session.id).param("kind", waitKind).update()
			return
		}
		val message = jdbc.sql(
			"""
			select payload ->> 'message' from session_events
			 where session_id = :session and kind = 'waiting' and payload ->> 'wait_kind' = :kind and local_seq = :last
			 order by received_at desc limit 1
			""".trimIndent(),
		).param("session", session.id).param("kind", waitKind).param("last", span.last)
			.query { rs, _ -> rs.getString(1) }.list().firstOrNull()
		val label = when (waitKind) {
			"permission_prompt" -> "터미널에서 권한 확인을 기다림"
			"idle_prompt" -> "다음 지시를 기다림"
			else -> "입력을 기다림"
		}
		val summary = listOfNotNull(sessionLabel(session), label, message?.takeIf { it.isNotBlank() })
			.joinToString(" · ").take(300)
		jdbc.sql(
			"""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, detail, wait_kind,
			                         wait_first_seq, wait_seq, repeat_count, waiting_since, notify)
			values (:id, :user, 2, 'session_waiting', 'ses', :session, :summary, cast(:detail as jsonb), :kind,
			        :first, :last, :repeats, :since, :notify)
			on conflict (source_id, wait_kind) where state = 'open' and kind = 'session_waiting'
			do update set summary = excluded.summary, detail = excluded.detail,
			              wait_first_seq = excluded.wait_first_seq, wait_seq = excluded.wait_seq,
			              repeat_count = excluded.repeat_count, waiting_since = excluded.waiting_since
			""".trimIndent(),
		).param("id", Ids.inbox()).param("user", OWNER).param("session", session.id).param("summary", summary)
			.param("detail", json.writeValueAsString(mapOf("wait_kind" to waitKind, "message" to message, "branch" to session.branch)))
			.param("kind", waitKind).param("first", span.first).param("last", span.last).param("repeats", span.count - 1)
			.param("since", span.since).param("notify", if (waitKind in IMMEDIATE_WAITS) "immediate" else "digest").update()
	}

	/** Closes all open waits of a session that ended, was turned off or expired. */
	private fun closeWaits(sessionId: String) {
		jdbc.sql(
			"""
			update inbox_items set state = 'closed', closed_at = now()
			 where source_id = :session and kind = 'session_waiting' and state = 'open'
			""".trimIndent(),
		).param("session", sessionId).update()
	}

	// ---------------------------------------------------------------- conflicts ("작업 충돌" 6)

	private fun checkConflicts(session: SessionRow, repoKey: String, path: String) {
		// Serialises checks of one file: of two sessions touching it at once, the second sees the first.
		// The batch already holds this lock (see [ingest]); taking it again is free.
		lockFiles(listOf(fileLockKey(repoKey, path)))
		data class Other(val id: String, val executor: String, val branch: String?, val touchedAt: OffsetDateTime)
		val others = jdbc.sql(
			"""
			select a.id, a.executor, a.repository ->> 'branch' as branch, max(e.received_at) as touched_at
			  from session_events e join agent_sessions a on a.id = e.session_id
			 where e.kind = 'file_touch' and e.payload ->> 'repo_key' = :repo and e.payload ->> 'path' = :path
			   and a.id <> :me and a.ended_at is null and a.deregistered_at is null and a.token_expires_at > now()
			 group by a.id, a.executor, a.repository ->> 'branch'
			""".trimIndent(),
		).param("repo", repoKey).param("path", path).param("me", session.id).query { rs, _ ->
			Other(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime::class.java))
		}.list()
		for (other in others) {
			val pair = listOf(session.id, other.id).sorted()
			val evidence = mapOf(
				"source" to "file_touch",
				"repo_key" to repoKey,
				"path" to path,
				"sessions" to listOf(
					mapOf("session_id" to session.id, "executor" to session.executor, "branch" to session.branch),
					mapOf(
						"session_id" to other.id, "executor" to other.executor, "branch" to other.branch,
						"touched_at" to other.touchedAt.toString(),
					),
				),
			)
			val summary = "두 세션이 같은 파일을 고침: $path · " +
				"${short(session.id)}(${session.executor}${session.branch?.let { ", $it" } ?: ""}) · " +
				"${short(other.id)}(${other.executor}${other.branch?.let { ", $it" } ?: ""})"
			raiseConflict("work_conflict|$repoKey|$path|${pair[0]}|${pair[1]}", evidence, summary.take(300))
		}
	}

	private fun raiseConflict(dedupeKey: String, evidence: Map<String, Any?>, summary: String) {
		val evidenceJson = json.writeValueAsString(evidence)
		val existing = jdbc.sql("select id, status from notices where dedupe_key = :key for update")
			.param("key", dedupeKey).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional()
		val noticeId: String
		if (existing.isEmpty) {
			noticeId = Ids.notice()
			jdbc.sql(
				"""
				insert into notices (id, kind, evidence, dedupe_key, recipients)
				values (:id, 'work_conflict', cast(:evidence as jsonb), :key, array[:owner])
				""".trimIndent(),
			).param("id", noticeId).param("evidence", evidenceJson).param("key", dedupeKey).param("owner", OWNER).update()
		} else {
			noticeId = existing.get().first
			// The same evidence again is a repeat of the same notice (G05.2). A resolved conflict
			// that comes back is open again; "acknowledged" and "not a conflict" stay as judged.
			jdbc.sql(
				"""
				update notices
				   set repeat_count = repeat_count + 1, last_seen_at = now(), evidence = cast(:evidence as jsonb),
				       status = case when status = 'resolved' then 'open' else status end,
				       status_by = case when status = 'resolved' then null else status_by end,
				       status_at = case when status = 'resolved' then null else status_at end,
				       status_note = case when status = 'resolved' then null else status_note end
				 where id = :id
				""".trimIndent(),
			).param("evidence", evidenceJson).param("id", noticeId).update()
			val status = existing.get().second
			if (status != "open" && status != "resolved") return
		}
		// "진행 중인 내 작업과 겹친 충돌" is raised at once (DEC-0092 5).
		jdbc.sql(
			"""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, detail, notify)
			values (:id, :user, 3, 'notice', 'ntc', :notice, :summary, cast(:detail as jsonb), 'immediate')
			on conflict (source_id) where state = 'open' and kind = 'notice' do nothing
			""".trimIndent(),
		).param("id", Ids.inbox()).param("user", OWNER).param("notice", noticeId).param("summary", summary)
			.param("detail", evidenceJson).update()
	}

	// ---------------------------------------------------------------- status card

	/**
	 * What a session loads at start (contract "현재 상태 카드"), at most [STATUS_CARD_MAX] characters:
	 * first-screen counts, the person's open work items with their next action, the newest
	 * decisions recorded in this repository and the other live sessions here. When it does not
	 * fit, the next actions go first, then the card is cut before its last line.
	 */
	fun statusCard(sessionId: String): String {
		val repoKey = jdbc.sql("select repo_key from agent_sessions where id = :id")
			.param("id", sessionId).query(String::class.java).optional().orElse(null)
		val counts = jdbc.sql(
			"select bucket, count(*) from inbox_items where user_id = :user and state = 'open' group by bucket",
		).param("user", OWNER).query { rs, _ -> rs.getInt(1) to rs.getLong(2) }.list().toMap()
		val openCount = jdbc.sql(
			"select count(*) from work_items where responsible_user_id = :user and stage not in ('done', 'cancelled')",
		).param("user", OWNER).query(Long::class.javaObjectType).single()
		// Work under way first; among equals the most recently changed.
		val work = jdbc.sql(
			"""
			select external_ref, title, stage, wait_reasons, next_action from work_items
			 where responsible_user_id = :user and stage not in ('done', 'cancelled')
			 order by (stage = 'in_progress') desc, updated_at desc, opened_on desc nulls last, id desc limit $CARD_WORK_ITEMS
			""".trimIndent(),
		).param("user", OWNER).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			val reasons = (rs.getArray(4).array as Array<String>).toList()
			val head = "- ${rs.getString(1)?.let { "$it " } ?: ""}${clip(rs.getString(2), 60)} (${Workflow.label(rs.getString(3))}" +
				(if (reasons.isEmpty()) "" else " · ${reasons.joinToString(", ") { Workflow.waitLabel(it) }}") + ")"
			head to rs.getString(5)?.let { "  다음: ${clip(it, 80)}" }
		}.list()
		val decisions = if (repoKey == null) emptyList() else jdbc.sql(
			"""
			select external_ref, decided_on, question from decisions where git_ref ->> 'repo_key' = :repo
			 order by decided_on desc nulls last, external_ref desc limit $CARD_DECISIONS
			""".trimIndent(),
		).param("repo", repoKey).query { rs, _ ->
			"- ${rs.getString(1) ?: ""} (${rs.getString(2) ?: "날짜 없음"}) ${clip(rs.getString(3), 60)}"
		}.list()
		val others = if (repoKey == null) emptyList() else jdbc.sql(
			"""
			select id, executor, repository ->> 'branch', current_work, share_current_work from agent_sessions
			 where repo_key = :repo and id <> :id and ended_at is null and deregistered_at is null and token_expires_at > now()
			 order by last_seen_at desc limit 5
			""".trimIndent(),
		).param("repo", repoKey).param("id", sessionId).query { rs, _ ->
			val work = if (rs.getBoolean(5)) rs.getString(4) ?: "아직 올리지 않음" else "공유 안 함"
			"- ${short(rs.getString(1))}(${rs.getString(2)}${rs.getString(3)?.let { ", $it" } ?: ""}): $work"
		}.list()

		fun compose(withNext: Boolean): String {
			val lines = mutableListOf<String>()
			lines += "[zm-baton] 이 세션을 등록했다: $sessionId (등록을 끄려면 `zm-baton session off`)"
			lines += "나를 기다리는 것: 권한 요청 ${counts[1] ?: 0} · 멈춘 AI의 질문 ${counts[2] ?: 0} · " +
				"겹친 충돌 ${counts[3] ?: 0} · 위험 검토 ${counts[4] ?: 0} · 표본·알림 ${counts[5] ?: 0}"
			lines += if (work.isEmpty()) {
				"열린 작업: 없음"
			} else {
				val more = if (openCount > work.size) " (그 밖에 ${openCount - work.size}건)" else ""
				"열린 작업 ${openCount}건$more:\n" + work.joinToString("\n") { (head, next) ->
					if (withNext && next != null) "$head\n$next" else head
				}
			}
			if (decisions.isNotEmpty()) lines += "이 저장소의 최근 결정:\n" + decisions.joinToString("\n")
			if (others.isNotEmpty()) lines += "같은 저장소에서 지금 살아 있는 다른 세션:\n" + others.joinToString("\n")
			return lines.joinToString("\n")
		}

		val last = "하는 일이 바뀌면 `zm-baton session work \"<무엇을, 어디서>\"`로 한 줄(300자 이하)을 올린다."
		val budget = STATUS_CARD_MAX - last.length - 1
		val full = compose(withNext = true)
		val body = if (full.length <= budget) full else compose(withNext = false).take(budget)
		return "$body\n$last"
	}

	/** [s] in at most [max] characters, ending in "…" when cut. */
	private fun clip(s: String, max: Int): String {
		val flat = s.replace(Regex("\\s+"), " ").trim()
		return if (flat.length > max) flat.take(max - 1) + "…" else flat
	}

	// ---------------------------------------------------------------- helpers

	private enum class InsertResult { STORED, DUPLICATE, FOREIGN }

	private fun insertEvent(sessionId: String, eventId: String, seq: Long, kind: String, payload: Map<String, Any?>): InsertResult {
		val inserted = jdbc.sql(
			"""
			insert into session_events (event_id, session_id, local_seq, kind, payload)
			values (:eventId, :session, :seq, :kind, cast(:payload as jsonb))
			on conflict (event_id) do nothing
			""".trimIndent(),
		).param("eventId", eventId).param("session", sessionId).param("seq", seq).param("kind", kind)
			.param("payload", json.writeValueAsString(payload)).update()
		if (inserted == 1) return InsertResult.STORED
		val owner = jdbc.sql("select session_id from session_events where event_id = :id")
			.param("id", eventId).query(String::class.java).single()
		return if (owner == sessionId) InsertResult.DUPLICATE else InsertResult.FOREIGN
	}

	private fun authenticate(sessionId: String, token: String, forUpdate: Boolean): SessionRow {
		val row = jdbc.sql(
			"""
			select id, executor, repo_key, repository ->> 'branch', max_resumed_seq,
			       (ended_at is null and deregistered_at is null and token_expires_at > now()) as live
			  from agent_sessions where id = :id and token_hash = :hash
			""".trimIndent() + if (forUpdate) " for update" else "",
		).param("id", sessionId).param("hash", SessionTokens.hash(token)).query { rs, _ ->
			SessionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)) to rs.getBoolean(6)
		}.optional().orElseThrow { UnauthorizedException("unknown session or token") }
		if (!row.second) throw UnauthorizedException("the session has ended or its token expired; register again")
		return row.first
	}

	/** Checks one client event and returns the payload to store: only known fields, never paths outside the repository. */
	private fun validate(event: SessionEventIn): Map<String, Any?> {
		if (event.eventId.isBlank() || event.eventId.length > 100) throw BadRequestException("event_id must be 1-100 characters")
		if (event.localSeq < 1) throw BadRequestException("local_seq must be 1 or more")
		if (event.kind !in CLIENT_KINDS) throw BadRequestException("unknown kind ${event.kind}")
		val p = event.payload
		return when (event.kind) {
			"current_work" -> {
				val summary = (p["summary"] as? String)?.trim().orEmpty()
				if (summary.isEmpty() || summary.length > CURRENT_WORK_MAX) {
					throw BadRequestException("summary must be 1-$CURRENT_WORK_MAX characters")
				}
				val refs = (p["refs"] as? List<*>).orEmpty().map { it as? String ?: throw BadRequestException("refs must be strings") }
				if (refs.size > 10 || refs.any { it.length > 200 }) throw BadRequestException("at most 10 refs of up to 200 characters")
				// Required: a summary that leaned on an earlier one's setting could be published by
				// arriving after the event that turned sharing off.
				val share = p["share"] as? Boolean ?: throw BadRequestException("share must be true or false")
				mapOf("summary" to summary, "refs" to refs, "share" to share)
			}
			"waiting" -> {
				val waitKind = (p["wait_kind"] as? String)?.trim()?.ifEmpty { null } ?: "unknown"
				if (!WAIT_KIND.matches(waitKind)) throw BadRequestException("wait_kind must be a lower-case word")
				val message = (p["message"] as? String)?.trim()?.take(300)
				mapOf("wait_kind" to waitKind, "message" to message)
			}
			"file_touch" -> {
				val repoKey = (p["repo_key"] as? String)?.trim().orEmpty()
				if (!isRepoKey(repoKey)) throw BadRequestException("repo_key is missing or not a key")
				val path = relativePath(p["path"] as? String) ?: throw BadRequestException("path must be relative to the repository root")
				val tool = (p["tool"] as? String)?.trim()?.take(50)
				val contentHash = (p["content_hash"] as? String)?.trim()?.lowercase()?.takeIf { HEX.matches(it) }
				val head = (p["head"] as? String)?.trim()?.lowercase()?.takeIf { HEX.matches(it) }
				mapOf("repo_key" to repoKey, "path" to path, "tool" to tool, "content_hash" to contentHash, "head" to head)
			}
			else -> emptyMap()
		}
	}

	private data class CleanRepository(val repoKey: String?, val view: Map<String, String?>)

	private fun cleanRepository(repo: RepositoryIn?): CleanRepository? {
		if (repo == null) return null
		val repoKey = repo.repoKey?.trim()?.ifEmpty { null }
		if (repoKey != null && !isRepoKey(repoKey)) throw BadRequestException("repository.repo_key is not a key")
		val prefix = repo.prefix?.trim()?.trimEnd('/')
		val cleanPrefix = when {
			prefix.isNullOrEmpty() -> ""
			else -> relativePath(prefix) ?: throw BadRequestException("repository.prefix must be relative to the repository root")
		}
		val remote = cleanRemote(repo.remote)
		val branch = repo.branch?.trim()?.take(200)?.ifEmpty { null }
		return CleanRepository(repoKey, mapOf("remote" to remote, "prefix" to cleanPrefix, "branch" to branch))
	}

	/**
	 * The remote as host and path only. The whole value is parsed first and rebuilt from the parts
	 * that cannot hold a secret: user info, query and fragment are dropped, and so is the user of
	 * an scp-style `user@host:path`. Anything else, and local paths, give null. The length limit
	 * applies to the result, so cutting can never leave half a credential behind.
	 */
	fun cleanRemote(raw: String?): String? {
		val s = raw?.trim().orEmpty()
		if (s.isEmpty() || s.length > 2000 || looksAbsolute(s) || s.startsWith(".") || s.startsWith("file:", ignoreCase = true)) {
			return null
		}
		val cleaned = if (URL_SCHEME.containsMatchIn(s)) {
			val uri = try {
				URI(s)
			} catch (e: URISyntaxException) {
				return null
			}
			val host = uri.host ?: return null
			"${uri.scheme.lowercase()}://$host${if (uri.port >= 0) ":${uri.port}" else ""}${uri.rawPath.orEmpty()}"
		} else {
			val m = SCP_REMOTE.matchEntire(s) ?: return null
			"${m.groupValues[1]}:${m.groupValues[2]}"
		}
		return cleaned.takeIf { it.length <= 500 }
	}

	/** A path from the repository root with `/` separators, or null when it is absolute or climbs out. */
	fun relativePath(raw: String?): String? {
		val path = raw?.trim() ?: return null
		if (path.isEmpty() || path.length > 500 || '\\' in path || looksAbsolute(path)) return null
		val parts = path.split('/')
		if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
		return path
	}


	private fun requireProtocol(version: Int) {
		if (version != PROTOCOL_VERSION) throw BadRequestException("unsupported protocol_version $version")
	}

	private fun lock(space: Int, key: String) {
		jdbc.sql("select pg_advisory_xact_lock(:space, hashtext(:key))").param("space", space).param("key", key)
			.query().listOfRows()
	}

	private fun fileLockKey(repoKey: String, path: String) = "$repoKey|$path"

	/**
	 * Takes the file locks in the order of the lock numbers themselves, not of the strings: two
	 * keys whose numbers collide are one lock, and only the number order is the same for everyone.
	 */
	private fun lockFiles(keys: Collection<String>) {
		if (keys.isEmpty()) return
		// `array[:keys]` becomes `array[?, ?, …]`: the named parameter expands a collection.
		val numbers = jdbc.sql("select distinct hashtext(k) as h from unnest(array[:keys]) as k order by h")
			.param("keys", keys.distinct()).query(Int::class.java).list()
		for (n in numbers) {
			jdbc.sql("select pg_advisory_xact_lock(:space, :n)").param("space", LOCK_FILE).param("n", n).query().listOfRows()
		}
	}

	private fun sessionLabel(session: SessionRow): String =
		"${short(session.id)}(${session.executor}${session.branch?.let { ", $it" } ?: ""})"

	/** `ses_01J9…7f3c`: enough to tell sessions apart on one screen. */
	private fun short(id: String): String = if (id.length > 12) id.take(8) + "…" + id.takeLast(4) else id

	fun audit(actorKind: String, actorId: String, action: String, targetKind: String?, targetId: String?, details: Map<String, Any?>) {
		jdbc.sql(
			"""
			insert into audit_log (id, actor_kind, actor_id, on_behalf_of_user_id, action, target_kind, target_id, details)
			values (:id, :kind, :actor, :user, :action, :tkind, :tid, cast(:details as jsonb))
			""".trimIndent(),
		).param("id", Ids.audit()).param("kind", actorKind).param("actor", actorId).param("user", OWNER)
			.param("action", action).param("tkind", targetKind).param("tid", targetId)
			.param("details", json.writeValueAsString(details)).update()
	}
}
