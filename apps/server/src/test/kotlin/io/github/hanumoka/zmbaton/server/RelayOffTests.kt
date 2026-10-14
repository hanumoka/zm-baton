package io.github.hanumoka.zmbaton.server

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
import kotlin.test.assertNull

/**
 * Relays are off by default (no ZM_BATON_RELAY): until person authentication exists any program on
 * this PC could answer one (H-21). The server then makes no relay, takes no answer and gives none.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RelayOffTests @Autowired constructor(
	private val sessions: SessionService,
	private val relays: RelayService,
	private val jdbc: JdbcClient,
) {
	private val fp = "ef".repeat(32)

	private fun register(): SessionRegistered {
		val device = sessions.registerDevice(RegisterDevice("relay-off-pc", "windows")).deviceId
		return sessions.register(RegisterSession(2, device, "claude_code", null, UUID.randomUUID().toString()))
	}

	@Test
	fun `by default the registration says relays are off and a relay request makes nothing`() {
		val s = register()
		assertFalse(s.relayEnabled, "the hook learns at registration that relays are off and does not ask")
		assertFalse(relays.me().relayEnabled)
		val relayId = "rly_" + UUID.randomUUID().toString().replace("-", "")
		val started = relays.start(
			s.sessionId, s.token,
			RelayRequest(2, relayId, "permission_request", fp, 1, mapOf("tool_name" to "Bash", "command" to "rm -rf build")),
		)
		assertFalse(started.relay)
		assertEquals("relay_off", started.reason)
		assertNull(started.itemId)
		assertEquals(0, jdbc.sql("select count(*) from inbox_items where relay_id = :id").param("id", relayId).query(Long::class.javaObjectType).single())
		assertEquals("over", relays.take(s.sessionId, s.token, relayId, RelayTake(2, fp)).outcome)
		assertFailsWith<UnauthorizedException> {
			relays.start(s.sessionId, "zmbs_wrong", RelayRequest(2, relayId, "permission_request", fp, 1, mapOf("tool_name" to "Bash")))
		}
	}

	@Test
	fun `while off no answer is taken, even for a relay left from when it was on`() {
		val s = register()
		val itemId = Ids.inbox()
		jdbc.sql(
			"""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, notify, relay_id, input_fingerprint, relay_deadline)
			values (:id, 'usr_owner', 1, 'permission_request', 'ses', :session, 'left over', 'immediate', :rid, :fp, now() + interval '1 minute')
			""".trimIndent(),
		).param("id", itemId).param("session", s.sessionId).param("rid", "rly_" + UUID.randomUUID().toString().replace("-", "")).param("fp", fp).update()
		val refused = assertFailsWith<ConflictException> { relays.answer(itemId, RelayAnswerIn(decision = "allow")) }
		assertContains(refused.message!!, "중계가 꺼져 있다")
		assertEquals("open", jdbc.sql("select state from inbox_items where id = :id").param("id", itemId).query(String::class.java).single())
	}
}
