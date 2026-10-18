package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.auth.Tokens
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The default `local` mode keeps stage 1 as it was (docs/stage1/auth.md): a request without credentials
 * is the owner's, a device registered before tokens still registers sessions and claims runs, and a
 * device token is checked when one is sent. No sign-in exists here.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthLocalTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
	private val requester: Requester,
	private val runs: RunService,
) {
	private val http = AuthHttp("http://localhost:" + env.getRequiredProperty("local.server.port"), json)

	@Test fun `local mode reports login administration unavailable and refuses issuance`() {
		val id = "usr_" + UUID.randomUUID()
		jdbc.sql("insert into users (id, display_name) values (:id, 'local member')").param("id", id).update()
		jdbc.sql("insert into memberships (org_id, user_id, roles) values ('org_owner', :id, '{requester}')").param("id", id).update()
		val (code, body) = http.call("GET", "/api/org/people/$id/login")
		assertEquals(200, code, body)
		assertEquals(false, http.obj(body)["can_issue"])
		assertEquals(409, http.call("POST", "/api/org/people/$id/login/setup").first)
	}

	private fun registerSession(device: String, token: String?): Int =
		http.call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
			bearer = token,
		).first

	@Test
	fun `a request without credentials is the owner's, and there is no sign-in`() {
		val (status, body) = http.call("GET", "/api/me")
		assertEquals(200, status, body)
		assertEquals("usr_owner", http.obj(body)["user_id"])
		val state = http.obj(http.call("GET", "/api/auth/state").second)
		assertEquals("local", state["mode"])
		assertEquals("usr_owner", (state["person"] as Map<*, *>)["id"])
		assertNull(state["csrf_token"])
		assertEquals(false, state["setup_pending"])
		assertEquals(409, http.call("POST", "/api/auth/login", """{"login":"owner","password":"whatever it is"}""").first)
		assertEquals(409, http.call("PUT", "/api/auth/password", """{"current_password":"a","new_password":"b"}""").first)
		// Outside a request (a server-internal caller) the owner too.
		assertEquals("usr_owner", requester.person(null, "read"))
		// A page of another site is refused here too.
		assertEquals(403, http.send("GET", "/api/me", headers = mapOf("Sec-Fetch-Site" to "cross-site")).status)
	}

	@Test
	fun `a device registered before tokens keeps working, a new one gets a token, and a token sent is checked`() {
		val legacy = "dev_" + UUID.randomUUID()
		jdbc.sql("insert into devices (id, org_id, user_id, name, os) values (:id, 'org_owner', 'usr_owner', 'old-pc', 'windows')").param("id", legacy).update()
		assertEquals(201, registerSession(legacy, null), "as before this version")
		val work = runs.createWorkItem("local compat", "usr_owner")
		val run = runs.createRun(work.id, "claude_code", "usr_owner")
		assertEquals(200, http.call("POST", "/api/runs/${run.id}/claim", """{"device_id":"$legacy"}""").first)

		val (status, body) = http.call("POST", "/api/devices", """{"name":"new-pc","os":"windows"}""")
		assertEquals(201, status, body)
		val device = http.obj(body)["device_id"] as String
		val token = http.obj(body)["device_token"] as String
		assertTrue(token.startsWith("zmbd_"))
		assertEquals(201, registerSession(device, token))
		assertEquals(201, registerSession(device, null))
		assertEquals(403, registerSession(legacy, token), "a token speaks for its own device")
		assertEquals(401, registerSession(device, Tokens.new(Tokens.DEVICE)), "an unknown token is refused, not ignored")
		assertEquals(200, http.call("GET", "/api/devices/self", bearer = token).first)
		assertEquals(401, http.call("GET", "/api/devices/self").first, "no token, nothing to show")

		// The legacy device gets a token for itself; the person is the owner here.
		val (rekeyed, rekeyBody) = http.call("POST", "/api/devices/$legacy/token")
		assertEquals(200, rekeyed, rekeyBody)
		assertEquals(legacy, http.obj(rekeyBody)["device_id"])
		val legacyToken = http.obj(rekeyBody)["device_token"] as String
		assertEquals(201, registerSession(legacy, legacyToken))

		// Revoked: neither its token nor its bare id gets in.
		assertEquals(200, http.call("POST", "/api/me/devices/$legacy/revoke").first)
		assertEquals(401, registerSession(legacy, legacyToken))
		val run2 = runs.createRun(runs.createWorkItem("after revoke", "usr_owner").id, "claude_code", "usr_owner")
		val (claim, claimBody) = http.call("POST", "/api/runs/${run2.id}/claim", """{"device_id":"$legacy"}""")
		assertEquals(401, claim)
		assertContains(http.obj(claimBody)["message"] as String, "폐기")
	}

	@Test
	fun `a session token still cannot do what a person does`() {
		val (_, body) = http.call("POST", "/api/devices", """{"name":"session-pc"}""")
		val device = http.obj(body)["device_id"] as String
		val (regStatus, regBody) = http.call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
		)
		assertEquals(201, regStatus)
		val sessionToken = http.obj(regBody)["token"] as String
		assertEquals(403, http.call("GET", "/api/inbox", bearer = sessionToken).first)
		assertFailsWith<ForbiddenException> { requester.person("Bearer $sessionToken", "test") }
	}
}
