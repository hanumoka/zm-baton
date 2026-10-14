package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.LoginService
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Device tokens on a server in `required` mode (docs/stage1/auth.md): issued at registration, checked on
 * every device call, bound to their device, revoked when a device is lost; and the device login that
 * gives the CLI a token when it has no Web sign-in (RFC 8628 shape). Same context as AuthRequiredTests.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["zm-baton.auth.mode=required", "zm-baton.auth.bcrypt-strength=4"],
)
class AuthDeviceTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
	private val logins: LoginService,
	private val runs: RunService,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")

	private fun web() = AuthHttp(base, json)

	private fun register(w: AuthHttp, device: String, token: String): Pair<String, String> {
		val (status, body) = w.call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
			bearer = token, signedIn = false,
		)
		assertEquals(201, status, body)
		return w.obj(body)["session_id"] as String to w.obj(body)["token"] as String
	}

	@Test
	fun `a device token is checked on every device call and bound to its device`() {
		val w = web().signIn(logins)
		val (device, token) = w.device("bound-pc")
		val (other, otherToken) = w.device("other-pc")
		assertTrue(token.startsWith("zmbd_"))
		val (selfStatus, selfBody) = w.call("GET", "/api/devices/self", bearer = token, signedIn = false)
		assertEquals(200, selfStatus, selfBody)
		assertEquals(device, w.obj(selfBody)["device_id"])
		assertEquals("required", w.obj(selfBody)["mode"])

		// A token speaks for its own device only.
		val work = runs.createWorkItem("compat run", "usr_owner")
		val run = runs.createRun(work.id, "claude_code", "usr_owner")
		assertEquals(403, w.call("POST", "/api/runs/${run.id}/claim", """{"device_id":"$other"}""", bearer = token, signedIn = false).first)
		assertEquals(403, register0(w, other, token))
		assertEquals(401, w.call("GET", "/api/runs?state=requested", bearer = Tokens.new(Tokens.DEVICE), signedIn = false).first)
		val (claimed, claimBody) = w.call("POST", "/api/runs/${run.id}/claim", """{"device_id":"$device"}""", bearer = token, signedIn = false)
		assertEquals(200, claimed, claimBody)
		val generation = (w.obj(claimBody)["generation"] as Number).toLong()
		// Another device cannot report on or renew a run this one holds.
		val events = """{"protocol_version":2,"events":[{"event_id":"evt_${UUID.randomUUID()}","generation":$generation,"seq":1,"kind":"started","payload":{}}]}"""
		assertEquals(403, w.call("POST", "/api/runs/${run.id}/events", events, bearer = otherToken, signedIn = false).first)
		assertEquals(403, w.call("POST", "/api/runs/${run.id}/lease", """{"device_id":"$other","generation":$generation}""", bearer = token, signedIn = false).first)
		assertEquals(200, w.call("POST", "/api/runs/${run.id}/events", events, bearer = token, signedIn = false).first)
		assertEquals(200, w.call("POST", "/api/runs/${run.id}/lease", """{"device_id":"$device","generation":$generation}""", bearer = token, signedIn = false).first)

		// The CLI's own person-side calls go with the device token: its repositories and the export.
		val key = "git:" + UUID.randomUUID().toString().replace("-", "")
		assertEquals(200, w.call("POST", "/api/devices/$device/repositories", """{"repo_key":"$key","name":"demo"}""", bearer = token, signedIn = false).first)
		assertEquals(403, w.call("POST", "/api/devices/$other/repositories", """{"repo_key":"$key","name":"demo"}""", bearer = token, signedIn = false).first)
		val export = w.send("GET", "/api/export", bearer = token, signedIn = false)
		assertEquals(200, export.status)
		assertFalse(token in export.body, "the device token")
		assertFalse(Tokens.hash(token) in export.body, "the device token's hash")
		assertTrue(jdbc.sql("select count(*) from audit_log where action = 'export.downloaded' and details ->> 'device_id' = :d").param("d", device)
			.query(Long::class.javaObjectType).single() >= 1)
	}

	private fun register0(w: AuthHttp, device: String, token: String): Int =
		w.call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
			bearer = token, signedIn = false,
		).first

	@Test
	fun `revoking a lost device stops its token and ends its live sessions`() {
		val w = web().signIn(logins)
		val (device, token) = w.device("lost-pc")
		val (session, sessionToken) = register(w, device, token)
		val (listed, listBody) = w.call("GET", "/api/me/devices")
		assertEquals(200, listed)
		val mine = json.readValue(listBody, List::class.java).map { it as Map<*, *> }.single { it["id"] == device }
		assertEquals(true, mine["has_token"])

		val (revoked, revokedBody) = w.call("POST", "/api/me/devices/$device/revoke")
		assertEquals(200, revoked, revokedBody)
		assertTrue(w.obj(revokedBody)["revoked_at"] != null)
		val (self, selfBody) = w.call("GET", "/api/devices/self", bearer = token, signedIn = false)
		assertEquals(401, self)
		assertContains(w.obj(selfBody)["message"] as String, "폐기")
		assertEquals(401, register0(w, device, token))
		assertEquals(401, w.call("GET", "/api/sessions/$session/status-card", bearer = sessionToken, signedIn = false).first, "its session ended")
		assertEquals(1, jdbc.sql("select count(*) from audit_log where action = 'device.revoked' and target_id = :d").param("d", device)
			.query(Long::class.javaObjectType).single())
		assertEquals(404, w.call("POST", "/api/me/devices/$device/revoke").first, "a revoked device is gone")
	}

	@Test
	fun `a device login - the CLI shows a code, the person approves it, the next poll takes the token once`() {
		val cli = web()
		val (started, startBody) = cli.call("POST", "/api/device-logins", """{"name":"new-laptop","os":"windows"}""", signedIn = false)
		assertEquals(200, started, startBody)
		val s = cli.obj(startBody)
		val deviceCode = s["device_code"] as String
		val userCode = s["user_code"] as String
		assertTrue(Regex("^[BCDFGHJKLMNPQRSTVWXZ]{4}-[BCDFGHJKLMNPQRSTVWXZ]{4}$").matches(userCode))
		assertEquals("/#me?device_login=$userCode", s["verification_path_complete"])
		assertEquals(600, (s["expires_in"] as Number).toInt())
		val pending = cli.send("POST", "/api/device-logins/token", """{"device_code":"$deviceCode"}""", signedIn = false)
		assertEquals(400, pending.status)
		assertEquals("authorization_pending", cli.obj(pending.body)["error"])
		val tooSoon = cli.send("POST", "/api/device-logins/token", """{"device_code":"$deviceCode"}""", signedIn = false)
		assertEquals("slow_down", cli.obj(tooSoon.body)["error"])
		assertEquals(401, cli.call("POST", "/api/device-logins/$userCode/approve", signedIn = false).first, "approving takes a sign-in")

		val person = web().signIn(logins)
		val (viewStatus, viewBody) = person.call("GET", "/api/device-logins/${userCode.lowercase().replace("-", "%20")}")
		assertEquals(200, viewStatus, viewBody)
		assertEquals("pending", person.obj(viewBody)["state"])
		assertEquals("new-laptop", person.obj(viewBody)["name"])
		assertEquals(200, person.call("POST", "/api/device-logins/$userCode/approve").first)
		assertEquals(409, person.call("POST", "/api/device-logins/$userCode/approve").first, "once")

		jdbc.sql("update device_logins set last_polled_at = now() - interval '1 minute' where user_code = :c").param("c", userCode).update()
		val got = cli.send("POST", "/api/device-logins/token", """{"device_code":"$deviceCode"}""", signedIn = false)
		assertEquals(200, got.status, got.body)
		val device = cli.obj(got.body)["device_id"] as String
		val token = cli.obj(got.body)["device_token"] as String
		assertEquals(200, cli.call("GET", "/api/devices/self", bearer = token, signedIn = false).first)
		jdbc.sql("update device_logins set last_polled_at = now() - interval '1 minute' where user_code = :c").param("c", userCode).update()
		assertEquals("invalid_grant", cli.obj(cli.send("POST", "/api/device-logins/token", """{"device_code":"$deviceCode"}""", signedIn = false).body)["error"])
		assertEquals("done", person.obj(person.call("GET", "/api/device-logins/$userCode").second)["state"])
		assertEquals("usr_owner", jdbc.sql("select user_id from devices where id = :d").param("d", device).query(String::class.java).single())
		assertFalse(deviceCode in jdbc.sql("select string_agg(t::text, ' ') from device_logins t").query(String::class.java).single())
	}

	@Test
	fun `a denied device login gives nothing, and a device registered before tokens gets one for itself`() {
		val person = web().signIn(logins)
		val cli = web()
		val denied = cli.obj(cli.call("POST", "/api/device-logins", """{"name":"stranger"}""", signedIn = false).second)
		assertEquals(200, person.call("POST", "/api/device-logins/${denied["user_code"]}/deny").first)
		assertEquals("access_denied", cli.obj(cli.send("POST", "/api/device-logins/token", """{"device_code":"${denied["device_code"]}"}""", signedIn = false).body)["error"])

		// A device from before tokens: no token_hash. In required mode it asks for a token for itself.
		val legacy = "dev_" + UUID.randomUUID()
		jdbc.sql("insert into devices (id, org_id, user_id, name, os) values (:id, 'org_owner', 'usr_owner', 'old-pc', 'windows')").param("id", legacy).update()
		val key = "git:" + UUID.randomUUID().toString().replace("-", "")
		jdbc.sql("insert into device_repositories (device_id, repo_key, name) values (:d, :k, 'kept')").param("d", legacy).param("k", key).update()
		assertEquals(401, register0(cli, legacy, ""))
		val ask = cli.obj(cli.call("POST", "/api/device-logins", """{"name":"old-pc","device_id":"$legacy"}""", signedIn = false).second)
		val (viewStatus, viewBody) = person.call("GET", "/api/device-logins/${ask["user_code"]}")
		assertEquals(200, viewStatus)
		assertEquals(legacy, person.obj(viewBody)["device_id"], "the person sees it is their device asking")
		assertEquals(200, person.call("POST", "/api/device-logins/${ask["user_code"]}/approve").first)
		val got = cli.obj(cli.send("POST", "/api/device-logins/token", """{"device_code":"${ask["device_code"]}"}""", signedIn = false).body)
		assertEquals(legacy, got["device_id"], "the same device, so its repositories and history stay")
		val token = got["device_token"] as String
		register(cli, legacy, token)
		assertEquals(1, jdbc.sql("select count(*) from device_repositories where device_id = :d and removed_at is null").param("d", legacy)
			.query(Long::class.javaObjectType).single())

		// Asking again replaces the token; the old one stops.
		val again = cli.obj(cli.call("POST", "/api/device-logins", """{"name":"old-pc","device_id":"$legacy"}""", signedIn = false).second)
		assertEquals(200, person.call("POST", "/api/device-logins/${again["user_code"]}/approve").first)
		val newer = cli.obj(cli.send("POST", "/api/device-logins/token", """{"device_code":"${again["device_code"]}"}""", signedIn = false).body)["device_token"] as String
		assertNotEquals(token, newer)
		assertEquals(401, cli.call("GET", "/api/devices/self", bearer = token, signedIn = false).first)
		assertEquals(200, cli.call("GET", "/api/devices/self", bearer = newer, signedIn = false).first)
	}
}
