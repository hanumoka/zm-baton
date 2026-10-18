package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The audit API's reads added for the audit screen (Audit.kt): the actor class of each row, the work
 * item of a run target, filters by actor class and from a time on, one row by id, and the chips'
 * counts. All read-only; a session token is refused as for the list.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditScreenApiTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val sessions: SessionService,
	private val jdbc: JdbcClient,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	private fun call(method: String, path: String, body: String? = null, token: String? = null): Pair<Int, String> {
		val builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
		if (token != null) builder.header("Authorization", "Bearer $token")
		val response = http.send(
			builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		return response.statusCode() to response.body()
	}

	private fun rows(path: String): List<Map<*, *>> {
		val (status, body) = call("GET", path)
		assertEquals(200, status, body)
		return json.readValue(body, List::class.java).map { it as Map<*, *> }
	}

	private fun obj(path: String): Map<*, *> {
		val (status, body) = call("GET", path)
		assertEquals(200, status, body)
		return json.readValue(body, Map::class.java)
	}

	private fun q(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

	/** Four rows about one new target, one per actor class. */
	private fun fourActors(): String {
		val target = "wrk_" + UUID.randomUUID()
		// Audit reads now require a readable target, including the run acting on its behalf.
		jdbc.sql("insert into work_items (id, title, stage, responsible_user_id) values (:id, 'audit fixture', 'draft', 'usr_owner')")
			.param("id", target).update()
		val run = Ids.run()
		jdbc.sql("insert into runs (id, work_item_id, state, end_reason, executor) values (:id, :w, 'ended', 'submitted', 'claude_code')")
			.param("id", run).param("w", target).update()
		sessions.audit("person", SessionService.OWNER, "work_item.updated", "wrk", target, mapOf("fields" to listOf("title")))
		sessions.audit("session", "ses_x", "session.sharing_changed", "wrk", target, mapOf("share" to false))
		sessions.audit("system", run, "work_item.stage_changed", "wrk", target, mapOf("to" to "in_progress"))
		sessions.audit("system", SessionService.SYSTEM_ACTOR, "session.expired", "wrk", target, emptyMap())
		return target
	}

	@Test
	fun `each row says its actor class, and the list and the counts filter by it`() {
		val target = fourActors()
		val all = rows("/api/audit?target_id=$target")
		assertEquals(listOf("system", "run", "session", "person"), all.map { it["actor_class"] })
		val runs = rows("/api/audit?target_id=$target&actor_class=run")
		assertEquals(listOf("work_item.stage_changed"), runs.map { it["action"] })
		assertEquals(listOf("server"), rows("/api/audit?target_id=$target&actor_class=system").map { it["actor_id"] })
		val facets = obj("/api/audit/facets?target_id=$target&actor_class=run")
		assertEquals(1, facets["total"])
		// The actor classes are counted without their own filter, so every chip shows its number.
		assertEquals(mapOf("person" to 1, "session" to 1, "run" to 1, "system" to 1), facets["actor_classes"])
		assertEquals(mapOf("wrk" to 1), facets["target_kinds"])
		assertEquals(400, call("GET", "/api/audit?actor_class=robot").first)
	}

	@Test
	fun `rows from a time on, rows without a target, and one row by its id`() {
		val target = fourActors()
		val hourAgo = q(OffsetDateTime.now().minusHours(1).toString())
		val inAnHour = q(OffsetDateTime.now().plusHours(1).toString())
		assertEquals(4, rows("/api/audit?target_id=$target&after=$hourAgo").size)
		assertEquals(0, rows("/api/audit?target_id=$target&after=$inAnHour").size)
		assertEquals(400, call("GET", "/api/audit?after=yesterday").first)

		val actor = "retention-" + UUID.randomUUID()
		sessions.audit("system", actor, "retention.purged", null, null, mapOf("deleted" to emptyMap<String, Int>()))
		val untargeted = rows("/api/audit?actor_id=$actor&target_kind=none")
		assertEquals(1, untargeted.size)
		assertNull(untargeted.single()["target_kind"])
		val kinds = obj("/api/audit/facets?after=$hourAgo")["target_kinds"] as Map<*, *>
		assertTrue((kinds["none"] as Number).toLong() >= 1, kinds.toString())

		val id = untargeted.single()["id"] as String
		val one = obj("/api/audit/$id")
		assertEquals("retention.purged" to "system", one["action"] to one["actor_class"])
		assertEquals(404, call("GET", "/api/audit/aud_missing").first)
	}

	@Test
	fun `a run target carries its work item, so the screen can open the run record`() {
		val (status, body) = call("POST", "/api/work-items", """{"title":"audit screen run","responsible_user_id":"usr_owner"}""")
		assertEquals(201, status, body)
		val work = json.readValue(body, Map::class.java)["id"] as String
		val (made, runBody) = call("POST", "/api/work-items/$work/runs", """{"executor":"claude_code"}""")
		assertEquals(201, made, runBody)
		val run = json.readValue(runBody, Map::class.java)["id"] as String
		val row = rows("/api/audit?target_kind=run&target_id=$run").single()
		assertEquals("run.requested" to work, row["action"] to row["target_work_item_id"])
		assertNull(rows("/api/audit?target_kind=wrk&target_id=$work").first()["target_work_item_id"])
	}

	@Test
	fun `a session token cannot read the counts or one row`() {
		val (_, deviceBody) = call("POST", "/api/devices", """{"name":"audit-screen-pc"}""")
		val device = json.readValue(deviceBody, Map::class.java)["device_id"] as String
		val (_, sessionBody) = call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}"}""",
		)
		val token = json.readValue(sessionBody, Map::class.java)["token"] as String
		assertEquals(403, call("GET", "/api/audit/facets", token = token).first)
		assertEquals(403, call("GET", "/api/audit/aud_x", token = token).first)
	}
}
