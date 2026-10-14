package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.auth.Access
import io.github.hanumoka.zmbaton.server.auth.AccessKind
import io.github.hanumoka.zmbaton.server.auth.AuthSettings
import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.Permissions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Contract v1 "권한 요청과 선택형 질문을 첫 화면으로 중계한다": a directly opened Claude Code session's
// permission request (PermissionRequest hook) or multiple-choice question (PreToolUse on
// AskUserQuestion) becomes a first-screen item; a person answers it with one conditional update;
// the hook takes the answer with another. Anything that does not line up gives no decision, and
// the executor asks in its terminal. Nothing here answers on its own: an answer is only ever what
// a person pressed. Also the person's focus mode and first-screen visibility ("알림과 집중 모드").
//
// Relays are off unless the server starts with ZM_BATON_RELAY=on. Until person authentication
// exists, any program on this PC can call the first screen's API, so an AI session with a shell
// could answer another session's permission request (hardening backlog H-21). While off, the
// server makes no relay, takes no answer, and the hook ends without a decision.

data class RelayRequest(
	@JsonProperty("protocol_version") val protocolVersion: Int,
	/** A new id for every hook call (relay 2.1). */
	@JsonProperty("relay_id") val relayId: String,
	/** `permission_request` or `session_question`. */
	val kind: String,
	/** SHA-256 of the tool name and the whole tool input (relay 2.2); the hook sends it again to take the answer. */
	@JsonProperty("input_fingerprint") val inputFingerprint: String,
	/** The session's local sequence number at the request, so a later `resumed` can close it. */
	@JsonProperty("local_seq") val localSeq: Long,
	/** What a person judges (relay 2.3). Null when the hook withheld it. */
	val detail: Map<String, Any?>? = null,
	/** Why the hook kept the detail back: `secret_suspected` (relay 3) or `too_large`. Such a request is not relayed. */
	val withheld: String? = null,
)

data class RelayStarted(
	/** True: the hook waits for an answer until [deadline]. False: the hook ends without a decision at once. */
	val relay: Boolean,
	@JsonProperty("item_id") val itemId: String?,
	/** Why it was not relayed: secret_suspected, too_large, focus_mode or not_visible. */
	val reason: String?,
	val deadline: OffsetDateTime?,
	@JsonProperty("wait_seconds") val waitSeconds: Int?,
)

data class RelayTake(
	@JsonProperty("protocol_version") val protocolVersion: Int,
	@JsonProperty("input_fingerprint") val inputFingerprint: String,
)

data class RelayTaken(
	/** `delivered` (the answer is in [answer]), `waiting` (ask again), `over` or `mismatch` (no decision). */
	val outcome: String,
	val state: String,
	val answer: Map<String, Any?>?,
)

/** A person's answer: [decision] and [reason] for a permission request, [answers] (question to chosen labels) for a question. */
data class RelayAnswerIn(
	val decision: String? = null,
	val reason: String? = null,
	val answers: Map<String, List<String>>? = null,
)

data class RelayAnswered(val id: String, val state: String)

data class MeView(
	@JsonProperty("user_id") val userId: String,
	@JsonProperty("focus_on") val focusOn: Boolean,
	@JsonProperty("focus_changed_at") val focusChangedAt: OffsetDateTime?,
	@JsonProperty("first_screen_visible") val firstScreenVisible: Boolean,
	@JsonProperty("relay_wait_seconds") val relayWaitSeconds: Int,
	/** Whether relays are on (ZM_BATON_RELAY=on). It is a server setting; no screen turns it on. */
	@JsonProperty("relay_enabled") val relayEnabled: Boolean,
)

data class FocusIn(val on: Boolean)

/**
 * Whether relays are on: the server setting `zm-baton.relay` (environment ZM_BATON_RELAY), `on`
 * or anything else for off, and only while people sign in (`ZM_BATON_AUTH=required`). Without sign-in
 * any program on this PC could answer a relay (H-21), so in `local` mode `on` is ignored with a warning
 * and relays stay off. It is read at start only, so no request can turn it on.
 */
@Component
class RelaySwitch(@Value("\${zm-baton.relay:off}") setting: String, auth: AuthSettings) {
	private val asked: Boolean = setting.trim().equals("on", ignoreCase = true)
	val enabled: Boolean = asked && auth.required

