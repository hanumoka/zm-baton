package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.LoginService
import io.github.hanumoka.zmbaton.server.auth.LoginThrottle
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["zm-baton.auth.mode=required", "zm-baton.auth.bcrypt-strength=4"])
class ServerAdministrationTests @Autowired constructor(
	env: Environment, private val json: ObjectMapper, private val jdbc: JdbcClient,
	private val logins: LoginService, private val throttle: LoginThrottle,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val initialize = """{"organization_name":"시험 조직","administrator_name":"최초 관리자"}"""
	private fun web() = AuthHttp(base, json)
	private fun owner() = web().signIn(logins)

	@BeforeEach fun reset() {
		jdbc.sql("update server_installation set administrator_user_id = 'usr_owner', initialized_at = null, initialized_by = null where id = 1").update()
		throttle.succeeded(listOf("setup:127.0.0.1", "setup:0:0:0:0:0:0:0:1", "setup:::1"))
	}

	@AfterEach fun restoreAdministrator() {
		jdbc.sql("update server_installation set administrator_user_id = 'usr_owner', initialized_at = null, initialized_by = null where id = 1").update()
	}

	private fun member(roles: String = "requester", password: Boolean = true): Pair<String, AuthHttp> {
		val id = "usr_" + UUID.randomUUID()
		jdbc.sql("insert into users (id, display_name) values (:id, '관리 권한 시험')").param("id", id).update()
		jdbc.sql("insert into memberships (org_id, user_id, roles) values ('org_owner', :id, cast(:roles as text[]))")
			.param("id", id).param("roles", "{$roles}").update()
		val client = web()
		if (password) {
			val code = logins.issueSetupToken(id, "first_password").first
			val signed = client.send("POST", "/api/auth/setup", json.writeValueAsString(mapOf(
				"setup_token" to code, "login" to id, "password" to AuthHttp.OWNER_PASSWORD)), signedIn = false)
			assertEquals(200, signed.status, signed.body)
			client.takeCookie(signed)
			client.csrf = client.obj(signed.body)["csrf_token"] as String
		}
		return id to client
	}

	@Test fun `initial setup is authenticated one time and survives a fresh browser session`() {
		assertEquals(401, web().call("GET", "/api/server").first)
		assertEquals(401, web().call("POST", "/api/server/initialize", initialize).first)
		val admin = owner()
		val before = admin.obj(admin.call("GET", "/api/auth/state").second)
		assertEquals(true, before["server_admin"])
		assertEquals(true, before["server_setup_pending"])
		assertEquals(403, admin.send("POST", "/api/server/initialize", initialize, csrfHeader = "wrong").status)
		assertEquals(400, admin.call("POST", "/api/server/initialize", """{"organization_name":" ","administrator_name":"a"}""").first)
		val first = admin.call("POST", "/api/server/initialize", initialize)
		assertEquals(200, first.first, first.second)
		assertEquals("시험 조직", admin.obj(first.second)["organization_name"])
		assertNotNull(admin.obj(first.second)["initialized_at"])
		assertEquals(409, owner().call("POST", "/api/server/initialize", initialize).first)
		assertEquals(false, owner().obj(owner().call("GET", "/api/auth/state").second)["server_setup_pending"])
	}

	@Test fun `concurrent initial setup succeeds exactly once`() {
		val first = owner()
		val second = owner()
		val a = CompletableFuture.supplyAsync { first.call("POST", "/api/server/initialize", initialize).first }
		val b = CompletableFuture.supplyAsync { second.call("POST", "/api/server/initialize", initialize).first }
		assertEquals(listOf(200, 409), listOf(a.join(), b.join()).sorted())
	}

	@Test fun `organisation operators devices and sessions cannot administer the server`() {
		val admin = owner()
		val (_, operator) = member("operator,master_admin")
		assertEquals(false, operator.obj(operator.call("GET", "/api/auth/state").second)["server_admin"])
		assertEquals(403, operator.call("GET", "/api/server").first)
		assertEquals(403, operator.call("POST", "/api/server/initialize", initialize).first)
		assertEquals(403, operator.call("PUT", "/api/server/administrator", """{"user_id":"usr_owner"}""").first)
		val (deviceId, token) = admin.device("server-admin-test")
		assertEquals(403, web().call("GET", "/api/server", bearer = token).first)
		val (status, registered) = admin.call("POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$deviceId","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
			bearer = token, signedIn = false)
		assertEquals(201, status, registered)
		val sessionToken = admin.obj(registered)["token"] as String
		assertEquals(403, web().call("GET", "/api/server", bearer = sessionToken).first)
	}

	@Test fun `transfer requires a working member login and grants no organisation operator authority`() {
		val admin = owner()
		assertEquals(200, admin.call("POST", "/api/server/initialize", initialize).first)
		val (withoutPassword, _) = member(password = false)
		assertEquals(409, admin.call("PUT", "/api/server/administrator", """{"user_id":"$withoutPassword"}""").first)
		val (id, next) = member()
		assertEquals(200, admin.call("PUT", "/api/server/administrator", """{"user_id":"$id"}""").first)
		assertEquals(403, admin.call("GET", "/api/server").first)
		assertEquals(200, next.call("GET", "/api/server").first)
		assertEquals(false, next.obj(next.call("GET", "/api/org").second)["can_manage"])
		assertEquals(403, next.call("POST", "/api/org/people", """{"display_name":"not allowed"}""").first)
		assertEquals(409, admin.call("PATCH", "/api/org/people/$id", """{"status":"inactive"}""").first)
		assertEquals(200, next.call("PUT", "/api/server/administrator", """{"user_id":"usr_owner"}""").first)
		assertEquals(200, admin.call("PATCH", "/api/org/people/$id", """{"status":"inactive"}""").first)
		assertEquals("usr_owner", admin.obj(admin.call("GET", "/api/org").second)["owner_user_id"])
	}
}
