package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Retention (FN-1404, G13.1) against a real PostgreSQL 17. Old rows are written with times far in
 * the past, so a period of a year deletes only them and never the rows other tests make now.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RetentionTests @Autowired constructor(
	private val retention: RetentionService,
	private val props: RetentionProperties,
	private val sessions: SessionService,
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
) {
	private val longAgo = OffsetDateTime.parse("2001-02-03T04:05:06Z")

	/** One record of every kind, all [at] old, plus the ids to look them up by. */
	private data class Records(
		val audit: String, val session: String, val sessionEvent: String, val inbox: String, val notice: String,
		val run: String, val runEvent: String, val openInbox: String, val liveSession: String,
	)

	private fun count(table: String, column: String, id: String): Long =
		jdbc.sql("select count(*) from $table where $column = :id").param("id", id).query(Long::class.javaObjectType).single()

	private fun records(at: OffsetDateTime): Records {
		val n = UUID.randomUUID().toString().replace("-", "")
		val device = sessions.registerDevice(RegisterDevice("retention-pc", "windows"), person = SessionService.OWNER).deviceId
		val audit = "aud_old_$n"
		jdbc.sql("insert into audit_log (id, actor_kind, actor_id, action, at) values (:id, 'person', 'usr_owner', 'test.old', :at)")
			.param("id", audit).param("at", at).update()
		fun session(id: String, ended: OffsetDateTime?) =
			jdbc.sql(
				"""
				insert into agent_sessions (id, user_id, device_id, executor, executor_session_id, token_hash, token_expires_at,
				                            created_at, last_seen_at, ended_at)
				values (:id, 'usr_owner', :device, 'claude_code', :esid, :hash, :expires, :at, :at, :ended)
				""".trimIndent(),
			).param("id", id).param("device", device).param("esid", id).param("hash", "h_$id")
				.param("expires", if (ended == null) OffsetDateTime.now().plusHours(12) else at.plusHours(12))
				.param("at", at).param("ended", ended).update()
		val session = "ses_old_$n"
		session(session, at)
		// Live although it registered long ago: retention never touches a live session.
		val liveSession = "ses_live_$n"
		session(liveSession, null)
		val sessionEvent = "evt_old_$n"
		jdbc.sql(
			"""
			insert into session_events (event_id, session_id, local_seq, kind, payload, received_at)
			values (:id, :session, 1, 'file_touch', '{"repo_key":"git:x","path":"a.md"}', :at)
			""".trimIndent(),
		).param("id", sessionEvent).param("session", session).param("at", at).update()
		fun inbox(id: String, state: String) =
			jdbc.sql(
				"""
				insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, notify, state, created_at, closed_at, waiting_since)
				values (:id, 'usr_owner', 2, 'session_waiting', 'ses', :src, 'old', 'digest', :state, :at, :closed, :at)
				""".trimIndent(),
			).param("id", id).param("src", "ses_src_$id").param("state", state).param("at", at)
				.param("closed", if (state == "open") null else at).update()
		val inbox = "inb_old_$n"
		inbox(inbox, "closed")
		val openInbox = "inb_open_$n"
		inbox(openInbox, "open")
		val notice = "ntc_old_$n"
		jdbc.sql(
			"""
			insert into notices (id, kind, evidence, dedupe_key, recipients, status, status_at, created_at, last_seen_at)
			values (:id, 'work_conflict', '{}', :key, array['usr_owner'], 'resolved', :at, :at, :at)
			""".trimIndent(),
		).param("id", notice).param("key", "dedupe_$n").param("at", at).update()
		val work = "wrk_old_$n"
		jdbc.sql("insert into work_items (id, title, stage, responsible_user_id, created_at, updated_at) values (:id, 'old', 'done', 'usr_owner', :at, :at)")
			.param("id", work).param("at", at).update()
		val run = "run_old_$n"
		jdbc.sql(
			"""
			insert into runs (id, work_item_id, generation, state, end_reason, executor, created_at, ended_at)
			values (:id, :work, 1, 'ended', 'submitted', 'claude_code', :at, :at)
			""".trimIndent(),
		).param("id", run).param("work", work).param("at", at).update()
		val runEvent = "evt_run_old_$n"
		jdbc.sql(
			"""
			insert into run_events (event_id, run_id, generation, seq, kind, payload, received_at)
			values (:id, :run, 1, 1, 'answer', '{}', :at)
			""".trimIndent(),
		).param("id", runEvent).param("run", run).param("at", at).update()
		return Records(audit, session, sessionEvent, inbox, notice, run, runEvent, openInbox, liveSession)
	}

	private fun present(r: Records) = mapOf(
		"audit" to count("audit_log", "id", r.audit),
		"session" to count("agent_sessions", "id", r.session),
		"sessionEvent" to count("session_events", "event_id", r.sessionEvent),
		"inbox" to count("inbox_items", "id", r.inbox),
		"notice" to count("notices", "id", r.notice),
		"run" to count("runs", "id", r.run),
		"runEvent" to count("run_events", "event_id", r.runEvent),
		"openInbox" to count("inbox_items", "id", r.openInbox),
		"liveSession" to count("agent_sessions", "id", r.liveSession),
	)

	private fun purgeRows(): Long =
		jdbc.sql("select count(*) from audit_log where action = :a").param("a", RetentionService.ACTION).query(Long::class.javaObjectType).single()

	private val everyKindAYear = RetentionKind.entries.associateWith { 365 }

	@Test
	fun `by default every kind is kept forever, and the daily check deletes and writes nothing`() {
		assertEquals(RetentionProperties.KEEP_EVERYTHING, props, "empty settings in application.properties bind as null")
		assertTrue(props.configured().isEmpty())
		assertTrue(retention.view().kinds.all { it.days == null })
		val old = records(longAgo)
		val purges = purgeRows()

		assertNull(retention.runIfDue())
		assertNull(retention.runIfDue(OffsetDateTime.now().plusYears(100)))
		val nothing = retention.purge(emptyMap(), OffsetDateTime.now())
		assertTrue(nothing.deleted.isEmpty())

		assertTrue(present(old).values.all { it == 1L }, "nothing was deleted: ${present(old)}")
		assertEquals(purges, purgeRows(), "nothing ran, so nothing was audited")
	}

	@Test
	fun `a period deletes only records older than it, keeps open and live ones, and audits the counts`() {
		val old = records(longAgo)
		val recent = records(OffsetDateTime.now().minusDays(1))
		val now = OffsetDateTime.now()

		val run = retention.purge(everyKindAYear, now)

		val gone = present(old)
		assertEquals(0L, gone["audit"])
		assertEquals(0L, gone["session"])
		assertEquals(0L, gone["sessionEvent"])
		assertEquals(0L, gone["inbox"])
		assertEquals(0L, gone["notice"])
		assertEquals(0L, gone["runEvent"])
		assertEquals(1L, gone["run"], "run rows are work history and stay")
		assertEquals(1L, gone["openInbox"], "an open first-screen item stays however old")
		assertEquals(1L, gone["liveSession"], "a live session stays however old")
		assertTrue(present(recent).values.all { it == 1L }, "records newer than a year stay: ${present(recent)}")

		for (table in listOf("audit_log", "agent_sessions", "session_events", "inbox_items", "notices", "run_events")) {
			assertTrue((run.deleted[table] ?: 0) >= 1, "$table counted in ${run.deleted}")
		}
		val row = jdbc.sql("select actor_kind, actor_id, details::text from audit_log where action = :a order by at desc, id desc limit 1")
			.param("a", RetentionService.ACTION).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
		assertEquals("system", row.first)
		assertEquals(RetentionService.ACTOR, row.second)
		val details = json.readValue(row.third, Map::class.java)
		assertEquals(365, (details["days"] as Map<*, *>)["audit_log"])
		assertEquals(run.deleted["audit_log"], ((details["deleted"] as Map<*, *>)["audit_log"] as Number).toInt())
	}

	@Test
	fun `one kind with a period deletes only that kind`() {
		val old = records(longAgo)
		retention.purge(mapOf(RetentionKind.CLOSED_INBOX_ITEMS to 30), OffsetDateTime.now())
		val after = present(old)
		assertEquals(0L, after["inbox"])
		assertEquals(1L, after["audit"])
		assertEquals(1L, after["session"])
		assertEquals(1L, after["notice"])
		assertEquals(1L, after["runEvent"])
	}

	@Test
	fun `with a period set the check runs at most once a day`() {
		val service = RetentionService(jdbc, json, sessions, RetentionProperties(36500, null, null, null, null))
		// A time far ahead: older runs of other tests are more than a day before it.
		val later = OffsetDateTime.now().plusYears(10)
		val first = service.runIfDue(later)
		assertNotNull(first)
		assertEquals(mapOf("audit_log" to 36500), first.days)
		assertNull(service.runIfDue(later.plusHours(23)))
		assertNotNull(service.runIfDue(later.plusHours(25)))
		assertEquals(later.plusHours(25).toInstant(), service.view().lastRun!!.ranAt.toInstant())
	}

	@Test
	fun `a period below one day is refused at startup`() {
		val e = assertFailsWith<IllegalArgumentException> { RetentionProperties(0, null, null, null, null) }
		assertTrue(e.message!!.contains("ZM_BATON_RETENTION_AUDIT_LOG_DAYS"))
	}
}