	init {
		if (asked && !auth.required) {
			LoggerFactory.getLogger(RelaySwitch::class.java).warn(
				"ZM_BATON_RELAY=on is ignored: relays need ZM_BATON_AUTH=required, so that only a signed-in person answers them " +
					"(docs/hardening-backlog.md H-21). Relays stay off.",
			)
		}
	}
}

data class FirstScreenIn(val visible: Boolean)

@Service
class RelayService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val sessions: SessionService,
	private val relaySwitch: RelaySwitch,
	private val facts: PermissionFacts,
) {
	companion object {
		val KINDS = setOf("permission_request", "session_question")
		val WITHHELD = setOf("secret_suspected", "too_large")
		const val DEFAULT_WAIT_SECONDS = 60
		const val MIN_WAIT_SECONDS = 10
		/** The hook gives up after 80 seconds (`zm-baton hook permission-request`); the server's deadline comes first. */
		const val MAX_WAIT_SECONDS = 70
		const val DEFAULT_DETAIL_RETENTION_DAYS = 7
		/** A visibility signal counts this long; the first screen sends one every 15 seconds while it is visible. */
		const val VISIBLE_SECONDS = 30
		const val DETAIL_MAX = 65_536
		const val REASON_MAX = 500
		/** A closed relay stays on the first screen this long, so the person sees how it ended. */
		const val RECENT_MINUTES = 10
		private const val KIND_LIST = "('permission_request', 'session_question')"
		private val RELAY_ID = Regex("^rly_[A-Za-z0-9_-]{8,100}$")
		private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
		private val TOOL_NAME = Regex("^[A-Za-z0-9_.:-]{1,100}$")
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	// ---------------------------------------------------------------- the hook's side (session token)

	/** A hook asks to relay. The first screen gets an item unless focus mode turns a permission request away. */
	@Transactional
	fun start(sessionId: String, token: String, req: RelayRequest): RelayStarted {
		sessions.requireProtocol(req.protocolVersion)
		val relayId = req.relayId.trim()
		if (!RELAY_ID.matches(relayId)) throw BadRequestException("relay_id must look like rly_<8-100 letters, digits, _ or ->")
		if (req.kind !in KINDS) throw BadRequestException("kind must be one of $KINDS")
		if (!FINGERPRINT.matches(req.inputFingerprint)) throw BadRequestException("input_fingerprint must be 64 lower-case hex digits")
		if (req.localSeq < 1) throw BadRequestException("local_seq must be 1 or more")
		if (req.withheld != null && req.withheld !in WITHHELD) throw BadRequestException("withheld must be one of $WITHHELD")
		// Off: nothing is made and nothing of the request is kept; the hook ends without a decision.
		if (!relaySwitch.enabled) {
			sessions.authenticate(sessionId, token, forUpdate = false)
			return RelayStarted(false, null, "relay_off", null, null)
		}
		val detail = if (req.withheld != null) null else cleanDetail(req.kind, req.detail)
		// The row lock orders this against the session's own end in another transaction.
		val session = sessions.authenticate(sessionId, token, forUpdate = true)

		// The same relay id again (a retried request) gets the same answer, never a second item.
		data class Earlier(val source: String, val deadline: OffsetDateTime?, val reason: String?, val id: String, val state: String)
		val earlier = jdbc.sql("select source_id, relay_deadline, relay_reason, id, state from inbox_items where relay_id = :rid")
			.param("rid", relayId).query { rs, _ ->
				Earlier(rs.getString(1), rs.getObject(2, OffsetDateTime::class.java), rs.getString(3), rs.getString(4), rs.getString(5))
			}.optional().orElse(null)
		if (earlier != null) {
			if (earlier.source != session.id) throw ConflictException("relay_id is already used by another session")
			return RelayStarted(earlier.deadline != null && earlier.state == "open", earlier.id, earlier.reason, earlier.deadline, null)
		}

		// The relay waits for the session's person: their focus mode and first screen decide.
		val focus = jdbc.sql("select focus_on, coalesce(first_screen_visible_until > now(), false) from users where id = :user")
			.param("user", session.userId).query { rs, _ -> rs.getBoolean(1) to rs.getBoolean(2) }.single()
		val (focusOn, visible) = focus
		val label = sessions.sessionLabel(session)
		val notRelayed = when {
			req.withheld != null -> req.withheld
			req.kind == "permission_request" && focusOn -> "focus_mode"
			req.kind == "session_question" && focusOn -> "focus_mode"
			req.kind == "session_question" && !visible -> "not_visible"
			else -> null
		}
		// Focus mode sends a new permission request straight to the terminal (relay 7). The terminal
		// asks at the same time, and its wait already reaches the first screen through the
		// notification hook, so no second item is made for it.
		if (notRelayed == "focus_mode" && req.kind == "permission_request") {
			audit(session.id, "relay.not_relayed", null, mapOf("relay_id" to relayId, "kind" to req.kind, "reason" to notRelayed))
			return RelayStarted(false, null, notRelayed, null, null)
		}
		val wait = waitSeconds(jdbc.sql("select org_id from agent_sessions where id = :id").param("id", session.id).query(String::class.java).single())
		val itemId = Ids.inbox()
		val summary = (listOf(label) + summaryOf(req.kind, detail, notRelayed)).joinToString(" · ").take(300)
		val deadline = jdbc.sql(
			"""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, detail, notify,
			                         relay_id, input_fingerprint, relay_deadline, relay_seq, relay_reason)
			values (:id, :user, :bucket, :kind, 'ses', :session, :summary, cast(:detail as jsonb), 'immediate',
			        :rid, :fp, case when :relayed then now() + make_interval(secs => :wait) end, :seq, :reason)
			returning relay_deadline
			""".trimIndent(),
		).param("id", itemId).param("user", session.userId).param("bucket", if (req.kind == "permission_request") 1 else 2)
			.param("kind", req.kind).param("session", session.id).param("summary", summary)
			.param("detail", detail?.let { json.writeValueAsString(it) }).param("rid", relayId).param("fp", req.inputFingerprint)
			.param("relayed", notRelayed == null).param("wait", wait).param("seq", req.localSeq).param("reason", notRelayed)
			.query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) }.list().firstOrNull()
		audit(
			session.id, if (notRelayed == null) "relay.requested" else "relay.not_relayed", itemId,
			mapOf("relay_id" to relayId, "kind" to req.kind, "tool_name" to detail?.get("tool_name"), "reason" to notRelayed),
		)
		return RelayStarted(notRelayed == null, itemId, notRelayed, deadline, if (notRelayed == null) wait else null)
	}

	/**
	 * The hook takes the answer (relay 5): one conditional update from `answered` to `delivered`,
	 * only before the deadline, with the same relay id and fingerprint, while the session lives.
	 */
	@Transactional
	fun take(sessionId: String, token: String, relayId: String, req: RelayTake): RelayTaken {
		sessions.requireProtocol(req.protocolVersion)
		if (!FINGERPRINT.matches(req.inputFingerprint)) throw BadRequestException("input_fingerprint must be 64 lower-case hex digits")
		val session = sessions.authenticate(sessionId, token, forUpdate = true)
		// Turned off since the relay started (a restart without ZM_BATON_RELAY=on): no answer goes out.
		if (!relaySwitch.enabled) return RelayTaken("over", "relay_off", null)
		val taken = jdbc.sql(
			"""
			update inbox_items i set state = 'delivered', delivered_at = now()
			 where i.relay_id = :rid and i.source_id = :session and i.state = 'answered' and i.relay_deadline > now()
			   and i.input_fingerprint = :fp
			   and exists (select 1 from agent_sessions a where a.id = i.source_id and a.ended_at is null
			                 and a.deregistered_at is null and a.token_expires_at > now())
			returning i.id, i.answer::text
			""".trimIndent(),
		).param("rid", relayId).param("session", session.id).param("fp", req.inputFingerprint)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.list().firstOrNull()
		if (taken != null) {
			val answer = json.readValue(taken.second, mapType)
			audit(session.id, "relay.delivered", taken.first, mapOf("relay_id" to relayId, "decision" to answer["decision"]))
			return RelayTaken("delivered", "delivered", answer)
		}
		data class Row(val state: String, val expired: Boolean, val fingerprint: String?)
		val row = jdbc.sql(
			"""
			select state, coalesce(relay_deadline <= now(), false), input_fingerprint from inbox_items
			 where relay_id = :rid and source_id = :session
			""".trimIndent(),
		).param("rid", relayId).param("session", session.id)
			.query { rs, _ -> Row(rs.getString(1), rs.getBoolean(2), rs.getString(3)) }.optional()
			.orElseThrow { NotFoundException("no relay $relayId in this session") }
		if (row.fingerprint != req.inputFingerprint) return RelayTaken("mismatch", row.state, null)
		if (row.state in setOf("open", "answered") && row.expired) {
			closeExpired(relayId)
			val state = jdbc.sql("select state from inbox_items where relay_id = :rid").param("rid", relayId).query(String::class.java).single()
			return RelayTaken("over", state, null)
		}
		return RelayTaken(if (row.state == "open" || row.state == "answered") "waiting" else "over", row.state, null)
	}

	// ---------------------------------------------------------------- the person's side

	/**
	 * A person's answer (relay 4): one conditional update from `open` to `answered`, only before the
	 * deadline and while the session lives. A late answer is refused with what happened instead.
	 */
	@Transactional
	fun answer(itemId: String, body: RelayAnswerIn, person: String = Requester.STAGE1_PERSON): RelayAnswered {
		val (kind, detailText) = jdbc.sql("select kind, detail::text from inbox_items where id = :id and user_id = :user")
			.param("id", itemId).param("user", person).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional()
			.orElseThrow { NotFoundException("no first-screen item $itemId") }
		if (kind !in KINDS) throw BadRequestException("only a permission request or a question is answered here")
		if (!relaySwitch.enabled) throw ConflictException("중계가 꺼져 있다(ZM_BATON_RELAY). 그 세션의 터미널에서 답한다")
		val answer = buildAnswer(kind, detailText?.let { json.readValue(it, mapType) }, body)
		val updated = jdbc.sql(
			"""
			update inbox_items i set state = 'answered', answer = cast(:answer as jsonb), answered_by = :user, answered_at = now()
			 where i.id = :id and i.state = 'open' and i.relay_deadline > now()
			   and exists (select 1 from agent_sessions a where a.id = i.source_id and a.ended_at is null
			                 and a.deregistered_at is null and a.token_expires_at > now())
			""".trimIndent(),
		).param("answer", json.writeValueAsString(answer)).param("user", person).param("id", itemId).update()
		if (updated == 0) throw ConflictException(lateReason(itemId))
		sessions.audit(
			"person", person, "relay.answered", "inb", itemId,
			mapOf("kind" to kind, "decision" to answer["decision"], "questions" to (answer["answers"] as Map<*, *>?)?.size),
		)
		return RelayAnswered(itemId, "answered")
	}

	/** A person sends an open relay to the terminal: the hook stops waiting and gives no decision. */
	@Transactional
	fun toTerminal(itemId: String, person: String = Requester.STAGE1_PERSON): RelayAnswered {
		val updated = jdbc.sql(
			"""
			update inbox_items set state = 'moved_to_terminal', relay_reason = 'person', closed_at = now()
			 where id = :id and user_id = :user and kind in $KIND_LIST and state = 'open' and relay_deadline > now()
			""".trimIndent(),
		).param("id", itemId).param("user", person).update()
		if (updated == 0) throw ConflictException(lateReason(itemId))
		sessions.audit("person", person, "relay.to_terminal", "inb", itemId, emptyMap())
		return RelayAnswered(itemId, "moved_to_terminal")
	}

	/** Why an answer can no longer be taken, for the person. */
	private fun lateReason(itemId: String): String {
		data class Row(val state: String, val relayed: Boolean, val expired: Boolean, val live: Boolean)
		val row = jdbc.sql(
			"""
			select i.state, i.relay_deadline is not null, coalesce(i.relay_deadline <= now(), false),
			       exists (select 1 from agent_sessions a where a.id = i.source_id and a.ended_at is null
			                 and a.deregistered_at is null and a.token_expires_at > now())
			  from inbox_items i where i.id = :id
			""".trimIndent(),
		).param("id", itemId).query { rs, _ -> Row(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4)) }
			.optional().orElseThrow { NotFoundException("no first-screen item $itemId") }
		return when {
			!row.relayed -> "중계하지 않은 항목이다. 그 세션의 터미널에서 답한다"
			row.state == "answered" || row.state == "delivered" -> "이미 답했다"
			row.state == "open" && !row.live -> "이미 터미널로 넘어갔다. 세션이 끝났다"
			row.state == "open" && row.expired -> "이미 터미널로 넘어갔다. 기한이 지났다"
			row.state == "moved_to_terminal" -> "이미 터미널로 넘어갔다"
			row.state == "undelivered" -> "답이 실행기에 전달되지 않았다. 터미널에서 답한다"
			else -> "이미 닫혔다"
		}
	}

	fun me(person: String = Requester.STAGE1_PERSON): MeView =
		jdbc.sql("select focus_on, focus_changed_at, coalesce(first_screen_visible_until > now(), false) from users where id = :user")
			.param("user", person).query { rs, _ ->
				MeView(
					person, rs.getBoolean(1), rs.getObject(2, OffsetDateTime::class.java), rs.getBoolean(3), waitSeconds(facts.orgOf(person)),
					relaySwitch.enabled,
				)
			}.single()

	/** Turns focus mode on or off. Relays already waiting keep their deadline (relay 7). */
	@Transactional
	fun setFocus(on: Boolean, person: String = Requester.STAGE1_PERSON): MeView {
		val changed = jdbc.sql("update users set focus_on = :on, focus_changed_at = now() where id = :user and focus_on <> :on")
			.param("on", on).param("user", person).update()
		if (changed == 1) sessions.audit("person", person, "focus.changed", "usr", person, mapOf("on" to on))
		return me(person)
	}

	/** The first screen says whether its tab is visible (relay 12); the signal lasts [VISIBLE_SECONDS]. */
	@Transactional
	fun setFirstScreen(visible: Boolean, person: String = Requester.STAGE1_PERSON): MeView {
		jdbc.sql(
			"""
			update users set first_screen_visible_until = case when :visible then now() + make_interval(secs => :secs) end
			 where id = :user
			""".trimIndent(),
		).param("visible", visible).param("secs", VISIBLE_SECONDS).param("user", person).update()
		return me(person)
	}

	// ---------------------------------------------------------------- deadlines and retention

	/** Past the deadline an unanswered relay goes to the terminal and an untaken answer is 「전달 안 됨」 (relay 6). */
	@Transactional
	fun closeExpired(relayId: String? = null): Int =
		jdbc.sql(
			"""
			update inbox_items
			   set state = case state when 'open' then 'moved_to_terminal' else 'undelivered' end,
			       relay_reason = 'deadline', closed_at = now()
			 where kind in $KIND_LIST and state in ('open', 'answered') and relay_deadline <= now()
			   and (cast(:rid as text) is null or relay_id = :rid)
			""".trimIndent(),
		).param("rid", relayId).update()

	/** A detail the secret check missed must not stay: it goes [DEFAULT_DETAIL_RETENTION_DAYS] days after the item closed (relay 3). */
	@Transactional
	fun purgeDetails(): Int =
		jdbc.sql(
			"""
			update inbox_items set detail = null
			 where kind in $KIND_LIST and detail is not null and state in ('moved_to_terminal', 'undelivered', 'closed')
			   and closed_at < now() - make_interval(days => :days)
			""".trimIndent(),
		).param("days", setting(null, "relay_detail_retention_days", DEFAULT_DETAIL_RETENTION_DAYS, 1, 3650)).update()

	// ---------------------------------------------------------------- helpers

	/** The organisation's `relay_wait_seconds` (data model "organizations"), within what the hook waits. */
	fun waitSeconds(orgId: String): Int = setting(orgId, "relay_wait_seconds", DEFAULT_WAIT_SECONDS, MIN_WAIT_SECONDS, MAX_WAIT_SECONDS)

	/** An organisation's setting; [orgId] null: the server's first organisation (a sweep that serves them all). */
	private fun setting(orgId: String?, key: String, default: Int, min: Int, max: Int): Int =
		jdbc.sql(
			"""
			select settings ->> :key from organizations
			 where id = coalesce(cast(:org as text), (select o.id from organizations o order by o.created_at, o.id limit 1))
			""".trimIndent(),
		).param("key", key).param("org", orgId)
			.query(String::class.java).optional().orElse(null)?.toIntOrNull()?.coerceIn(min, max) ?: default

	/** The detail as stored: a tool name, and for a question its questions with their options. */
	private fun cleanDetail(kind: String, detail: Map<String, Any?>?): Map<String, Any?> {
		if (detail == null) throw BadRequestException("detail is required unless the hook withheld it")
		val toolName = detail["tool_name"] as? String
		if (toolName == null || !TOOL_NAME.matches(toolName)) throw BadRequestException("detail.tool_name must be a tool name")
		if (json.writeValueAsString(detail).length > DETAIL_MAX) throw BadRequestException("detail is longer than $DETAIL_MAX characters")
		if (kind == "session_question") questions(detail)
		return detail
	}

	private data class Question(val text: String, val labels: List<String>, val multi: Boolean)

	/** The questions of an AskUserQuestion detail: 1-4, each with its text and option labels. */
	private fun questions(detail: Map<String, Any?>): List<Question> {
		val raw = detail["questions"] as? List<*> ?: throw BadRequestException("detail.questions must be a list")
		if (raw.isEmpty() || raw.size > 4) throw BadRequestException("detail.questions must hold 1-4 questions")
		val list = raw.map { q ->
			val m = q as? Map<*, *> ?: throw BadRequestException("each question must be an object")
			val text = (m["question"] as? String)?.takeIf { it.isNotBlank() && it.length <= 2000 }
				?: throw BadRequestException("each question needs its text")
			val options = m["options"] as? List<*> ?: throw BadRequestException("each question needs options")
			if (options.isEmpty() || options.size > 10) throw BadRequestException("a question has 1-10 options")
			val labels = options.map { o ->
				((o as? Map<*, *>)?.get("label") as? String)?.takeIf { it.isNotBlank() && it.length <= 300 }
					?: throw BadRequestException("each option needs a label")
			}
			Question(text, labels, m["multiSelect"] == true)
		}
		if (list.map { it.text }.toSet().size != list.size) throw BadRequestException("two questions have the same text")
		return list
	}

	/** Checks a person's answer against what was asked and returns what the hook will take. */
	private fun buildAnswer(kind: String, detail: Map<String, Any?>?, body: RelayAnswerIn): Map<String, Any?> {
		if (kind == "permission_request") {
			if (body.answers != null) throw BadRequestException("a permission request takes decision, not answers")
			return when (body.decision) {
				"allow" -> {
					if (!body.reason.isNullOrBlank()) throw BadRequestException("a reason goes with deny only")
					mapOf("decision" to "allow")
				}
				"deny" -> {
					val reason = body.reason?.trim()?.ifEmpty { null }
					if (reason != null && reason.length > REASON_MAX) throw BadRequestException("the reason is at most $REASON_MAX characters")
					if (reason == null) mapOf("decision" to "deny") else mapOf("decision" to "deny", "reason" to reason)
				}
				else -> throw BadRequestException("decision must be allow or deny")
			}
		}
		if (body.decision != null) throw BadRequestException("a question takes answers, not a decision")
		val given = body.answers ?: throw BadRequestException("answers are required")
		// A question that was not relayed has no detail to answer against, and is refused later anyway.
		val asked = detail?.let { questions(it) } ?: throw ConflictException("중계하지 않은 항목이다. 그 세션의 터미널에서 답한다")
		if (given.keys != asked.map { it.text }.toSet()) throw BadRequestException("answer every question, and only those")
		val answers = linkedMapOf<String, List<String>>()
		for (q in asked) {
			val chosen = given.getValue(q.text)
			if (chosen.isEmpty() || chosen.toSet().size != chosen.size) throw BadRequestException("choose at least one option, each once")
			if (!q.multi && chosen.size != 1) throw BadRequestException("choose one option for \"${q.text.take(60)}\"")
			if (chosen.any { it !in q.labels }) throw BadRequestException("choose among the options that were asked")
			// In the order they were offered, so the joined answer reads like the question.
			answers[q.text] = q.labels.filter { it in chosen }
		}
		return mapOf("answers" to answers)
	}

	/** The list line after the session: the tool and its command or file, the first question, or why it waits in the terminal. */
	private fun summaryOf(kind: String, detail: Map<String, Any?>?, notRelayed: String?): List<String> {
		val parts = mutableListOf<String>()
		when (notRelayed) {
			"secret_suspected" -> return listOf("비밀값이 섞여 터미널에서 확인")
			"too_large" -> return listOf("상세가 길어 터미널에서 확인")
		}
		if (kind == "permission_request") {
			val tool = detail?.get("tool_name") as? String ?: "도구"
			val what = (detail?.get("command") as? String ?: detail?.get("file_path") as? String)
				?.lineSequence()?.firstOrNull()?.trim()
			parts += if (what.isNullOrEmpty()) "$tool 권한 요청" else "$tool: $what"
		} else {
			val first = ((detail?.get("questions") as? List<*>)?.firstOrNull() as? Map<*, *>)?.get("question") as? String
			parts += first?.replace(Regex("\\s+"), " ")?.trim() ?: "선택형 질문"
			when (notRelayed) {
				"focus_mode" -> parts += "집중 모드라 터미널에서 답함"
				"not_visible" -> parts += "첫 화면이 보이지 않아 터미널에서 답함"
			}
		}
		return parts
	}

	private fun audit(sessionId: String, action: String, itemId: String?, details: Map<String, Any?>) =
		sessions.audit("session", sessionId, action, if (itemId == null) "ses" else "inb", itemId ?: sessionId, details)
}

