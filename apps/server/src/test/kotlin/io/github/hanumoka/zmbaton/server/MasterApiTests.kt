package io.github.hanumoka.zmbaton.server

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The master's API path (MasterApi.kt) against a fake Messages API on this machine. The key is a
 * made-up value given by a test bean; no real key is used, made or looked for. The checks: a cheaper
 * model screens, a stronger one confirms, the answer is checked like the local path's, the cost is
 * recorded on the run, the key reaches only the request header, and a failure hands the check to the
 * owner's local connector.
 */
@Import(TestcontainersConfiguration::class, MasterApiTests.FakeKey::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["zm-baton.master.sweep-ms=3600000", "zm-baton.master.daily-limit=50", "zm-baton.master.api.key-env=ZMB_TEST_FAKE_KEY"],
)
class MasterApiTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
	private val checks: MasterCheckService,
	private val runner: MasterApiRunner,
) {
	@TestConfiguration(proxyBeanMethods = false)
	class FakeKey {
		@Bean
		@Primary
		fun fakeMasterApiKey(): MasterApiKey = MasterApiKey { name -> if (name == "ZMB_TEST_FAKE_KEY") FAKE_KEY else null }
	}

	/** What the fake API saw and what it answers next. */
	data class Seen(val path: String, val headers: Map<String, String?>, val body: Map<*, *>)

	companion object {
		/** Not a key: a marker this test looks for wherever the key must not be. */
		const val FAKE_KEY = "zmb-test-not-a-real-key-7f3c"
		val seen = ConcurrentLinkedQueue<Seen>()
		val replies = ConcurrentLinkedQueue<Pair<Int, String>>()
		private val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
		private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
			createContext("/v1/messages") { ex ->
				val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
				seen += Seen(ex.requestURI.path, listOf("x-api-key", "anthropic-version", "anthropic-beta", "content-type")
					.associateWith { ex.requestHeaders.getFirst(it) }, mapper.readValue(body, Map::class.java))
				val (status, text) = replies.poll() ?: (500 to """{"type":"error","error":{"type":"api_error","message":"no reply set"}}""")
				val bytes = text.toByteArray(Charsets.UTF_8)
				ex.responseHeaders.add("content-type", "application/json")
				ex.sendResponseHeaders(status, bytes.size.toLong())
				ex.responseBody.use { it.write(bytes) }
			}
			start()
		}

		@JvmStatic
		@DynamicPropertySource
		fun api(registry: DynamicPropertyRegistry) {
			registry.add("zm-baton.master.api.base-url") { "http://127.0.0.1:${server.address.port}" }
		}

		@JvmStatic
		@AfterAll
		fun stop() = server.stop(0)

		fun message(model: String, text: String, input: Int, output: Int, stop: String = "end_turn"): String = mapper.writeValueAsString(
			mapOf("id" to "msg_test", "type" to "message", "role" to "assistant", "model" to model, "stop_reason" to stop,
				"content" to listOf(mapOf("type" to "text", "text" to text)), "usage" to mapOf("input_tokens" to input, "output_tokens" to output)),
		)
	}

	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	@BeforeEach
	fun clear() {
		seen.clear()
		replies.clear()
	}

	private fun ok(method: String, path: String, body: Any? = null): Map<*, *> {
		val request = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
			.method(method, body?.let { HttpRequest.BodyPublishers.ofString(json.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody()).build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		assertTrue(response.statusCode() in 200..299, "$method $path: ${response.statusCode()} ${response.body()}")
		return json.readValue(response.body(), Map::class.java)
	}

	private fun project(): String {
		val id = "prj_" + UUID.randomUUID().toString().replace("-", "")
		jdbc.sql("insert into projects (id, org_id, name) values (:id, 'org_owner', 'api')").param("id", id).update()
		return id
	}

	private fun approved(title: String, body: String, project: String): String {
		val d = ok("POST", "/api/documents", mapOf("title" to title, "source_of_truth" to "product", "project_id" to project, "body_markdown" to body))
		val id = (d["document"] as Map<*, *>)["id"] as String
		ok("POST", "/api/documents/$id/versions/1/approvals", mapOf("decision" to "approved"))
		return id
	}

	private fun check(doc: String): Map<String, Any?> =
		jdbc.sql(
			"""
			select c.id, c.state, c.path, c.run_id, c.api_run_id, c.work_item_id, c.outcome::text, c.reason from master_checks c
			  join document_versions v on c.subjects @> jsonb_build_array(jsonb_build_object('id', v.id))
			 where v.doc_id = :d order by c.created_at desc limit 1
			""".trimIndent(),
		).param("d", doc).query { rs, _ ->
			mapOf("id" to rs.getString(1), "state" to rs.getString(2), "path" to rs.getString(3), "run_id" to rs.getString(4),
				"api_run_id" to rs.getString(5), "work_item_id" to rs.getString(6), "outcome" to rs.getString(7)?.let { json.readValue(it, Map::class.java) },
				"reason" to rs.getString(8))
		}.single()

	@Test
	fun `with the key set, a cheaper model screens, a stronger one confirms, and the checked answer becomes a notice`() {
		assertEquals(true, ok("GET", "/api/settings/master")["api_path"])
		val p = project()
		val a = approved("인증 안내", "# 인증\n\n마스터는 서버에서 API 키로 돈다.", p)
		replies += 200 to message("claude-haiku-5-5", """{"candidates":[
			{"left":"S1","left_quote":"마스터는 개인 구독으로 서버에서 돈다.","right":"C1","right_quote":"마스터는 서버에서 API 키로 돈다.","reason":"인증 방식"},
			{"left":"S1","left_quote":"없는 문장이다.","right":"C1","right_quote":"마스터는 서버에서 API 키로 돈다.","reason":"지어냄"}]}""", 1200, 90)
		replies += 200 to message("claude-opus-5-5", """{"candidates":[
			{"left":"S1","left_quote":"마스터는 개인 구독으로 서버에서 돈다.","right":"C1","right_quote":"마스터는 서버에서 API 키로 돈다.","reason":"서버에서 쓰는 자격이 다르다"}]}""", 1500, 60)
		val b = approved("인증 결정 요약", "# 요약\n\n마스터는 개인 구독으로 서버에서 돈다.", p)
		val requested = check(b)
		assertEquals("requested" to "api", requested["state"] to requested["path"])
		val run = requested["run_id"] as String
		val runRow = jdbc.sql("select r.executor, d.device_id from runs r join delegations d on d.id = r.delegation_id where r.id = :id")
			.param("id", run).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		assertEquals("anthropic_api" to null, runRow, "a central run: no device can claim it")

		assertEquals(1, runner.runPending())
		checks.sweep()

		val done = check(b)
		assertEquals("done", done["state"])
		val outcome = done["outcome"] as Map<*, *>
		assertEquals(listOf(2, 1, 1), listOf(outcome["screened"], outcome["proposed"], outcome["kept"]))
		val notice = (outcome["notice_ids"] as List<*>).single() as String
		val evidence = json.readValue(jdbc.sql("select evidence::text from notices where id = :id").param("id", notice).query(String::class.java).single(), Map::class.java)
		assertEquals("서버에서 쓰는 자격이 다르다" to "api", evidence["reason"] to evidence["master_path"])
		assertEquals(listOf(b, a), (evidence["sides"] as List<*>).map { (it as Map<*, *>)["id"] })

		// The screen saw only the instruction; the confirmation saw it with the candidates left after the check.
		val calls = seen.toList()
		assertEquals(listOf("claude-haiku-5-5", "claude-opus-5-5"), calls.map { it.body["model"] })
		assertTrue(calls.all { it.headers["x-api-key"] == FAKE_KEY && it.headers["anthropic-version"] == "2023-06-01" })
		assertTrue(calls.all { ((it.body["output_config"] as Map<*, *>)["format"] as Map<*, *>)["type"] == "json_schema" })
		assertNull(calls[0].headers["anthropic-beta"])
		assertEquals("server-side-fallback-2026-07-01" to "default", calls[1].headers["anthropic-beta"] to calls[1].body["fallbacks"])
		val confirmPrompt = ((calls[1].body["messages"] as List<*>).single() as Map<*, *>)["content"] as String
		assertContains(confirmPrompt, "다른 검토자가 다음 후보를 찾았다")
		assertFalse("없는 문장이다." in confirmPrompt, "a candidate whose quote failed the check is not paid for twice")

		// The run reports like any run: the price-list cost of both models, the work item moved on.
		val usage = jdbc.sql("select usage::text, auth_method, billing_location, state, end_reason from runs where id = :id").param("id", run)
			.query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)) }.single()
		val u = json.readValue(usage[0], Map::class.java)
		// Haiku 1200×0.10 + 90×0.50, Opus 1500×4.00 + 60×20.00, per million tokens.
		assertEquals(0.000165 + 0.0072, (u["cost_usd"] as Number).toDouble(), 1e-9)
		assertEquals(2700L to 150L, (u["input_tokens"] as Number).toLong() to (u["output_tokens"] as Number).toLong())
		assertEquals(listOf("api_key", "api", "ended", "submitted"), usage.drop(1))
		assertEquals("done", (ok("GET", "/api/work-items/${done["work_item_id"]}")["item"] as Map<*, *>)["stage"])

		// The key went in the request header and nowhere else.
		for (table in listOf("audit_log", "run_events", "runs", "master_checks", "notices", "delegations", "inbox_items")) {
			val dump = jdbc.sql("select coalesce(json_agg(t)::text, '') from $table t").query(String::class.java).single()
			assertFalse(FAKE_KEY in dump, "$table holds the key")
		}
		assertFalse(FAKE_KEY in ok("GET", "/api/settings/master").toString())
	}

	@Test
	fun `no candidate after the screen means no call to the stronger model`() {
		val p = project()
		approved("빈 후보 A", "# A\n\n빈 후보 문장이다.", p)
		replies += 200 to message("claude-haiku-5-5", """{"candidates":[]}""", 300, 10)
		val b = approved("빈 후보 B", "# B\n\n빈 후보 문장 둘이다.", p)
		runner.runPending()
		checks.sweep()
		assertEquals(1, seen.size)
		val done = check(b)
		assertEquals("done", done["state"])
		assertEquals(0, (done["outcome"] as Map<*, *>)["kept"])
	}

	@Test
	fun `when the API refuses for credit, the same work item goes to the owner's local connector`() {
		val device = ok("POST", "/api/devices", mapOf("name" to "fallback-pc"))["device_id"] as String
		val p = project()
		approved("크레딧 A", "# A\n\n크레딧 문장이다.", p)
		replies += 402 to """{"type":"error","error":{"type":"billing_error","message":"Your credit balance is too low"}}"""
		val b = approved("크레딧 B", "# B\n\n크레딧 문장 둘이다.", p)
		val apiRun = check(b)["run_id"] as String
		runner.runPending()
		val apiEnd = jdbc.sql("select state, end_reason from runs where id = :id").param("id", apiRun)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		assertEquals("ended" to "failed", apiEnd)
		val error = jdbc.sql("select payload::text from run_events where run_id = :id and kind = 'error'").param("id", apiRun).query(String::class.java).single()
		assertContains(error, "402")
		assertContains(error, "billing_error")
		assertFalse("credit balance" in error, "the API's own message is not copied")

		checks.sweep()
		val local = check(b)
		assertEquals("requested" to "local", local["state"] to local["path"])
		assertEquals(apiRun, local["api_run_id"])
		val newRun = local["run_id"] as String
		val row = jdbc.sql(
			"""
			select r.work_item_id, r.executor, r.continue_reason, r.resumed_from_run_id, d.device_id, d.scope ->> 'purpose'
			  from runs r join delegations d on d.id = r.delegation_id where r.id = :id
			""".trimIndent(),
		).param("id", newRun).query { rs, _ -> (1..6).map { rs.getString(it) } }.single()
		assertEquals(listOf(local["work_item_id"], "claude_code", "replacement", apiRun, device, "master_check"), row)
		assertEquals(local["work_item_id"], jdbc.sql("select work_item_id from runs where id = :id").param("id", apiRun).query(String::class.java).single())
	}
}
