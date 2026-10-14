package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.LoginService
import io.github.hanumoka.zmbaton.server.auth.LoginThrottle
import io.github.hanumoka.zmbaton.server.auth.Tokens
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A server in `required` mode over real HTTP (docs/stage1/auth.md): who a request comes from, sign-in,
 * sign-out, the attempt limit, CSRF, what is stored (hashes only), the one-time setup token, and that a
 * request without sign-in or with the wrong kind of token is refused. Device tokens are AuthDeviceTests.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["zm-baton.auth.mode=required", "zm-baton.auth.bcrypt-strength=4"],
)
class AuthRequiredTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
	private val logins: LoginService,
	private val throttle: LoginThrottle,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")

	private fun web() = AuthHttp(base, json)

	private fun count(sql: String, vararg params: Pair<String, Any?>): Long {
		var s = jdbc.sql(sql)
		for ((k, v) in params) s = s.param(k, v)
		return s.query(Long::class.javaObjectType).single()
	}

	/** Every text the auth tables, the audit log and the server's records hold, to look for a secret in. */
	private fun everything(): String =
		listOf(
			"select coalesce(string_agg(t::text, ' '), '') from person_passwords t",
			"select coalesce(string_agg(t::text, ' '), '') from web_logins t",
			"select coalesce(string_agg(t::text, ' '), '') from setup_tokens t",
			"select coalesce(string_agg(t::text, ' '), '') from devices t",
			"select coalesce(string_agg(t::text, ' '), '') from device_logins t",
			"select coalesce(string_agg(t::text, ' '), '') from audit_log t",
		).joinToString(" ") { jdbc.sql(it).query(String::class.java).single() }

	@Test
	fun `nothing a person or a device does goes through without sign-in or a device token`() {
		val anon = web()
		assertEquals(401, anon.call("GET", "/api/work-items").first)
		assertEquals(401, anon.call("GET", "/api/inbox").first)
		assertEquals(401, anon.call("GET", "/api/export").first)
		assertEquals(401, anon.call("POST", "/api/devices", """{"name":"x"}""").first)
		val (claim, claimBody) = anon.call("POST", "/api/runs/run_x/claim", """{"device_id":"dev_x"}""")
		assertEquals(401, claim)
		assertContains(anon.obj(claimBody)["message"] as String, "zm-baton login")
		assertEquals(401, anon.call("GET", "/api/runs?state=requested").first)
		assertEquals(401, anon.call("POST", "/api/sessions", """{"protocol_version":2,"device_id":"dev_x","executor":"claude_code","executor_session_id":"s"}""").first)
		// A forged cookie is no sign-in.
		anon.cookie = "zmb_login=" + Tokens.new(Tokens.LOGIN)
		assertEquals(401, anon.call("GET", "/api/me").first)
		// The state and sign-in endpoints stay open.
		val (stateStatus, stateBody) = anon.call("GET", "/api/auth/state")
		assertEquals(200, stateStatus)
		val state = anon.obj(stateBody)
		assertEquals("required", state["mode"])
		assertNull(state["person"])
		assertNull(state["csrf_token"])
		assertEquals(false, state["secure"])
	}

	@Test
	fun `the first password with the one-time setup token from the server's log, then sign-in as that person`() {
		// The start made a token for the owner, who had no password (AuthStartup); only its hash is kept.
		assertTrue(count("select count(*) from setup_tokens where user_id = 'usr_owner' and reason = 'first_password'") >= 1 || logins.loginName("usr_owner") != null)
		val w = web().signIn(logins)
		val (status, body) = w.call("GET", "/api/me")
		assertEquals(200, status, body)
		assertEquals("usr_owner", w.obj(body)["user_id"], "the person is the one signed in")
		val state = w.obj(w.call("GET", "/api/auth/state").second)
		assertEquals("usr_owner", (state["person"] as Map<*, *>)["id"])
		assertEquals(AuthHttp.OWNER_LOGIN, (state["person"] as Map<*, *>)["login"])
		assertEquals(w.csrf, state["csrf_token"])
		assertEquals(false, state["setup_pending"])
		// The person's actions are recorded as that person's.
		val (created, createdBody) = w.call("POST", "/api/work-items", """{"title":"signed-in work","responsible_user_id":"usr_owner"}""")
		assertEquals(201, created, createdBody)
		val workId = w.obj(createdBody)["id"] as String
		assertEquals(1, count("select count(*) from audit_log where action = 'work_item.created' and target_id = :id and actor_kind = 'person' and actor_id = 'usr_owner'", "id" to workId))
	}

	@Test
	fun `a setup token works once, a wrong one never, and only hashes are stored`() {
		val person = "usr_" + UUID.randomUUID().toString().replace("-", "")
		jdbc.sql("insert into users (id, display_name) values (:id, 'second')").param("id", person).update()
		jdbc.sql("insert into memberships (org_id, user_id, roles) values ('org_owner', :id, '{requester}')").param("id", person).update()
		val (token, _) = logins.issueSetupToken(person, "reset")
		val login = "second-" + person.takeLast(6)
		val password = "another synthetic pass"
		val w = web()
		val wrong = w.send("POST", "/api/auth/setup", """{"setup_token":"${Tokens.new(Tokens.SETUP)}","login":"$login","password":"$password"}""", signedIn = false)
		assertEquals(401, wrong.status)
		val short = w.send("POST", "/api/auth/setup", """{"setup_token":"$token","login":"$login","password":"short"}""", signedIn = false)
		assertEquals(400, short.status, "a refused password leaves the token usable")
		val ok = w.send("POST", "/api/auth/setup", """{"setup_token":"$token","login":"$login","password":"$password"}""", signedIn = false)
		assertEquals(200, ok.status, ok.body)
		val cookie = ok.headers.entries.first { it.key.equals("set-cookie", true) }.value.first()
		assertContains(cookie, "HttpOnly")
		assertContains(cookie, "SameSite=Strict")
		assertFalse("Secure" in cookie, "plain HTTP on loopback: no Secure flag")
		val again = w.send("POST", "/api/auth/setup", """{"setup_token":"$token","login":"$login","password":"$password x"}""", signedIn = false)
		assertEquals(401, again.status, "a setup token works once")

		w.takeCookie(ok)
		val cookieToken = w.cookie!!.substringAfter('=')
		val stored = everything()
		for (secret in listOf(token, password, cookieToken)) assertFalse(secret in stored, "a secret is stored or audited as it is")
		val hash = jdbc.sql("select password_hash from person_passwords where user_id = :p").param("p", person).query(String::class.java).single()
		assertTrue(hash.startsWith("$2"), "BCrypt")
		assertEquals(1, count("select count(*) from web_logins where user_id = :p and token_hash = :h", "p" to person, "h" to Tokens.hash(cookieToken)))
		assertEquals(1, count("select count(*) from setup_tokens where user_id = :p and token_hash = :h and used_at is not null", "p" to person, "h" to Tokens.hash(token)))
	}

	@Test
	fun `a changing request needs the login's CSRF token, and sign-out ends the login`() {
		val w = web().signIn(logins)
		assertEquals(403, w.send("PUT", "/api/me/focus", """{"on":true}""", csrfHeader = "wrong").status)
		val missing = w.send("PUT", "/api/me/focus", """{"on":true}""", csrfHeader = "")
		assertEquals(403, missing.status)
		val ok = w.send("PUT", "/api/me/focus", """{"on":false}""")
		assertEquals(200, ok.status, ok.body)
		// A page of another site never gets through, signed in or not.
		assertEquals(403, w.send("GET", "/api/me", headers = mapOf("Sec-Fetch-Site" to "cross-site")).status)
		assertEquals(200, w.send("GET", "/api/me", headers = mapOf("Sec-Fetch-Site" to "same-origin")).status)

		val out = w.send("POST", "/api/auth/logout")
		assertEquals(200, out.status, out.body)
		assertContains(out.headers.entries.first { it.key.equals("set-cookie", true) }.value.first(), "Max-Age=0")
		assertEquals(401, w.call("GET", "/api/me").first, "the old cookie no longer signs in")
	}

	@Test
	fun `five wrong passwords lock the name for a while, a right one after the lock clears it`() {
		web().signIn(logins)
		val start = Instant.parse("2026-10-14T00:00:00Z")
		throttle.clock = Clock.fixed(start, ZoneOffset.UTC)
		try {
			val w = web()
			repeat(5) {
				assertEquals(401, w.send("POST", "/api/auth/login", """{"login":"owner","password":"not the password $it"}""", signedIn = false).status)
			}
			val locked = w.send("POST", "/api/auth/login", """{"login":"owner","password":"${AuthHttp.OWNER_PASSWORD}"}""", signedIn = false)
			assertEquals(429, locked.status, "locked even with the right password")
			assertContains(w.obj(locked.body)["message"] as String, "초 뒤에")
			throttle.clock = Clock.fixed(start.plus(Duration.ofSeconds(31)), ZoneOffset.UTC)
			val after = w.send("POST", "/api/auth/login", """{"login":"owner","password":"${AuthHttp.OWNER_PASSWORD}"}""", signedIn = false)
			assertEquals(200, after.status, after.body)
			assertTrue(count("select count(*) from audit_log where action = 'auth.login_failed'") >= 5)
			assertFalse("not the password" in everything(), "a typed password is never recorded")
		} finally {
			throttle.clock = Clock.systemUTC()
		}
	}

	@Test
	fun `a session token cannot do what a person does, and neither can a device token`() {
		val w = web().signIn(logins)
		val (device, deviceToken) = w.device("required-refusals")
		val (regStatus, regBody) = w.call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
			bearer = deviceToken, signedIn = false,
		)
		assertEquals(201, regStatus, regBody)
		val session = w.obj(regBody)["session_id"] as String
		val sessionToken = w.obj(regBody)["token"] as String
		// Contract "등록과 세션 토큰" 6: refused and written down, even with a valid cookie beside it.
		assertEquals(403, w.call("PUT", "/api/me/focus", """{"on":true}""", bearer = sessionToken).first)
		assertEquals(1, count("select count(*) from audit_log where action = 'person_action.refused' and actor_id = :s", "s" to session))
		assertEquals(403, w.call("GET", "/api/inbox", bearer = deviceToken, signedIn = false).first)
		assertEquals(403, w.call("POST", "/api/work-items", """{"title":"x","responsible_user_id":"usr_owner"}""", bearer = deviceToken, signedIn = false).first)
		assertTrue(count("select count(*) from audit_log where action = 'person_action.refused' and actor_id = :d and details ->> 'reason' = 'device token'", "d" to device) >= 2)
		// The session's own calls still work with its token.
		assertEquals(200, w.call("GET", "/api/sessions/$session/status-card", bearer = sessionToken, signedIn = false).first)
	}

	@Test
	fun `changing the password needs the current one and ends the person's other logins`() {
		val a = web().signIn(logins)
		val b = web().signIn(logins)
		assertEquals(401, a.send("PUT", "/api/auth/password", """{"current_password":"wrong wrong wrong","new_password":"a new synthetic pass"}""").status)
		val changed = a.send("PUT", "/api/auth/password", """{"current_password":"${AuthHttp.OWNER_PASSWORD}","new_password":"a new synthetic pass"}""")
		assertEquals(200, changed.status, changed.body)
		assertTrue((a.obj(changed.body)["other_logins_ended"] as Number).toInt() >= 1)
		assertEquals(200, a.call("GET", "/api/me").first, "this login stays")
		assertEquals(401, b.call("GET", "/api/me").first, "the other login ended")
		// Put the shared password back for the other tests in this context.
		assertEquals(200, a.send("PUT", "/api/auth/password", """{"current_password":"a new synthetic pass","new_password":"${AuthHttp.OWNER_PASSWORD}"}""").status)
		assertNotEquals(a.cookie, b.cookie)
		assertNotNull(logins.loginName("usr_owner"))
	}
}