@RestController
@RequestMapping("/api")
class RelayController(private val relays: RelayService, private val permissions: Permissions) {

	/** The PermissionRequest or PreToolUse hook of a directly opened session asks to relay. */
	@Access(AccessKind.SESSION)
	@PostMapping("/sessions/{id}/relays")
	fun start(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: RelayRequest,
	): RelayStarted = relays.start(id, token(authorization), body)

	/** The hook takes the answer, or learns that it waits on or that the terminal decides. */
	@Access(AccessKind.SESSION)
	@PostMapping("/sessions/{id}/relays/{relayId}/take")
	fun take(
		@PathVariable id: String,
		@PathVariable relayId: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: RelayTake,
	): RelayTaken = relays.take(id, token(authorization), relayId, body)

	/** A person answers on the first screen. A session token cannot (contract "등록과 세션 토큰" 6). */
	@PostMapping("/inbox/{id}/answer")
	fun answer(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: RelayAnswerIn,
	): RelayAnswered {
		val me = permissions.person(authorization, "answer a relayed request")
		permissions.require(me, Act.RELAY_ANSWER, permissions.inboxItem(id))
		return relays.answer(id, body, me.person!!)
	}

	@PostMapping("/inbox/{id}/to-terminal")
	fun toTerminal(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): RelayAnswered {
		val me = permissions.person(authorization, "send a relayed request to the terminal")
		permissions.require(me, Act.RELAY_ANSWER, permissions.inboxItem(id))
		return relays.toTerminal(id, me.person!!)
	}

