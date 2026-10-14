package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract v1 "권한 요청과 선택형 질문을 첫 화면으로 중계한다" and the boundary cases its "구현 PR이
 * 시험으로 확인할 경계 상황" names (1, 2, 3, 8, 11, 12), against a real PostgreSQL 17.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RelayTests @Autowired constructor(
	private val sessions: SessionService,
	private val relays: RelayService,
	private val people: PersonService,
	private val jdbc: JdbcClient,
) {
	private data class Registered(val id: String, val token: String, var seq: Long = 100)

	private val fp = "ab".repeat(32)
	private val otherFp = "cd".repeat(32)

	@AfterEach
	fun restore() {
		relays.setFocus(false)
		relays.setFirstScreen(false)
	}

	private fun register(): Registered {
		val device = sessions.registerDevice(RegisterDevice("relay-pc", "windows")).deviceId
		val r = sessions.register(
			RegisterSession(2, device, "claude_code", null, UUID.randomUUID().toString(), RepositoryIn("git:" + UUID.randomUUID().toString().replace("-", ""), null, "", "main")),
		)
		return Registered(r.sessionId, r.token)
	}

	private fun send(s: Registered, kind: String, seq: Long = ++s.seq) =
		sessions.ingest(s.id, s.token, SessionEventBatch(2, listOf(SessionEventIn("evt_" + UUID.randomUUID(), seq, kind))))

	private fun relayId() = "rly_" + UUID.randomUUID().toString().replace("-", "")

	private val bash = mapOf(
		"tool_name" to "Bash", "command" to "rm -rf build && npm run build", "description" to "Rebuild",
		"input" to mapOf("command" to "rm -rf build && npm run build", "description" to "Rebuild"),
		"work_location" to mapOf("path" to "apps/web", "branch" to "main"),
	)

	private val question = mapOf(
		"tool_name" to "AskUserQuestion",
		"questions" to listOf(
			mapOf(
				"question" to "Which color?", "header" to "Color", "multiSelect" to false,
				"options" to listOf(mapOf("label" to "Red", "description" to "warm"), mapOf("label" to "Blue", "description" to "cool")),
			),
			mapOf(
				"question" to "Which sizes?", "header" to "Size", "multiSelect" to true,
				"options" to listOf(mapOf("label" to "S"), mapOf("label" to "M"), mapOf("label" to "L")),
			),
		),
	)

	private fun start(
		s: Registered, kind: String = "permission_request", detail: Map<String, Any?>? = bash, withheld: String? = null,
		id: String = relayId(), fingerprint: String = fp,
	): Pair<String, RelayStarted> = id to relays.start(s.id, s.token, RelayRequest(2, id, kind, fingerprint, ++s.seq, detail, withheld))

	private fun RelayStarted.item(): String = assertNotNull(itemId)

	private fun take(s: Registered, relayId: String, fingerprint: String = fp) = relays.take(s.id, s.token, relayId, RelayTake(2, fingerprint))

	private fun row(itemId: String): Map<String, Any?> =
		jdbc.sql("select state, relay_reason, detail::text as detail, summary, delivered_at, bucket, notify from inbox_items where id = :id")
			.param("id", itemId).query().singleRow()

	private fun expire(itemId: String) =
		jdbc.sql("update inbox_items set relay_deadline = now() - interval '1 second' where id = :id").param("id", itemId).update()

	@Test
	fun `a permission request opens a first-screen item, a person allows it, and the hook takes the answer once`() {
		val s = register()
		val (rid, started) = start(s)
		assertTrue(started.relay)
		assertEquals(60, started.waitSeconds)
		val itemId = started.item()
		assertEquals(1, (row(itemId)["bucket"] as Number).toInt())
		assertEquals("immediate", row(itemId)["notify"])
		assertContains(row(itemId)["summary"] as String, "Bash: rm -rf build && npm run build")
		val listed = people.inbox().single { it.id == itemId }
		assertEquals("open", listed.state)
		assertNotNull(listed.relayDeadline)
		assertEquals("rm -rf build && npm run build", listed.detail!!["command"], "the whole command is shown")

		assertEquals("waiting", take(s, rid).outcome, "nothing to take before a person answers")
		relays.answer(itemId, RelayAnswerIn(decision = "allow"))
		assertEquals("answered", row(itemId)["state"])
		assertEquals("answered", people.inbox().single { it.id == itemId }.state, "answered but not yet delivered stays visible")

		assertEquals("mismatch", take(s, rid, otherFp).outcome, "another input's fingerprint takes nothing")
		assertEquals("answered", row(itemId)["state"])
		val taken = take(s, rid)
		assertEquals("delivered", taken.outcome)
		assertEquals(mapOf("decision" to "allow"), taken.answer)
		assertEquals("over", take(s, rid).outcome, "an answer is taken once")
		assertEquals("delivered", people.inbox().single { it.id == itemId }.state)
		assertFailsWith<ConflictException> { relays.answer(itemId, RelayAnswerIn(decision = "deny")) }

		// A resume older than the relay arrives late: nothing closes. The next one closes it.
		send(s, "resumed", seq = 1)
		assertEquals("delivered", row(itemId)["state"])
		send(s, "resumed")
		assertEquals("closed", row(itemId)["state"])
		assertNotNull(row(itemId)["delivered_at"], "the item still says the answer was delivered")
		assertEquals("closed", people.inbox().single { it.id == itemId }.state, "a closed relay shows for a while")
	}

	@Test
	fun `a deny carries its reason to the hook, and an allow takes no reason`() {
		val s = register()
		val (rid, started) = start(s)
		assertFailsWith<BadRequestException> { relays.answer(started.item(), RelayAnswerIn(decision = "allow", reason = "x")) }
		assertFailsWith<BadRequestException> { relays.answer(started.item(), RelayAnswerIn(decision = "maybe")) }
		relays.answer(started.item(), RelayAnswerIn(decision = "deny", reason = "빌드 폴더는 지우지 않는다"))
		assertEquals(mapOf("decision" to "deny", "reason" to "빌드 폴더는 지우지 않는다"), take(s, rid).answer)
	}

	@Test
	fun `boundary 1 - an answer pressed before the deadline but taken after it is not delivered`() {
		val s = register()
		val (rid, started) = start(s)
		relays.answer(started.item(), RelayAnswerIn(decision = "allow"))
		expire(started.item())
		val taken = take(s, rid)
		assertEquals("over", taken.outcome)
		assertEquals("undelivered", taken.state)
		assertNull(taken.answer, "no decision reaches the executor; it asks in the terminal")
		assertEquals("deadline", row(started.item())["relay_reason"])
	}

	@Test
	fun `an answer after the deadline is refused and says the request went to the terminal`() {
		val s = register()
		val (rid, started) = start(s)
		expire(started.item())
		val refused = assertFailsWith<ConflictException> { relays.answer(started.item(), RelayAnswerIn(decision = "allow")) }
		assertContains(refused.message!!, "이미 터미널로 넘어갔다")
		assertEquals("open", row(started.item())["state"], "the refusal itself writes nothing")
		assertEquals(1, relays.closeExpired())
		assertEquals("moved_to_terminal", row(started.item())["state"])
		assertEquals("over", take(s, rid).outcome)
	}

	@Test
	fun `the sweeper closes an open relay as moved to the terminal and an untaken answer as undelivered`() {
		val s = register()
		val open = start(s).second.item()
		val answered = start(s).second.item()
		relays.answer(answered, RelayAnswerIn(decision = "allow"))
		expire(open)
		expire(answered)
		relays.closeExpired()
		assertEquals("moved_to_terminal", row(open)["state"])
		assertEquals("undelivered", row(answered)["state"])
	}

	@Test
	fun `boundary 2 - the session ends after the answer and before the take`() {
		val s = register()
		val (rid, answered) = start(s)
		val open = start(s).second.item()
		relays.answer(answered.item(), RelayAnswerIn(decision = "allow"))
		send(s, "ended")
		assertEquals("undelivered", row(answered.item())["state"])
		assertEquals("closed", row(open)["state"])
		assertEquals("session_ended", row(open)["relay_reason"])
		assertFailsWith<UnauthorizedException> { take(s, rid) }
		assertContains(assertFailsWith<ConflictException> { relays.answer(open, RelayAnswerIn(decision = "allow")) }.message!!, "닫혔다")
	}

	@Test
	fun `boundary 2 - turning the session off closes its relays too`() {
		val s = register()
		val (_, answered) = start(s)
		relays.answer(answered.item(), RelayAnswerIn(decision = "deny"))
		send(s, "deregistered")
		assertEquals("undelivered", row(answered.item())["state"])
	}

	@Test
	fun `boundary 3 - focus mode sends new permission requests to the terminal and leaves waiting ones until their deadline`() {
		val s = register()
		val (rid, waiting) = start(s)
		relays.setFocus(true)
		val (_, turnedAway) = start(s)
		assertFalse(turnedAway.relay)
		assertEquals("focus_mode", turnedAway.reason)
		assertNull(turnedAway.itemId, "the terminal asks, and its wait reaches the first screen through the notification hook")
		relays.answer(waiting.item(), RelayAnswerIn(decision = "allow"))
		assertEquals("delivered", take(s, rid).outcome)
		relays.setFocus(false)
		assertTrue(start(s).second.relay)
	}

	@Test
	fun `boundary 8 - a request whose input looked like it held a secret keeps no detail and cannot be answered here`() {
		val s = register()
		val (_, started) = start(s, detail = mapOf("tool_name" to "Bash", "command" to "curl -H 'Authorization: Bearer SYNTHETIC'"), withheld = "secret_suspected")
		assertFalse(started.relay)
		assertEquals("secret_suspected", started.reason)
		val r = row(started.item())
		assertNull(r["detail"], "the detail is not stored even when the hook sent one by mistake")
		assertFalse((r["summary"] as String).contains("SYNTHETIC"))
		assertContains(r["summary"] as String, "비밀값이 섞여 터미널에서 확인")
		assertEquals("open", r["state"])
		assertContains(assertFailsWith<ConflictException> { relays.answer(started.item(), RelayAnswerIn(decision = "allow")) }.message!!, "중계하지 않은")
		send(s, "resumed")
		assertEquals("closed", row(started.item())["state"], "it leaves the first screen when the session moves on")
	}

	@Test
	fun `boundary 12 - a question is relayed only while the first screen is visible, and the chosen options go back`() {
		val s = register()
		val (_, hidden) = start(s, kind = "session_question", detail = question)
		assertFalse(hidden.relay)
		assertEquals("not_visible", hidden.reason)
		val left = row(hidden.item())
		assertEquals(2, (left["bucket"] as Number).toInt())
		assertContains(left["summary"] as String, "Which color?", message = "the first screen says which session asks what")
		assertContains(assertFailsWith<ConflictException> {
			relays.answer(hidden.item(), RelayAnswerIn(answers = mapOf("Which color?" to listOf("Red"), "Which sizes?" to listOf("S"))))
		}.message!!, "중계하지 않은")

		relays.setFirstScreen(true)
		assertTrue(relays.me().firstScreenVisible)
		val (rid, shown) = start(s, kind = "session_question", detail = question)
		assertTrue(shown.relay)
		val item = shown.item()
		assertFailsWith<BadRequestException> { relays.answer(item, RelayAnswerIn(answers = mapOf("Which color?" to listOf("Red")))) }
		assertFailsWith<BadRequestException> {
			relays.answer(item, RelayAnswerIn(answers = mapOf("Which color?" to listOf("Red", "Blue"), "Which sizes?" to listOf("S"))))
		}
		assertFailsWith<BadRequestException> {
			relays.answer(item, RelayAnswerIn(answers = mapOf("Which color?" to listOf("Green"), "Which sizes?" to listOf("S"))))
		}
		assertFailsWith<BadRequestException> { relays.answer(item, RelayAnswerIn(decision = "allow")) }
		relays.answer(item, RelayAnswerIn(answers = mapOf("Which color?" to listOf("Blue"), "Which sizes?" to listOf("L", "S"))))
		val taken = take(s, rid)
		assertEquals("delivered", taken.outcome)
		assertEquals(mapOf("answers" to mapOf("Which color?" to listOf("Blue"), "Which sizes?" to listOf("S", "L"))), taken.answer)

		relays.setFirstScreen(false)
		assertFalse(relays.me().firstScreenVisible)
		relays.setFocus(true)
		relays.setFirstScreen(true)
		assertEquals("focus_mode", start(s, kind = "session_question", detail = question).second.reason)
	}

	@Test
	fun `a visibility signal lasts thirty seconds`() {
		relays.setFirstScreen(true)
		jdbc.sql("update users set first_screen_visible_until = now() - interval '1 second' where id = 'usr_owner'").update()
		assertFalse(relays.me().firstScreenVisible)
	}

	@Test
	fun `boundary 11 - an answer pressed after the terminal answered is still only delivered, never shown as applied by the server`() {
		// The server cannot see the terminal. It delivers the person's answer if the hook still waits,
		// and the item says delivered (the screen adds that it may not have been used); the session's
		// next resume closes it.
		val s = register()
		val (rid, started) = start(s)
		send(s, "resumed") // the tool ran after a terminal answer: a waiting relay keeps its deadline
		assertEquals("open", row(started.item())["state"])
		relays.answer(started.item(), RelayAnswerIn(decision = "allow"))
		assertEquals("delivered", take(s, rid).outcome)
		assertEquals("delivered", row(started.item())["state"])
		send(s, "resumed")
		assertEquals("closed", row(started.item())["state"])
	}

	@Test
	fun `a person can send a waiting relay to the terminal, and the hook then stops without a decision`() {
		val s = register()
		val (rid, started) = start(s)
		relays.toTerminal(started.item())
		assertEquals("moved_to_terminal", row(started.item())["state"])
		assertEquals("person", row(started.item())["relay_reason"])
		assertEquals("over", take(s, rid).outcome)
		assertFailsWith<ConflictException> { relays.toTerminal(started.item()) }
	}

	@Test
	fun `a repeated relay id gets the same item, and another session cannot reuse it`() {
		val s = register()
		val id = relayId()
		val first = start(s, id = id).second
		val again = start(s, id = id).second
		assertEquals(first.itemId, again.itemId)
		assertEquals(1, jdbc.sql("select count(*) from inbox_items where relay_id = :id").param("id", id).query(Long::class.javaObjectType).single())
		assertFailsWith<ConflictException> { start(register(), id = id) }
	}

	@Test
	fun `bad requests are refused before anything is stored`() {
		val s = register()
		assertFailsWith<BadRequestException> { start(s, id = "nope") }
		assertFailsWith<BadRequestException> { start(s, fingerprint = "ABC") }
		assertFailsWith<BadRequestException> { start(s, kind = "run_question") }
		assertFailsWith<BadRequestException> { start(s, detail = null) }
		assertFailsWith<BadRequestException> { start(s, detail = mapOf("tool_name" to "two words")) }
		assertFailsWith<BadRequestException> { start(s, kind = "session_question", detail = mapOf("tool_name" to "AskUserQuestion", "questions" to emptyList<Any>())) }
		assertFailsWith<BadRequestException> { start(s, withheld = "because") }
		assertFailsWith<UnauthorizedException> { relays.start(s.id, "zmbs_wrong", RelayRequest(2, relayId(), "permission_request", fp, 1, bash)) }
	}

	@Test
	fun `a closed relay's detail goes after the retention days, an open one's stays`() {
		val s = register()
		val old = start(s).second.item()
		val open = start(s).second.item()
		expire(old)
		relays.closeExpired()
		jdbc.sql("update inbox_items set closed_at = now() - interval '8 days' where id = :id").param("id", old).update()
		relays.purgeDetails()
		assertNull(row(old)["detail"])
		assertNotNull(row(open)["detail"])
	}

	@Test
	fun `focus mode is the person's setting and is audited when it changes`() {
		relays.setFocus(true)
		assertTrue(relays.me().focusOn)
		relays.setFocus(true)
		val changes = jdbc.sql("select count(*) from audit_log where action = 'focus.changed' and at > now() - interval '1 minute'")
			.query(Long::class.javaObjectType).single()
		relays.setFocus(false)
		assertFalse(relays.me().focusOn)
		assertTrue(changes >= 1)
	}
}
