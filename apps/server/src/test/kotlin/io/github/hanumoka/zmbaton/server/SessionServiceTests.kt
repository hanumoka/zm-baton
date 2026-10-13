package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract v1 sessions, file conflicts and first-screen items against a real PostgreSQL 17. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SessionServiceTests @Autowired constructor(
	private val sessions: SessionService,
	private val people: PersonService,
	private val jdbc: JdbcClient,
) {

	private data class Registered(val id: String, val token: String, var seq: Long = 0)

	private fun repoKey() = "git:" + UUID.randomUUID().toString().replace("-", "") + "00000000"

	private fun device(): String = sessions.registerDevice(RegisterDevice("test-pc", "windows")).deviceId

	private fun register(
		repoKey: String?,
		device: String = device(),
		executor: String = "claude_code",
		esid: String = UUID.randomUUID().toString(),
		branch: String = "main",
	): Registered {
		val r = sessions.register(
			RegisterSession(
				protocolVersion = 2, deviceId = device, executor = executor, executorVersion = "2.1.296",
				executorSessionId = esid,
				repository = repoKey?.let { RepositoryIn(it, "git@github.com:me/repo.git", "", branch) },
			),
		)
		return Registered(r.sessionId, r.token)
	}

	private fun send(s: Registered, kind: String, payload: Map<String, Any?> = emptyMap(), seq: Long? = null): SessionEventResult {
		val localSeq = seq ?: ++s.seq
		return sessions.ingest(
			s.id, s.token,
			SessionEventBatch(2, listOf(SessionEventIn("evt_" + UUID.randomUUID(), localSeq, kind, payload))),
		)
	}

	private fun touch(s: Registered, repoKey: String, path: String) =
		send(s, "file_touch", mapOf("repo_key" to repoKey, "path" to path, "tool" to "Edit", "content_hash" to "ab12cd34ef"))

	private fun openItems(sourceId: String): List<Map<String, Any?>> =
		jdbc.sql("select bucket, kind, repeat_count, wait_kind, notify from inbox_items where source_id = :id and state = 'open'")
			.param("id", sourceId).query().listOfRows()

	private fun noticesBetween(a: String, b: String): List<Map<String, Any?>> =
		jdbc.sql(
			"""
			select id, status, repeat_count from notices
			 where evidence -> 'sessions' @> jsonb_build_array(jsonb_build_object('session_id', :a))
			   and evidence -> 'sessions' @> jsonb_build_array(jsonb_build_object('session_id', :b))
			""".trimIndent(),
		).param("a", a).param("b", b).query().listOfRows()

	@Test
	fun `register keeps only the token hash and returns a status card`() {
		val s = register(repoKey())
		assertTrue(s.id.startsWith("ses_"))
		assertTrue(s.token.startsWith("zmbs_"))
		val row = jdbc.sql("select token_hash, user_id from agent_sessions where id = :id").param("id", s.id).query().singleRow()
		assertEquals(SessionTokens.hash(s.token), row["token_hash"])
		assertNotEquals(s.token, row["token_hash"])
		val card = sessions.statusCardFor(s.id, s.token)
		assertContains(card, s.id)
		assertContains(card, "나를 기다리는 것")
		assertTrue(card.length <= SessionService.STATUS_CARD_MAX)
	}

	@Test
	fun `registering the same executor session again reuses it and retires the old token`() {
		val dev = device()
		val esid = UUID.randomUUID().toString()
		val first = register(repoKey(), device = dev, esid = esid)
		val second = register(repoKey(), device = dev, esid = esid)
		assertEquals(first.id, second.id)
		assertFailsWith<UnauthorizedException> { sessions.statusCardFor(first.id, first.token) }
		sessions.statusCardFor(second.id, second.token)
		// A different executor session on the same device is a different registration.
		assertNotEquals(first.id, register(repoKey(), device = dev).id)
	}

	@Test
	fun `events need the session's own live token`() {
		val a = register(repoKey())
		val b = register(repoKey())
		assertFailsWith<UnauthorizedException> {
			sessions.ingest(a.id, b.token, SessionEventBatch(2, listOf(SessionEventIn("evt_x" + UUID.randomUUID(), 1, "resumed"))))
		}
		assertFailsWith<BadRequestException> {
			sessions.ingest(a.id, a.token, SessionEventBatch(1, emptyList()))
		}
		send(a, "ended")
		assertFailsWith<UnauthorizedException> { send(a, "resumed") }
	}

	@Test
	fun `two live sessions touching the same file raise one conflict and one first-screen item`() {
		val repo = repoKey()
		val a = register(repo, branch = "main")
		val b = register(repo, branch = "work/x")
		touch(a, repo, "docs/contract.md")
		assertTrue(noticesBetween(a.id, b.id).isEmpty(), "one session alone is not a conflict")
		touch(b, repo, "docs/contract.md")
		val notices = noticesBetween(a.id, b.id)
		assertEquals(1, notices.size)
		val noticeId = notices[0]["id"] as String
		val items = openItems(noticeId)
		assertEquals(1, items.size)
		assertEquals(3, (items[0]["bucket"] as Number).toInt())
		assertEquals("immediate", items[0]["notify"])

		// The same pair and file again is a repeat, not a second notice or item.
		touch(a, repo, "docs/contract.md")
		val again = noticesBetween(a.id, b.id)
		assertEquals(1, again.size)
		assertEquals(1, (again[0]["repeat_count"] as Number).toInt())
		assertEquals(1, openItems(noticeId).size)

		val view = people.sessions(includeEnded = false, limit = 200).single { it.id == a.id }
		assertEquals(listOf("docs/contract.md"), view.conflictedFiles)
	}

	@Test
	fun `no conflict across repositories, across paths, or with a deregistered session`() {
		val repo = repoKey()
		val a = register(repo)
		val other = register(repoKey())
		touch(a, repo, "a.md")
		touch(other, repo, "b.md") // same repo key on the event, different path
		assertTrue(noticesBetween(a.id, other.id).isEmpty())

		val b = register(repoKey())
		touch(b, repoKey(), "a.md") // same path, different repository
		assertTrue(noticesBetween(a.id, b.id).isEmpty())

		val gone = register(repo)
		touch(gone, repo, "c.md")
		send(gone, "deregistered")
		touch(a, repo, "c.md")
		assertTrue(noticesBetween(a.id, gone.id).isEmpty(), "a deregistered session is out of conflict checks")
	}

	@Test
	fun `a wait opens one item, repeats count up, and a later resume closes it`() {
		val s = register(repoKey())
		send(s, "waiting", mapOf("wait_kind" to "permission_prompt", "message" to "Claude needs your permission"))
		send(s, "waiting", mapOf("wait_kind" to "permission_prompt", "message" to "again"))
		val items = openItems(s.id)
		assertEquals(1, items.size)
		assertEquals(2, (items[0]["bucket"] as Number).toInt())
		assertEquals(1, (items[0]["repeat_count"] as Number).toInt())
		assertEquals("immediate", items[0]["notify"])
		send(s, "waiting", mapOf("wait_kind" to "idle_prompt"))
		assertEquals("digest", openItems(s.id).single { it["wait_kind"] == "idle_prompt" }["notify"])
		send(s, "resumed")
		assertTrue(openItems(s.id).isEmpty())
	}

	@Test
	fun `a wait older than the newest resume does not open, and an older resume does not close a newer wait`() {
		val s = register(repoKey())
		send(s, "resumed", seq = 10)
		send(s, "waiting", mapOf("wait_kind" to "idle_prompt"), seq = 7)
		assertTrue(openItems(s.id).isEmpty(), "the session already resumed after this wait")
		send(s, "waiting", mapOf("wait_kind" to "idle_prompt"), seq = 20)
		send(s, "resumed", seq = 15) // arrives late: it happened before the wait
		assertEquals(1, openItems(s.id).size)
		send(s, "resumed", seq = 21)
		assertTrue(openItems(s.id).isEmpty())
	}

	@Test
	fun `current work is limited and shows on another session's card unless not shared`() {
		val repo = repoKey()
		val a = register(repo)
		val b = register(repo)
		val tooLong = send(a, "current_work", mapOf("summary" to "x".repeat(301), "share" to true))
		assertEquals(1, tooLong.rejected)
		send(a, "current_work", mapOf("summary" to "결정 문서 셋 쓰는 중", "refs" to listOf("DEC-0105"), "share" to true))
		assertContains(sessions.statusCardFor(b.id, b.token), "결정 문서 셋 쓰는 중")
		send(a, "current_work", mapOf("summary" to "비밀 작업", "share" to false))
		val card = sessions.statusCardFor(b.id, b.token)
		assertFalse("비밀 작업" in card)
		assertContains(card, "공유 안 함")
		assertNull(people.sessions(false, 200).single { it.id == a.id }.currentWork)
	}

	@Test
	fun `judging a notice closes its item, not a conflict needs a reason, and a resolved conflict that returns opens again`() {
		val repo = repoKey()
		val a = register(repo)
		val b = register(repo)
		touch(a, repo, "x.kt")
		touch(b, repo, "x.kt")
		val noticeId = noticesBetween(a.id, b.id).single()["id"] as String
		assertFailsWith<BadRequestException> { people.judgeNotice(noticeId, NoticeStatusIn("not_a_conflict")) }
		people.judgeNotice(noticeId, NoticeStatusIn("resolved"))
		assertTrue(openItems(noticeId).isEmpty())
		touch(b, repo, "x.kt")
		assertEquals("open", noticesBetween(a.id, b.id).single()["status"])
		assertEquals(1, openItems(noticeId).size)
		people.judgeNotice(noticeId, NoticeStatusIn("not_a_conflict", "다른 함수를 고친다"))
		touch(a, repo, "x.kt")
		assertEquals("not_a_conflict", noticesBetween(a.id, b.id).single()["status"], "a judged non-conflict stays judged")
		assertTrue(openItems(noticeId).isEmpty())
	}

	@Test
	fun `absolute and climbing paths are refused, and credentials never reach the database`() {
		val repo = repoKey()
		val s = register(repo)
		for (bad in listOf("C:/Users/me/x.md", "/home/me/x.md", "..\\x.md", "../x.md", "a/../b.md", "a\\b.md", "")) {
			assertEquals(1, touch(s, repo, bad).rejected, "path $bad")
		}
		assertFailsWith<BadRequestException> {
			sessions.register(
				RegisterSession(2, device(), "claude_code", null, UUID.randomUUID().toString(), RepositoryIn(repo, null, "C:/work", "main")),
			)
		}
		val withCreds = sessions.register(
			RegisterSession(
				2, device(), "codex", null, UUID.randomUUID().toString(),
				RepositoryIn(repo, "https://user:secret@github.com/me/repo.git", "apps/web", "main"),
			),
		)
		val stored = jdbc.sql("select repository::text from agent_sessions where id = :id")
			.param("id", withCreds.sessionId).query(String::class.java).single()
		assertFalse("secret" in stored)
		assertContains(stored, "https://github.com/me/repo.git")
	}

	@Test
	fun `events after the end in one batch are refused`() {
		val s = register(repoKey())
		val result = sessions.ingest(
			s.id, s.token,
			SessionEventBatch(
				2,
				listOf(
					SessionEventIn("evt_" + UUID.randomUUID(), 1, "ended"),
					SessionEventIn("evt_" + UUID.randomUUID(), 2, "resumed"),
				),
			),
		)
		assertEquals(1, result.stored)
		assertEquals(1, result.rejected)
	}

	@Test
	fun `the sweeper ends an expired session and closes its waits`() {
		val s = register(repoKey())
		send(s, "waiting", mapOf("wait_kind" to "idle_prompt"))
		jdbc.sql("update agent_sessions set token_expires_at = now() - interval '1 minute' where id = :id")
			.param("id", s.id).update()
		assertTrue(sessions.endExpired() >= 1)
		assertTrue(openItems(s.id).isEmpty())
		assertEquals("ended", people.sessions(true, 200).first { it.id == s.id }.state)
		assertTrue(people.sessions(false, 200).none { it.id == s.id }, "an ended session is not listed as live")
	}

	private fun storedRemote(remote: String): String? {
		val r = sessions.register(
			RegisterSession(2, device(), "codex", null, UUID.randomUUID().toString(), RepositoryIn(repoKey(), remote, "", "main")),
		)
		return jdbc.sql("select repository ->> 'remote' from agent_sessions where id = :id")
			.param("id", r.sessionId).query(String::class.java).optional().orElse(null)
	}

	@Test
	fun `remotes keep only host and path, whatever their length or shape`() {
		val longSecret = "https://" + "SYNTHETIC_".repeat(60) + "@example.invalid/repo.git"
		assertEquals("https://example.invalid/repo.git", storedRemote(longSecret))
		assertEquals("https://example.invalid/repo.git", storedRemote("https://example.invalid/repo.git?access_token=SYNTHETIC_TOKEN#frag"))
		assertEquals("ssh://host:2222/me/r.git", storedRemote("ssh://deploy:SYNTHETIC@host:2222/me/r.git"))
		assertEquals("github.com:me/repo.git", storedRemote("SYNTHETIC_USER@github.com:me/repo.git"))
		assertNull(storedRemote("not a remote with spaces"))
		assertNull(storedRemote("https://example.invalid/" + "x".repeat(600)), "too long after cleaning: dropped, not cut")
	}

	@Test
	fun `an older current work that arrives late changes nothing`() {
		val repo = repoKey()
		val a = register(repo)
		val b = register(repo)
		send(a, "current_work", mapOf("summary" to "공개 요약", "share" to true), seq = 3)
		send(a, "current_work", mapOf("summary" to "비공개였던 요약", "share" to false), seq = 2)
		send(a, "current_work", mapOf("summary" to "처음 요약", "share" to true), seq = 1)
		val card = sessions.statusCardFor(b.id, b.token)
		assertContains(card, "공개 요약")
		assertFalse("비공개였던 요약" in card)
		assertFalse("처음 요약" in card)
		assertEquals("공개 요약", people.sessions(false, 200).single { it.id == a.id }.currentWork)
	}

	@Test
	fun `every summary states its sharing, so a late older one cannot publish a private one`() {
		val repo = repoKey()
		val a = register(repo)
		val b = register(repo)
		// Real order: shared 1, sharing turned off 2, private 3. Arrival order: 1, 3, 2.
		send(a, "current_work", mapOf("summary" to "공개 요약", "share" to true), seq = 1)
		assertEquals(1, send(a, "current_work", mapOf("summary" to "설정 없는 요약"), seq = 3).rejected, "share is required")
		send(a, "current_work", mapOf("summary" to "공유를 끈 뒤 요약", "share" to false), seq = 3)
		send(a, "current_work", mapOf("summary" to "공유를 끄며 쓴 요약", "share" to false), seq = 2)
		val card = sessions.statusCardFor(b.id, b.token)
		assertFalse("공유를 끈 뒤 요약" in card)
		assertFalse("공유를 끄며 쓴 요약" in card)
		assertContains(card, "공유 안 함")
		assertNull(people.sessions(false, 200).single { it.id == a.id }.currentWork)
	}

	@Test
	fun `registering again after expiry closes the expired registration's waits`() {
		val dev = device()
		val esid = UUID.randomUUID().toString()
		val old = register(repoKey(), device = dev, esid = esid)
		send(old, "waiting", mapOf("wait_kind" to "idle_prompt"))
		jdbc.sql("update agent_sessions set token_expires_at = now() - interval '1 minute' where id = :id")
			.param("id", old.id).update()
		val renewed = register(repoKey(), device = dev, esid = esid)
		assertNotEquals(old.id, renewed.id)
		assertTrue(openItems(old.id).isEmpty(), "the expired registration's wait must close with it")
	}

	@Test
	fun `a late older wait report only counts, and a resume between two reports starts a new wait`() {
		val s = register(repoKey())
		fun openRow() = jdbc.sql(
			"select summary, wait_first_seq, wait_seq, waiting_since from inbox_items where source_id = :id and state = 'open'",
		).param("id", s.id).query().singleRow()
		// Real order: wait 10, resume 20, wait 30. Arrival order: 10, 30, 20.
		send(s, "waiting", mapOf("wait_kind" to "idle_prompt", "message" to "첫 기다림"), seq = 10)
		val firstSince = (openRow()["waiting_since"] as java.sql.Timestamp).toInstant()
		Thread.sleep(30)
		send(s, "waiting", mapOf("wait_kind" to "idle_prompt", "message" to "새 기다림"), seq = 30)
		assertEquals(10L, (openRow()["wait_first_seq"] as Number).toLong())
		send(s, "resumed", seq = 20)
		val after = openRow()
		assertEquals(30L, (after["wait_first_seq"] as Number).toLong(), "the wait reported at 30 is a new wait")
		assertTrue((after["waiting_since"] as java.sql.Timestamp).toInstant().isAfter(firstSince), "it waits since its own report")
		assertContains(after["summary"] as String, "새 기다림")
		// A late report older than the newest one joins the wait; it does not bring back old words.
		send(s, "waiting", mapOf("wait_kind" to "idle_prompt", "message" to "늦게 온 옛 문구"), seq = 25)
		val late = openRow()
		assertContains(late["summary"] as String, "새 기다림")
		assertEquals(25L, (late["wait_first_seq"] as Number).toLong())
		assertEquals(after["waiting_since"], late["waiting_since"], "the server first heard of this wait at 30")
	}

	@Test
	fun `with several resumes and waits in any arrival order the wait starts at the first report after the newest resume`() {
		val s = register(repoKey())
		fun openRow() = jdbc.sql(
			"select wait_first_seq, wait_seq, repeat_count, waiting_since from inbox_items where source_id = :id and state = 'open'",
		).param("id", s.id).query().singleRow()
		fun receivedAt(seq: Long) = jdbc.sql("select received_at from session_events where session_id = :id and local_seq = :seq")
			.param("id", s.id).param("seq", seq).query(java.sql.Timestamp::class.java).single()
		// Real order: wait 10, resume 20, wait 30, wait 40, resume 50, wait 60, wait 70.
		// Arrival order: 10, 30, 40, 60, 70, 20, 50.
		for (seq in listOf(10L, 30L, 40L, 60L, 70L)) {
			send(s, "waiting", mapOf("wait_kind" to "idle_prompt"), seq = seq)
			Thread.sleep(5)
		}
		send(s, "resumed", seq = 20)
		assertEquals(30L, (openRow()["wait_first_seq"] as Number).toLong())
		send(s, "resumed", seq = 50)
		val row = openRow()
		assertEquals(60L, (row["wait_first_seq"] as Number).toLong())
		assertEquals(70L, (row["wait_seq"] as Number).toLong())
		assertEquals(1, (row["repeat_count"] as Number).toInt(), "60 and 70 are one wait reported twice")
		assertEquals(receivedAt(60), row["waiting_since"])
	}

	@Test
	fun `two batches touching two files in opposite orders neither deadlock nor double the notices`() {
		val pool = Executors.newFixedThreadPool(2)
		try {
			repeat(15) {
				val repo = repoKey()
				val a = register(repo)
				val b = register(repo)
				val start = CountDownLatch(1)
				fun batch(s: Registered, first: String, second: String) = SessionEventBatch(
					2,
					listOf(first, second).map { path ->
						SessionEventIn("evt_" + UUID.randomUUID(), ++s.seq, "file_touch", mapOf("repo_key" to repo, "path" to path))
					},
				)
				val futures = listOf(a to batch(a, "x.md", "y.md"), b to batch(b, "y.md", "x.md")).map { (s, events) ->
					pool.submit<SessionEventResult> {
						start.await()
						sessions.ingest(s.id, s.token, events)
					}
				}
				start.countDown()
				futures.forEach { assertEquals(2, it.get(30, TimeUnit.SECONDS).stored) }
				val notices = noticesBetween(a.id, b.id)
				assertEquals(2, notices.size, "one notice per file")
				for (n in notices) assertEquals(1, openItems(n["id"] as String).size)
			}
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `two files whose lock numbers collide are locked in number order, not in path order`() {
		val repo = repoKey()
		// Two paths of this repository whose lock numbers are equal, found by trying many paths.
		val (first, third) = jdbc.sql(
			"""
			select min(p), max(p) from (
			  select format('f%s.md', lpad(g::text, 7, '0')) as p from generate_series(1, 300000) g
			) t group by hashtext(:repo || '|' || p) having count(*) = 2 limit 1
			""".trimIndent(),
		).param("repo", repo).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		fun number(path: String) = jdbc.sql("select hashtext(:k)").param("k", "$repo|$path").query(Int::class.java).single()
		// A path between the two in path order whose number differs: sorting paths would lock
		// first -> second in one batch and second -> third (= first's number) in the other.
		val second = jdbc.sql(
			"""
			select p from (
			  select format('f%s.md', lpad(g::text, 7, '0')) as p from generate_series(1, 300000) g
			) t where p > :a and p < :b and hashtext(:repo || '|' || p) <> hashtext(:repo || '|' || :a) limit 1
			""".trimIndent(),
		).param("a", first).param("b", third).param("repo", repo).query(String::class.java).single()
		assertEquals(number(first), number(third))
		assertNotEquals(number(first), number(second))
		val pool = Executors.newFixedThreadPool(2)
		try {
			repeat(15) {
				val a = register(repo)
				val b = register(repo)
				val start = CountDownLatch(1)
				fun batch(s: Registered, vararg paths: String) = SessionEventBatch(
					2,
					paths.map { path ->
						SessionEventIn("evt_" + UUID.randomUUID(), ++s.seq, "file_touch", mapOf("repo_key" to repo, "path" to path))
					},
				)
				val futures = listOf(a to batch(a, first, second), b to batch(b, second, third)).map { (s, events) ->
					pool.submit<SessionEventResult> {
						start.await()
						sessions.ingest(s.id, s.token, events)
					}
				}
				start.countDown()
				futures.forEach { assertEquals(2, it.get(30, TimeUnit.SECONDS).stored) }
				assertEquals(1, noticesBetween(a.id, b.id).size, "only the second path is shared")
			}
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `concurrent touches of one file by two sessions raise exactly one notice`() {
		val pool = Executors.newFixedThreadPool(2)
		try {
			repeat(15) {
				val repo = repoKey()
				val a = register(repo)
				val b = register(repo)
				val start = CountDownLatch(1)
				val futures = listOf(a, b).map { s ->
					pool.submit {
						start.await()
						touch(s, repo, "same.md")
					}
				}
				start.countDown()
				futures.forEach { it.get(30, TimeUnit.SECONDS) }
				assertEquals(1, noticesBetween(a.id, b.id).size)
			}
		} finally {
			pool.shutdownNow()
		}
	}
}
