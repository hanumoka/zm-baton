package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.Permissions
import io.github.hanumoka.zmbaton.server.permission.ScopeColumns
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

// A manager per person (FN-0402, DEC-0099 3): not an AI session kept running, but one record a
// person's AI sessions belong to: who the person is, the current status card their sessions load at
// start, the work they hold, what they did, their own notes and the sessions linked to them. Notes
// are personal: only their writer reads them, an operator included (DEC-0103 2), and the export
// leaves them out.

data class ManagerPerson(val id: String, @JsonProperty("display_name") val displayName: String, val status: String)

data class OrgRef(val id: String, val name: String)

data class TeamRef(val id: String, val name: String)

data class ManagerNoteView(
	val id: String,
	val body: String,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("updated_at") val updatedAt: OffsetDateTime,
)

/** One of my sessions. Unlike the shared session list, my own unshared 「지금 하는 일」 shows here. */
data class OwnSessionView(
	val id: String,
	val executor: String,
	@JsonProperty("device_name") val deviceName: String,
	@JsonProperty("repo_key") val repoKey: String?,
	val branch: String?,
	@JsonProperty("current_work") val currentWork: String?,
	val shared: Boolean,
	@JsonProperty("current_work_at") val currentWorkAt: OffsetDateTime?,
	/** `live`, `ended`, `deregistered` or `expired`. */
	val state: String,
	@JsonProperty("last_seen_at") val lastSeenAt: OffsetDateTime,
)

/** A line of the work record: what the person or one of their sessions did. */
data class ActivityEntry(
	val action: String,
	@JsonProperty("actor_kind") val actorKind: String,
	@JsonProperty("actor_id") val actorId: String,
	@JsonProperty("target_kind") val targetKind: String?,
	@JsonProperty("target_id") val targetId: String?,
	val at: OffsetDateTime,
)

data class ManagerRecord(
	val person: ManagerPerson,
	@JsonProperty("manager_id") val managerId: String,
	val org: OrgRef,
	val roles: List<String>,
	val teams: List<TeamRef>,
	/** The card the person's next session gets, without the repository parts a session adds. */
	@JsonProperty("status_card") val statusCard: String,
	/** Work items the person holds: responsible for, or executing themselves or through their manager. */
	val work: List<WorkItemView>,
	val sessions: List<OwnSessionView>,
	val notes: List<ManagerNoteView>,
	val activity: List<ActivityEntry>,
)

data class NoteIn(val body: String)

/**
 * The current status card (contract "현재 상태 카드"), built from a person's record: their first-screen
 * counts, the open work items they hold with their next action, and, for a session in a repository,
 * the newest decisions recorded there that the person may see and the other live sessions there.
 * At most [SessionService.STATUS_CARD_MAX] characters: when it does not fit, the next actions go
 * first, then the card is cut before its last line.
 */
@Component
class StatusCards(private val jdbc: JdbcClient, private val facts: PermissionFacts) {

	/** The card of a registered session: its person's, in its repository. */
	fun forSession(sessionId: String): String {
		val (person, repoKey) = jdbc.sql("select user_id, repo_key from agent_sessions where id = :id").param("id", sessionId)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		return card(person, sessionId, repoKey)
	}

	/** What a person's next session would load, outside any repository (the manager record shows it). */
	fun preview(personId: String): String = card(personId, null, null)

