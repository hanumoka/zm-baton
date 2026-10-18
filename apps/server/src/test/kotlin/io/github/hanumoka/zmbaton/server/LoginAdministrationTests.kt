package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.LoginService
import io.github.hanumoka.zmbaton.server.auth.LoginThrottle
import org.junit.jupiter.api.BeforeEach
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
import kotlin.test.assertNull

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["zm-baton.auth.mode=required", "zm-baton.auth.bcrypt-strength=4"])
class LoginAdministrationTests @Autowired constructor(
	env: Environment, private val json: ObjectMapper, private val jdbc: JdbcClient,
	private val logins: LoginService, private val throttle: LoginThrottle,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private fun web() = AuthHttp(base, json)
	private fun owner() = web().signIn(logins)
	private fun path(id: String) = "/api/org/people/$id/login"

	@BeforeEach fun clearThrottle() { throttle.succeeded(listOf("setup:127.0.0.1", "setup:0:0:0:0:0:0:0:1", "setup:::1")) }

	private fun member(roles: String = "requester"): String {
		val id = "usr_" + UUID.randomUUID()
		jdbc.sql("insert into users (id, display_name) values (:id, '계정 시험')").param("id", id).update()
		jdbc.sql("insert into memberships (org_id, user_id, roles) values ('org_owner', :id, cast(:roles as text[]))")
			.param("id", id).param("roles", "{$roles}").update()
		return id
	}

	private fun issue(client: AuthHttp, id: String): String {
		val result = client.send("POST", path(id) + "/setup")
		assertEquals(200, result.status, result.body)
		assertContains(result.headers["cache-control"].toString(), "no-store")
		return client.obj(result.body)["setup_token"] as String
	}

	private fun redeem(client: AuthHttp, token: String, name: String): AuthHttp.Answer =
		client.send("POST", "/api/auth/setup", json.writeValueAsString(mapOf("setup_token" to token, "login" to name, "password" to AuthHttp.OWNER_PASSWORD)), signedIn = false)

	@Test fun `owner activates a member without a restart and records issuer without the secret`() {
		val admin = owner()
		val id = member()
		assertEquals(true, admin.obj(admin.call("GET", "/api/org").second)["can_manage_logins"])
		val read = admin.send("GET", path(id))
		assertEquals(200, read.status, read.body)
		assertContains(read.headers["cache-control"].toString(), "no-store")
		val before = admin.obj(read.body)
		assertNull(before["login"])
		assertNull(before["pending_until"])
		assertEquals(true, before["can_issue"])
		val token = issue(admin, id)
		val audit = jdbc.sql("select row_to_json(a)::text from audit_log a where action = 'auth.setup_token_issued' and target_id = :id")
			.param("id", id).query(String::class.java).single()
		assertContains(audit, "usr_owner")
		assertContains(audit, "person")
		assertFalse(audit.contains(token))
		val stored = jdbc.sql("select row_to_json(t)::text from setup_tokens t where user_id = :id").param("id", id).query(String::class.java).single()
		assertFalse(stored.contains(token))
		val person = web()
		val result = redeem(person, token, id)
		assertEquals(200, result.status, result.body)
		person.takeCookie(result)
		assertEquals(id, (person.obj(person.call("GET", "/api/auth/state").second)["person"] as Map<*, *>)["id"])
		assertEquals(401, redeem(web(), token, id).status)
		val after = admin.obj(admin.call("GET", path(id)).second)
		assertEquals(id, after["login"])
		assertNull(after["pending_until"])
	}

	@Test fun `operator role cannot read or reset any login and device and csrf refusals apply`() {
		val admin = owner()
		val id = member("operator,master_admin")
		val operator = web()
		val result = redeem(operator, issue(admin, id), id)
		operator.takeCookie(result)
		operator.csrf = operator.obj(result.body)["csrf_token"] as String
		assertEquals(false, operator.obj(operator.call("GET", "/api/org").second)["can_manage_logins"])
		for (target in listOf(id, "usr_owner")) {
			assertEquals(403, operator.call("GET", path(target)).first)
			assertEquals(403, operator.call("POST", path(target) + "/setup").first)
		}
		assertEquals(409, admin.call("POST", path("usr_owner") + "/setup").first)
		assertEquals(403, admin.send("POST", path(id) + "/setup", csrfHeader = "wrong").status)
		assertEquals(401, web().call("POST", path(id) + "/setup").first)
		val (deviceId, deviceToken) = admin.device("login-administration-test")
		assertEquals(403, web().call("POST", path(id) + "/setup", bearer = deviceToken).first)
		val (registered, sessionBody) = admin.call("POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$deviceId","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
			bearer = deviceToken, signedIn = false)
		assertEquals(201, registered, sessionBody)
		val sessionToken = admin.obj(sessionBody)["token"] as String
		assertEquals(403, admin.call("GET", path(id), bearer = sessionToken).first)
		assertEquals(403, admin.call("POST", path(id) + "/setup", bearer = sessionToken).first)
	}

	@Test fun `reissue expires previous token and a used reset ends old logins`() {
		val admin = owner()
		val id = member()
		val first = issue(admin, id)
		val second = issue(admin, id)
		assertEquals(401, redeem(web(), first, id).status)
		val person = web()
		val initial = redeem(person, second, id)
		assertEquals(200, initial.status, initial.body)
		person.takeCookie(initial)
		val reset = issue(admin, id)
		assertEquals(200, person.call("GET", "/api/me").first)
		assertEquals(200, redeem(web(), reset, id).status)
		assertEquals(401, person.call("GET", "/api/me").first)
	}

	@Test fun `disabling ends logins and tokens permanently even after reactivation`() {
		val admin = owner()
		val id = member()
		val person = web()
		person.takeCookie(redeem(person, issue(admin, id), id))
		val pending = issue(admin, id)
		assertEquals(200, admin.call("PATCH", "/api/org/people/$id", """{"status":"inactive"}""").first)
		assertEquals(false, admin.obj(admin.call("GET", path(id)).second)["can_issue"])
		assertEquals(409, admin.call("POST", path(id) + "/setup").first)
		assertEquals(401, redeem(web(), pending, id).status)
		assertEquals(200, admin.call("PATCH", "/api/org/people/$id", """{"status":"active"}""").first)
		assertEquals(401, person.call("GET", "/api/me").first)
		assertEquals(401, redeem(web(), pending, id).status)
		assertEquals(200, redeem(web(), issue(admin, id), id).status)
	}

	@Test fun `missing and foreign members are hidden and expired tokens do not work`() {
		val admin = owner()
		val id = member()
		val token = issue(admin, id)
		jdbc.sql("update setup_tokens set expires_at = now() - interval '1 second' where user_id = :id").param("id", id).update()
		assertEquals(401, redeem(web(), token, id).status)
		assertNull(admin.obj(admin.call("GET", path(id)).second)["pending_until"])
		assertEquals(200, redeem(web(), issue(admin, id), id).status)
		val foreignOrg = "org_" + UUID.randomUUID()
		jdbc.sql("insert into organizations (id, name, owner_user_id) values (:org, 'foreign', :id)").param("org", foreignOrg).param("id", id).update()
		jdbc.sql("update memberships set org_id = :org where user_id = :id").param("org", foreignOrg).param("id", id).update()
		for (target in listOf(id, "usr_missing")) {
			assertEquals(404, admin.call("GET", path(target)).first)
			assertEquals(404, admin.call("POST", path(target) + "/setup").first)
		}
	}
}
