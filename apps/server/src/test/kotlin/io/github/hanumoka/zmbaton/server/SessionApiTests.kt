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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The session and first-screen APIs over real HTTP, as the CLI and the Web screen call them. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SessionApiTests @Autowired constructor(
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

	private fun registerSession(device: String, repo: String): Pair<String, String> {
		val (status, body) = call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_version":"2.1.296",
			   "executor_session_id":"${UUID.randomUUID()}","repository":{"repo_key":"$repo","prefix":"","branch":"main"}}""",
		)
		assertEquals(201, status, body)
		val o = obj(body)
		assertTrue((o["status_card"] as String).contains(o["session_id"] as String))
		return o["session_id"] as String to o["token"] as String
	}

	private fun touch(session: Pair<String, String>, repo: String, seq: Int, path: String): Map<*, *> {
		val (status, body) = call(
			"POST", "/api/sessions/${session.first}/events",
			"""{"protocol_version":2,"events":[{"event_id":"evt_${UUID.randomUUID()}","local_seq":$seq,"kind":"file_touch",
			    "payload":{"repo_key":"$repo","path":"$path","tool":"Write"}}]}""",
			token = session.second,
		)
		assertEquals(200, status, body)
		return obj(body)
	}

	@Test
	fun `device, two sessions, a conflict on the first screen, and a person's judgement`() {
		val (devStatus, devBody) = call("POST", "/api/devices", """{"name":"owner-pc","os":"windows"}""")
		assertEquals(201, devStatus)
		val device = obj(devBody)["device_id"] as String
		val repo = "git:" + UUID.randomUUID().toString().replace("-", "")
		val a = registerSession(device, repo)
		val b = registerSession(device, repo)
		assertEquals(1, (touch(a, repo, 1, "README.md")["stored"] as Number).toInt())
		touch(b, repo, 1, "README.md")

		val (inboxStatus, inboxBody) = call("GET", "/api/inbox")
		assertEquals(200, inboxStatus)
		val items = json.readValue(inboxBody, List::class.java).map { it as Map<*, *> }
		val conflict = items.single { (it["summary"] as String).contains("README.md") && (it["detail"] as Map<*, *>)["repo_key"] == repo }
		assertEquals(3, (conflict["bucket"] as Number).toInt())

		val (sessionsStatus, sessionsBody) = call("GET", "/api/sessions")
		assertEquals(200, sessionsStatus)
		assertTrue(sessionsBody.contains(a.first) && sessionsBody.contains(b.first))

		val (judged, _) = call("POST", "/api/notices/${conflict["source_id"]}/status", """{"status":"acknowledged"}""")
		assertEquals(200, judged)
		val after = call("GET", "/api/inbox").second
		assertTrue(!after.contains(conflict["id"] as String))
	}

	@Test
	fun `session tokens are refused for person actions and the refusal is audited`() {
		val device = obj(call("POST", "/api/devices", """{"name":"pc"}""").second)["device_id"] as String
		val session = registerSession(device, "git:" + UUID.randomUUID().toString().replace("-", ""))
		assertEquals(403, call("GET", "/api/inbox", token = session.second).first)
		assertEquals(403, call("GET", "/api/sessions", token = session.second).first)
		assertEquals(403, call("POST", "/api/notices/ntc_x/status", """{"status":"resolved"}""", token = session.second).first)
		val refusals = jdbc.sql("select count(*) from audit_log where actor_id = :id and action = 'person_action.refused'")
			.param("id", session.first).query(Long::class.javaObjectType).single()
		assertEquals(3L, refusals)
	}

	@Test
	fun `session endpoints need the token and protocol version 2`() {
		val device = obj(call("POST", "/api/devices", """{"name":"pc"}""").second)["device_id"] as String
		val session = registerSession(device, "git:" + UUID.randomUUID().toString().replace("-", ""))
		val events = """{"protocol_version":2,"events":[]}"""
		assertEquals(401, call("POST", "/api/sessions/${session.first}/events", events).first)
		assertEquals(401, call("POST", "/api/sessions/${session.first}/events", events, token = "zmbs_wrong").first)
		assertEquals(400, call("POST", "/api/sessions/${session.first}/events", """{"protocol_version":1,"events":[]}""", token = session.second).first)
		assertEquals(200, call("GET", "/api/sessions/${session.first}/status-card", token = session.second).first)
		assertEquals(
			400,
			call(
				"POST", "/api/sessions",
				"""{"protocol_version":1,"device_id":"$device","executor":"claude_code","executor_session_id":"x"}""",
			).first,
		)
		assertEquals(
			404,
			call(
				"POST", "/api/sessions",
				"""{"protocol_version":2,"device_id":"dev_missing","executor":"claude_code","executor_session_id":"x"}""",
			).first,
		)
	}
}
