package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/** The five compat-test APIs over real HTTP, using the contract's snake_case wire format. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSmokeTests @Autowired constructor(env: Environment, private val json: ObjectMapper) {

	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	private fun call(method: String, path: String, body: String? = null): Pair<Int, Map<*, *>?> {
		val request = HttpRequest.newBuilder(URI.create(base + path))
			.header("Content-Type", "application/json")
			.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
			.build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		val parsed = response.body().takeIf { it.startsWith("{") }?.let { json.readValue(it, Map::class.java) }
		return response.statusCode() to parsed
	}

	@Test
	fun `create, poll, claim and report over http`() {
		val (created, work) = call("POST", "/api/work-items", """{"title":"smoke","responsible_user_id":"usr_owner"}""")
		assertEquals(201, created)
		val workId = work!!["id"] as String

		val (runStatus, run) = call("POST", "/api/work-items/$workId/runs", """{"executor":"claude_code"}""")
		assertEquals(201, runStatus)
		val runId = run!!["id"] as String

		val poll = http.send(
			HttpRequest.newBuilder(URI.create("$base/api/runs?state=requested")).GET().build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		assertEquals(200, poll.statusCode())
		assert(poll.body().contains(runId))

		val (claimStatus, claim) = call("POST", "/api/runs/$runId/claim", """{"device_id":"dev_smoke"}""")
		assertEquals(200, claimStatus)
		val generation = (claim!!["generation"] as Number).toLong()
		assertEquals(45, (claim["lease_seconds"] as Number).toInt())

		val (secondClaim, _) = call("POST", "/api/runs/$runId/claim", """{"device_id":"dev_other"}""")
		assertEquals(409, secondClaim)

		val events = """
			{"protocol_version":1,"events":[
			 {"event_id":"evt_smoke_1","generation":$generation,"seq":1,"kind":"lifecycle","payload":{"phase":"started","session_id":"sess_1"}},
			 {"event_id":"evt_smoke_2","generation":$generation,"seq":2,"kind":"answer","payload":{"text":"done"}}
			]}
		""".trimIndent()
		val (eventStatus, result) = call("POST", "/api/runs/$runId/events", events)
		assertEquals(200, eventStatus)
		assertEquals(2, (result!!["stored"] as Number).toInt())

		val (badVersion, _) = call("POST", "/api/runs/$runId/events", """{"protocol_version":9,"events":[]}""")
		assertEquals(400, badVersion)
	}
}
