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
import kotlin.test.assertTrue

/** The read APIs the Web and desktop screens poll, over real HTTP in snake_case. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReadApiTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val service: RunService,
) {

	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	private fun get(path: String): Pair<Int, Any?> {
		val response = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(), HttpResponse.BodyHandlers.ofString())
		val body = response.body().takeIf { it.startsWith("[") || it.startsWith("{") }?.let { json.readValue(it, Any::class.java) }
		return response.statusCode() to body
	}

	@Test
	fun `a screen can list work items, their runs and new events in seq order`() {
		val work = service.createWorkItem("read api", "usr_owner")
		val run = service.createRun(work.id, "claude_code")
		val gen = service.claim(run.id, "dev_read").generation
		service.ingest(run.id, EventBatch(1, (1L..3L).map { seq ->
			EventIn("evt_read_${run.id}_$seq", gen, seq, if (seq == 1L) "lifecycle" else "answer",
				if (seq == 1L) mapOf("phase" to "started") else mapOf("text" to "line $seq"))
		}))

		val (itemsStatus, items) = get("/api/work-items?limit=100")
		assertEquals(200, itemsStatus)
		val item = (items as List<*>).map { it as Map<*, *> }.single { it["id"] == work.id }
		assertEquals("ready", item["stage"])
		assertEquals(emptyList<Any>(), item["wait_reasons"])

		val (runsStatus, runs) = get("/api/work-items/${work.id}/runs")
		assertEquals(200, runsStatus)
		val runRow = (runs as List<*>).single() as Map<*, *>
		assertEquals(run.id, runRow["id"])
		assertEquals("dev_read", runRow["device_id"])

		val (_, all) = get("/api/runs/${run.id}/events")
		assertEquals(listOf(1, 2, 3), (all as List<*>).map { ((it as Map<*, *>)["seq"] as Number).toInt() })
		val (_, newer) = get("/api/runs/${run.id}/events?after_seq=1")
		val events = (newer as List<*>).map { it as Map<*, *> }
		assertEquals(listOf(2, 3), events.map { (it["seq"] as Number).toInt() })
		assertEquals("line 2", (events.first()["payload"] as Map<*, *>)["text"])
		assertTrue(events.all { (it["event_id"] as String).startsWith("evt_read_") })
	}

	@Test
	fun `unknown work items and runs are 404`() {
		assertEquals(404, get("/api/work-items/wrk_missing/runs").first)
		assertEquals(404, get("/api/runs/run_missing/events").first)
	}
}
