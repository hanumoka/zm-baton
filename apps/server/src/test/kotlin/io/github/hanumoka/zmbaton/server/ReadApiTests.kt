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
		assertEquals("running", runRow["state"])
		assertEquals(null, runRow["end_reason"])
		assertTrue(runRow["last_event_at"] is String)

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

	@Test
	fun `the run list shows the end reason the server applied`() {
		val work = service.createWorkItem("end reason", "usr_owner")
		val run = service.createRun(work.id, "claude_code")
		val gen = service.claim(run.id, "dev_read").generation
		// Two ended reports in separate batches, the later one with a smaller seq:
		// the server keeps the first one it applied, and the read API must say so.
		service.ingest(run.id, EventBatch(1, listOf(EventIn("evt_er_${run.id}_10", gen, 10, "lifecycle", mapOf("phase" to "ended", "end_reason" to "lost")))))
		service.ingest(run.id, EventBatch(1, listOf(EventIn("evt_er_${run.id}_9", gen, 9, "lifecycle", mapOf("phase" to "ended", "end_reason" to "submitted")))))
		val runRow = ((get("/api/work-items/${work.id}/runs").second as List<*>).single() as Map<*, *>)
		assertEquals("ended", runRow["state"])
		assertEquals("lost", runRow["end_reason"])
	}

	@Test
	fun `the run list is limited to the newest runs, oldest first`() {
		val work = service.createWorkItem("many runs", "usr_owner")
		val ids = (1..3).map {
			val run = service.createRun(work.id, "claude_code")
			val gen = service.claim(run.id, "dev_read").generation
			service.ingest(run.id, EventBatch(1, listOf(EventIn("evt_lim_${run.id}", gen, 1, "lifecycle", mapOf("phase" to "ended", "end_reason" to "replaced")))))
			run.id
		}
		val listed = (get("/api/work-items/${work.id}/runs?limit=2").second as List<*>).map { (it as Map<*, *>)["id"] }
		assertEquals(ids.takeLast(2), listed)
	}
}