	fun card(personId: String, sessionId: String?, repoKey: String?): String {
		val actor = facts.person(personId)
		val who = listOfNotNull(personId, actor.manager)
		val counts = jdbc.sql(
			"select bucket, count(*) from inbox_items where user_id = :user and state = 'open' group by bucket",
		).param("user", personId).query { rs, _ -> rs.getInt(1) to rs.getLong(2) }.list().toMap()
		val holds = "(responsible_user_id = :user or executor_id in (:who)) and stage not in ('done', 'cancelled')"
		val openCount = jdbc.sql("select count(*) from work_items where $holds").param("user", personId).param("who", who)
			.query(Long::class.javaObjectType).single()
		// Work under way first; among equals the most recently changed.
		val work = jdbc.sql(
			"""
			select external_ref, title, stage, wait_reasons, next_action from work_items
			 where $holds
			 order by (stage = 'in_progress') desc, updated_at desc, opened_on desc nulls last, id desc limit ${SessionService.CARD_WORK_ITEMS}
			""".trimIndent(),
		).param("user", personId).param("who", who).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			val reasons = (rs.getArray(4).array as Array<String>).toList()
			val head = "- ${rs.getString(1)?.let { "$it " } ?: ""}${clip(rs.getString(2), 60)} (${Workflow.label(rs.getString(3))}" +
				(if (reasons.isEmpty()) "" else " · ${reasons.joinToString(", ") { Workflow.waitLabel(it) }}") + ")"
			head to rs.getString(5)?.let { "  다음: ${clip(it, 80)}" }
		}.list()
		val decisions = if (repoKey == null) emptyList() else {
			val scope = PermissionRules.filter(actor, ScopeColumns.DECISIONS)
			val stmt = jdbc.sql(
				"""
				select d.external_ref, d.decided_on, d.question from decisions d where d.git_ref ->> 'repo_key' = :repo and ${scope.sql}
				 order by d.decided_on desc nulls last, d.external_ref desc limit ${SessionService.CARD_DECISIONS}
				""".trimIndent(),
			).param("repo", repoKey)
			scope.params.forEach { (k, v) -> stmt.param(k, v) }
			stmt.query { rs, _ -> "- ${rs.getString(1) ?: ""} (${rs.getString(2) ?: "날짜 없음"}) ${clip(rs.getString(3), 60)}" }.list()
		}
		val others = if (repoKey == null || sessionId == null) emptyList() else jdbc.sql(
			"""
			select id, executor, repository ->> 'branch', current_work, share_current_work from agent_sessions
			 where repo_key = :repo and id <> :id and org_id = :org and ended_at is null and deregistered_at is null and token_expires_at > now()
			 order by last_seen_at desc limit 5
			""".trimIndent(),
		).param("repo", repoKey).param("id", sessionId).param("org", actor.orgId ?: "").query { rs, _ ->
			val work = if (rs.getBoolean(5)) rs.getString(4) ?: "아직 올리지 않음" else "공유 안 함"
			"- ${short(rs.getString(1))}(${rs.getString(2)}${rs.getString(3)?.let { ", $it" } ?: ""}): $work"
		}.list()

		fun compose(withNext: Boolean): String {
			val lines = mutableListOf<String>()
			lines += if (sessionId != null) {
				"[zm-baton] 이 세션을 등록했다: $sessionId (등록을 끄려면 `zm-baton session off`)"
			} else {
				"[zm-baton] 상태 카드 미리보기: 다음 세션이 시작할 때 받는다. 저장소의 최근 결정과 다른 세션은 그 저장소에서 붙는다"
			}
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
		val budget = SessionService.STATUS_CARD_MAX - last.length - 1
		val full = compose(withNext = true)
		val body = if (full.length <= budget) full else compose(withNext = false).take(budget)
		return "$body\n$last"
	}

	/** [s] in at most [max] characters, ending in "…" when cut. */
	private fun clip(s: String, max: Int): String {
		val flat = s.replace(Regex("\\s+"), " ").trim()
		return if (flat.length > max) flat.take(max - 1) + "…" else flat
	}

	/** `ses_01J9…7f3c`, as SessionService shows sessions. */
	private fun short(id: String): String = if (id.length > 12) id.take(8) + "…" + id.takeLast(4) else id
}