	@GetMapping("/me")
	fun me(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): MeView =
		relays.me(permissions.person(authorization, "read my settings", Act.READ).person!!)

	@PutMapping("/me/focus")
	fun focus(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: FocusIn,
	): MeView = relays.setFocus(body.on, permissions.person(authorization, "turn focus mode on or off", Act.READ).person!!)

	@PutMapping("/me/first-screen")
	fun firstScreen(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: FirstScreenIn,
	): MeView = relays.setFirstScreen(body.visible, permissions.person(authorization, "report the first screen's visibility", Act.READ).person!!)

	private fun token(authorization: String?): String =
		SessionTokens.bearer(authorization) ?: throw UnauthorizedException("a session token is required")
}

/** Closes relays past their deadline every few seconds, and drops old details once an hour. */
@Component
class RelaySweeper(private val relays: RelayService) {
	@Scheduled(fixedDelayString = "\${zm-baton.relay-sweep-ms:5000}", initialDelayString = "\${zm-baton.relay-sweep-ms:5000}")
	fun sweep() {
		relays.closeExpired()
	}

	@Scheduled(fixedDelayString = "\${zm-baton.relay-purge-ms:3600000}", initialDelayString = "\${zm-baton.relay-purge-first-ms:120000}")
	fun purge() {
		relays.purgeDetails()
	}
}
