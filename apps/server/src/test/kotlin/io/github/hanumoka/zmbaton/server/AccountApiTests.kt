package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The AI account API (FN-0501): register, change, disable and enable, as person actions; values
 * shaped like keys refused and never stored, audited or exported. Over HTTP against PostgreSQL 17.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountApiTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	private fun call(method: String, path: String, body: String? = null, token: String? = null): HttpResponse<String> {
		val builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
		if (token != null) builder.header("Authorization", "Bearer $token")
		return http.send(
			builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
	}

	private fun obj(body: String): Map<*, *> = json.readValue(body, Map::class.java)

	/** A made-up value with the start of an Anthropic API key, put together here so no file holds a key-shaped literal. */
	private val fakeKey = listOf("sk", "ant", "api03", "Zx9".repeat(12)).joinToString("-")

	private fun register(body: String): HttpResponse<String> = call("POST", "/api/ai-accounts", body)

	@Test
	fun `an account is registered with generous default limits, changed, disabled and enabled, each audited`() {
		val created = register("""{"name":"회사 Team 플랜","auth_kind":"org_account","purpose":"회사 저장소 작업","data_terms":"학습에 쓰지 않는 조건"}""")
		assertEquals(201, created.statusCode(), created.body())
		val account = obj(created.body())["account"] as Map<*, *>
		val id = account["id"] as String
		assertTrue(id.startsWith("acc_"))
		assertEquals("organization", account["affiliation"])
		assertNull(account["owner_user_id"])
		assertEquals("device_login", account["auth_source"])
		assertEquals("subscription_login", account["auth_method"])
		val limits = account["limits"] as Map<*, *>
		assertEquals(200, limits["run_turn_limit"])
		assertEquals(60, limits["run_minute_limit"])
		assertEquals(30.0, (limits["run_cost_limit_usd"] as Number).toDouble())
		assertEquals(200.0, (limits["daily_cost_limit_usd"] as Number).toDouble())
		assertNull(account["block"])
		assertEquals(0, ((account["today"] as Map<*, *>)["runs"] as Number).toInt())

		val changed = call("PATCH", "/api/ai-accounts/$id", """{"version":${account["version"]},"daily_cost_limit_usd":50,"run_minute_limit":null}""")
		assertEquals(200, changed.statusCode(), changed.body())
		val after = obj(changed.body())["account"] as Map<*, *>
		assertEquals(50.0, ((after["limits"] as Map<*, *>)["daily_cost_limit_usd"] as Number).toDouble())
		assertNull((after["limits"] as Map<*, *>)["run_minute_limit"])
		// A stale version is refused; the kind of account does not change.
		assertEquals(409, call("PATCH", "/api/ai-accounts/$id", """{"version":0,"name":"x"}""").statusCode())
		val kind = call("PATCH", "/api/ai-accounts/$id", """{"version":${after["version"]},"auth_kind":"api_key"}""")
		assertEquals(400, kind.statusCode())
		assertContains(kind.body(), "새 계정으로 등록한다")

		val disabled = call("POST", "/api/ai-accounts/$id/disable", """{"version":${after["version"]}}""")
		assertEquals(200, disabled.statusCode(), disabled.body())
		val d = obj(disabled.body())
		assertEquals("disabled", (d["account"] as Map<*, *>)["status"])
		val listed = json.readValue(call("GET", "/api/ai-accounts").body(), List::class.java).map { (it as Map<*, *>)["id"] }
		assertTrue(id in listed)
		val active = json.readValue(call("GET", "/api/ai-accounts?include_disabled=false").body(), List::class.java).map { (it as Map<*, *>)["id"] }
		assertFalse(id in active)
		val v = ((d["account"] as Map<*, *>)["version"] as Number).toLong()
		assertEquals(200, call("POST", "/api/ai-accounts/$id/enable", """{"version":$v}""").statusCode())

		val history = (obj(call("GET", "/api/ai-accounts/$id").body())["history"] as List<*>).map { (it as Map<*, *>)["action"] }
		assertEquals(listOf("account.enabled", "account.disabled", "account.updated", "account.registered"), history)
	}

	@Test
	fun `a value shaped like a key is refused in every field, never echoed and never stored`() {
		val marker = "zmb-" + UUID.randomUUID()
		for (body in listOf(
			"""{"name":"$fakeKey","auth_kind":"api_key"}""",
			"""{"name":"$marker","auth_kind":"api_key","purpose":"키는 $fakeKey 이다"}""",
			"""{"name":"$marker","auth_kind":"api_key","data_terms":"${"AKIA" + "ABCDEFGHIJKLMNOP"}"}""",
			"""{"name":"$marker","auth_kind":"api_key","auth_source":"$fakeKey"}""",
			"""{"name":"$marker","auth_kind":"api_key","purpose":"Zq7Yt2Lm9Pw4Rx8Vb3Nc6Kd1Hf5Gs0Ja"}""",
		)) {
			val r = register(body)
			assertEquals(400, r.statusCode(), body)
			assertFalse(r.body().contains(fakeKey), "the refusal repeats the value")
			assertFalse(r.body().contains("Zq7Yt2Lm9Pw4Rx8Vb3Nc6Kd1Hf5Gs0Ja"), "the refusal repeats the value")
		}
		// Another environment variable would mean moving the key into the run: the vault's injection (stage 2).
		val other = register("""{"name":"$marker","auth_kind":"api_key","auth_source":"env:MY_WORK_KEY"}""")
		assertEquals(400, other.statusCode())
		assertContains(other.body(), "실행 주입")
		val stored = jdbc.sql("select count(*) from ai_accounts where name = :n").param("n", marker).query(Long::class.javaObjectType).single()
		assertEquals(0, stored)
		val leaked = jdbc.sql("select count(*) from audit_log where details::text like :k").param("k", "%sk-ant-%").query(Long::class.javaObjectType).single()
		assertEquals(0, leaked)

		// Identifiers and commit hashes a person writes are not taken for keys.
		val allowed = register(
			"""{"name":"작업 기록용","auth_kind":"personal_subscription","purpose":"run_${UUID.randomUUID()} 커밋 ${"a1b2c3d4e5".repeat(4)} 이후 작업"}""",
		)
		assertEquals(201, allowed.statusCode(), allowed.body())

		// The export carries the accounts and usage signals, and no key.
		val ok = register("""{"name":"내 API 키","auth_kind":"api_key","affiliation":"personal"}""")
		assertEquals(201, ok.statusCode(), ok.body())
		val export = call("GET", "/api/export")
		assertEquals(200, export.statusCode())
		assertFalse(export.body().contains("sk-ant-"))
		val collections = obj(export.body())["collections"] as Map<*, *>
		val records = (collections["ai_accounts"] as Map<*, *>)["records"] as List<*>
		val mine = records.map { it as Map<*, *> }.single { it["name"] == "내 API 키" }
		assertEquals("env:ANTHROPIC_API_KEY", mine["auth_source"])
		assertEquals("usr_owner", mine["owner_user_id"])
		assertTrue("usage_signals" in collections)
	}

	@Test
	fun `accounts are person actions`() {
		val device = obj(call("POST", "/api/devices", """{"name":"acc-pc"}""").body())["device_id"] as String
		val registered = obj(
			call(
				"POST", "/api/sessions",
				"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
			).body(),
		)
		val token = registered["token"] as String
		assertEquals(403, call("GET", "/api/ai-accounts", token = token).statusCode())
		assertEquals(403, call("POST", "/api/ai-accounts", """{"name":"x","auth_kind":"api_key"}""", token).statusCode())
		assertEquals(403, call("GET", "/api/usage/daily", token = token).statusCode())
		assertEquals(200, call("GET", "/api/usage/daily?days=3").statusCode())
	}
}