@Service
class ManagerService(
	private val jdbc: JdbcClient,
	private val facts: PermissionFacts,
	private val cards: StatusCards,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
) {
	companion object {
		const val NOTE_MAX = 2000
		const val WORK_MAX = 30
		const val ACTIVITY_MAX = 30
	}

	/** The manager record of [personId], as that person sees it. */
	fun record(personId: String): ManagerRecord {
		val actor = facts.person(personId)
		val orgId = actor.orgId ?: throw NotFoundException("$personId is not a member of any organisation")
		val managerId = actor.manager ?: throw NotFoundException("$personId has no manager record")
		val person = jdbc.sql("select id, display_name, status from users where id = :id").param("id", personId)
			.query { rs, _ -> ManagerPerson(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
		val org = jdbc.sql("select id, name from organizations where id = :id").param("id", orgId)
			.query { rs, _ -> OrgRef(rs.getString(1), rs.getString(2)) }.single()
		val teams = if (actor.teams.isEmpty()) emptyList() else jdbc.sql("select id, name from teams where id in (:ids) order by name, id")
			.param("ids", actor.teams.toList()).query { rs, _ -> TeamRef(rs.getString(1), rs.getString(2)) }.list()
		return ManagerRecord(
			person, managerId, org, actor.roles.map { it.wire }.sorted(), teams, cards.preview(personId),
			workItems.involving(personId, managerId, WORK_MAX), ownSessions(personId), notes(managerId), activity(personId),
		)
	}

	@Transactional
	fun addNote(personId: String, body: NoteIn): ManagerNoteView {
		val manager = managerOf(personId)
		val id = Ids.managerNote()
		jdbc.sql("insert into manager_notes (id, manager_id, body) values (:id, :manager, :body)")
			.param("id", id).param("manager", manager).param("body", text(body.body)).update()
		// The audit row is readable by operators: it names the note, never its words.
		sessions.audit("person", personId, "manager_note.added", "mnt", id, emptyMap())
		return note(id)
	}

	@Transactional
	fun updateNote(personId: String, id: String, body: NoteIn): ManagerNoteView {
		val changed = jdbc.sql("update manager_notes set body = :body, updated_at = now() where id = :id and manager_id = :manager")
			.param("body", text(body.body)).param("id", id).param("manager", managerOf(personId)).update()
		if (changed == 0) throw NotFoundException("no note $id")
		sessions.audit("person", personId, "manager_note.changed", "mnt", id, emptyMap())
		return note(id)
	}

	@Transactional
	fun deleteNote(personId: String, id: String) {
		val deleted = jdbc.sql("delete from manager_notes where id = :id and manager_id = :manager")
			.param("id", id).param("manager", managerOf(personId)).update()
		if (deleted == 0) throw NotFoundException("no note $id")
		sessions.audit("person", personId, "manager_note.deleted", "mnt", id, emptyMap())
	}

	private fun managerOf(personId: String): String =
		facts.managerOf(personId, facts.orgOf(personId)) ?: throw NotFoundException("$personId has no manager record")

	private fun text(raw: String): String {
		val t = raw.trim()
		if (t.isEmpty() || t.length > NOTE_MAX) throw BadRequestException("a note is 1-$NOTE_MAX characters")
		return t
	}

	private fun note(id: String): ManagerNoteView =
		jdbc.sql("select id, body, created_at, updated_at from manager_notes where id = :id").param("id", id)
			.query { rs, _ -> noteView(rs) }.single()

	private fun notes(managerId: String): List<ManagerNoteView> =
		jdbc.sql("select id, body, created_at, updated_at from manager_notes where manager_id = :m order by updated_at desc, id desc")
			.param("m", managerId).query { rs, _ -> noteView(rs) }.list()

	private fun noteView(rs: java.sql.ResultSet) = ManagerNoteView(
		rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime::class.java), rs.getObject(4, OffsetDateTime::class.java),
	)

	/** The person's sessions, live ones first, then the most recent; at most 20. */
	private fun ownSessions(personId: String): List<OwnSessionView> =
		jdbc.sql(
			"""
			select a.id, a.executor, d.name, a.repo_key, a.repository ->> 'branch', a.current_work, a.share_current_work, a.current_work_at,
			       case when a.deregistered_at is not null then 'deregistered'
			            when a.ended_at is not null then 'ended'
			            when a.token_expires_at <= now() then 'expired'
			            else 'live' end as state,
			       a.last_seen_at
			  from agent_sessions a join devices d on d.id = a.device_id
			 where a.user_id = :p
			 order by (a.ended_at is null and a.deregistered_at is null and a.token_expires_at > now()) desc, a.last_seen_at desc
			 limit 20
			""".trimIndent(),
		).param("p", personId).query { rs, _ ->
			OwnSessionView(
				rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getBoolean(7),
				rs.getObject(8, OffsetDateTime::class.java), rs.getString(9), rs.getObject(10, OffsetDateTime::class.java),
			)
		}.list()

	/** What the person and their sessions did, newest first (the work record, DEC-0099 3.2). */
	private fun activity(personId: String): List<ActivityEntry> =
		jdbc.sql(
			"""
			select action, actor_kind, actor_id, target_kind, target_id, at from audit_log
			 where (actor_kind = 'person' and actor_id = :p)
			    or (actor_kind = 'session' and actor_id in (select id from agent_sessions where user_id = :p))
			 order by at desc, id desc limit $ACTIVITY_MAX
			""".trimIndent(),
		).param("p", personId).query { rs, _ ->
			ActivityEntry(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getObject(6, OffsetDateTime::class.java))
		}.list()
}

@RestController
@RequestMapping("/api/me")
class ManagerController(private val managers: ManagerService, private val permissions: Permissions) {

	/** My manager record. Only ever my own: there is no path to another person's notes. */
	@GetMapping("/record")
	fun record(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): ManagerRecord {
		val me = permissions.person(authorization, "read my manager record", Act.READ)
		return managers.record(me.person!!)
	}

	@PostMapping("/notes")
	@ResponseStatus(HttpStatus.CREATED)
	fun addNote(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: NoteIn,
	): ManagerNoteView {
		val me = permissions.person(authorization, "write a note", Act.READ)
		return managers.addNote(me.person!!, body)
	}

	@PatchMapping("/notes/{id}")
	fun updateNote(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: NoteIn,
	): ManagerNoteView {
		val me = permissions.person(authorization, "change a note")
		permissions.require(me, Act.OWN_RECORD, permissions.note(id))
		return managers.updateNote(me.person!!, id, body)
	}

	@DeleteMapping("/notes/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	fun deleteNote(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?) {
		val me = permissions.person(authorization, "delete a note")
		permissions.require(me, Act.OWN_RECORD, permissions.note(id))
		managers.deleteNote(me.person!!, id)
	}
}
