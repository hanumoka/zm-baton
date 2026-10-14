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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The relay over real HTTP, as the hook (session token) and the first screen (no token) call it. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RelayApiTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	private fun call(method: String, path: String, body: String? = null, token: String? = null): Pair<Int, String> {
		val builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
		if (token != null) builder.header("Authorization", "Bearer $token")
		val request = builder
			.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
			.build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		return response.statusCode() to response.body()
	}

	private fun obj(body: String): Map<*, *> = json.readValue(body, Map::class.java)

	private fun session(): Pair<String, String> {
		val device = obj(call("POST", "/api/devices", """{"name":"relay-api-pc"}""").second)["device_id"] as String
		val (status, body) = call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
		)
		assertEquals(201, status, body)
		return obj(body)["session_id"] as String to obj(body)["token"] as String
	}

	@Test
	fun `relay, read, answer and take over HTTP, and a session token cannot answer`() {
		val (sessionId, token) = session()
		val relayId = "rly_" + UUID.randomUUID().toString().replace("-", "")
		val fp = "0f".repeat(32)
		val (startStatus, startBody) = call(
			"POST", "/api/sessions/$sessionId/relays",
			"""{"protocol_version":2,"relay_id":"$relayId","kind":"permission_request","input_fingerprint":"$fp","local_seq":5,
			   "detail":{"tool_name":"Write","file_path":"docs/a.md","input":{"file_path":"docs/a.md","content":"hello"}}}""",
			token,
		)
		assertEquals(200, startStatus, startBody)
		val started = obj(startBody)
		assertEquals(true, started["relay"])
		assertEquals(60, started["wait_seconds"])
		assertNotNull(started["deadline"])
		val itemId = started["item_id"] as String

		val (_, inboxBody) = call("GET", "/api/inbox")
		val item = json.readValue(inboxBody, List::class.java).map { it as Map<*, *> }.single { it["id"] == itemId }
		assertEquals("permission_request", item["kind"])
		assertEquals("open", item["state"])
		assertNotNull(item["relay_deadline"])
		assertEquals("docs/a.md", (item["detail"] as Map<*, *>)["file_path"])

		// Contract "등록과 세션 토큰" 6: answering is a person action; a session token is refused and audited.
		val (refused, _) = call("POST", "/api/inbox/$itemId/answer", """{"decision":"allow"}""", token)
		assertEquals(403, refused)
		val audited = jdbc.sql("select count(*) from audit_log where action = 'person_action.refused' and actor_id = :s")
			.param("s", sessionId).query(Long::class.javaObjectType).single()
		assertTrue(audited >= 1)
		val (stillWaiting, waitingBody) = call("POST", "/api/sessions/$sessionId/relays/$relayId/take", """{"protocol_version":2,"input_fingerprint":"$fp"}""", token)
		assertEquals(200, stillWaiting)
		assertEquals("waiting", obj(waitingBody)["outcome"])

		val (answered, answeredBody) = call("POST", "/api/inbox/$itemId/answer", """{"decision":"deny","reason":"그 파일은 내가 고친다"}""")
		assertEquals(200, answered, answeredBody)
		val (late, lateBody) = call("POST", "/api/inbox/$itemId/answer", """{"decision":"allow"}""")
		assertEquals(409, late)
		assertContains(obj(lateBody)["message"] as String, "이미 답했다")

		val (took, tookBody) = call("POST", "/api/sessions/$sessionId/relays/$relayId/take", """{"protocol_version":2,"input_fingerprint":"$fp"}""", token)
		assertEquals(200, took, tookBody)
		val taken = obj(tookBody)
		assertEquals("delivered", taken["outcome"])
		assertEquals(mapOf("decision" to "deny", "reason" to "그 파일은 내가 고친다"), taken["answer"])
		val (noToken, _) = call("POST", "/api/sessions/$sessionId/relays/$relayId/take", """{"protocol_version":2,"input_fingerprint":"$fp"}""")
		assertEquals(401, noToken)
	}

	@Test
	fun `my settings - focus mode and the first screen's visibility, person actions only`() {
		val (_, token) = session()
		val (meStatus, meBody) = call("GET", "/api/me")
		assertEquals(200, meStatus)
		assertEquals("usr_owner", obj(meBody)["user_id"])
		assertEquals(60, obj(meBody)["relay_wait_seconds"])
		val (on, onBody) = call("PUT", "/api/me/focus", """{"on":true}""")
		assertEquals(200, on)
		assertEquals(true, obj(onBody)["focus_on"])
		assertEquals(false, obj(call("PUT", "/api/me/focus", """{"on":false}""").second)["focus_on"])
		assertEquals(true, obj(call("PUT", "/api/me/first-screen", """{"visible":true}""").second)["first_screen_visible"])
		assertEquals(false, obj(call("PUT", "/api/me/first-screen", """{"visible":false}""").second)["first_screen_visible"])
		assertEquals(403, call("PUT", "/api/me/focus", """{"on":true}""", token).first)
		assertEquals(403, call("PUT", "/api/me/first-screen", """{"visible":true}""", token).first)
		assertEquals(false, obj(call("GET", "/api/me").second)["focus_on"])
	}
}
