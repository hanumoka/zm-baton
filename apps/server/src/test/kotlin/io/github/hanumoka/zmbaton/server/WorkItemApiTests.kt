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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Work items a person creates, edits and moves, over real HTTP as the Web screen calls them. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkItemApiTests @Autowired constructor(
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

	private fun create(title: String, extra: String = ""): String {
		val (status, body) = call(
			"POST", "/api/work-items",
			"""{"title":"$title","responsible_user_id":"usr_owner","executor_kind":"human","initial_stage":"draft"$extra}""",
		)
		assertEquals(201, status, body)
		assertEquals("draft", obj(body)["stage"])
		return obj(body)["id"] as String
	}

	private fun detail(id: String): Map<*, *> {
		val (status, body) = call("GET", "/api/work-items/$id")
		assertEquals(200, status, body)
		return obj(body)
	}

	private fun item(detail: Map<*, *>): Map<*, *> = detail["item"] as Map<*, *>

	private fun version(id: String): Long = (item(detail(id))["version"] as Number).toLong()

	private fun move(id: String, to: String, note: String? = null): Pair<Int, String> =
		call("POST", "/api/work-items/$id/transitions",
			"""{"to":"$to","version":${version(id)}${note?.let { ",\"note\":\"$it\"" } ?: ""}}""")

	private fun <T> withSkipped(stages: List<String>, block: () -> T): T {
		val before = obj(call("GET", "/api/settings/workflow").second)["skipped_stages"]
		val (status, body) = call("PUT", "/api/settings/workflow", json.writeValueAsString(mapOf("skipped_stages" to stages)))
		assertEquals(200, status, body)
		try {
			return block()
		} finally {
			call("PUT", "/api/settings/workflow", json.writeValueAsString(mapOf("skipped_stages" to before)))
		}
	}

	@Test
	fun `the compat-test body still creates a ready work item without an executor`() {
		val (status, body) = call("POST", "/api/work-items", """{"title":"compat","responsible_user_id":"usr_owner"}""")
		assertEquals(201, status, body)
		assertEquals("ready", obj(body)["stage"])
		val d = detail(obj(body)["id"] as String)
		assertNull(item(d)["executor_kind"])
		assertEquals("product", item(d)["source_of_truth"])
	}

	@Test
	fun `a work item runs through every stage when the organisation uses them all`() = withSkipped(emptyList()) {
		val id = create("all stages")
		val (planStatus, planBody) = move(id, "plan_review")
		assertEquals(200, planStatus, planBody)
		assertEquals("plan_review", item(obj(planBody))["stage"])
		assertEquals("ready", item(obj(move(id, "ready").second))["stage"])
		assertEquals("in_progress", item(obj(move(id, "in_progress").second))["stage"])
		assertEquals("result_review", item(obj(move(id, "result_review").second))["stage"])
		// Sending a result back needs a reason.
		assertEquals(400, move(id, "in_progress").first)
		assertEquals("in_progress", item(obj(move(id, "in_progress", "시험이 하나 빠졌다").second))["stage"])
		assertEquals("result_review", item(obj(move(id, "result_review").second))["stage"])
		val done = obj(move(id, "done").second)
		assertEquals("done", item(done)["stage"])
		assertTrue(item(done)["closed_at"] != null)
		assertEquals(emptyList<Any>(), done["moves"])
		val history = (done["history"] as List<*>).map { (it as Map<*, *>)["action"] }
		assertEquals("work_item.created", history.last())
		assertEquals(7, history.count { it == "work_item.stage_changed" })
	}

	@Test
	fun `a one-person organisation passes through the review stages`() = withSkipped(listOf("plan_review", "result_review")) {
		val id = create("skip reviews")
		val ready = obj(move(id, "plan_review").second)
		assertEquals("ready", item(ready)["stage"])
		val change = (ready["history"] as List<*>).first() as Map<*, *>
		assertEquals(listOf("plan_review", "ready"), (change["details"] as Map<*, *>)["path"])
		move(id, "in_progress")
		val moves = (detail(id)["moves"] as List<*>).map { it as Map<*, *> }
		assertEquals(listOf("result_review", "done"), moves.first()["path"])
		assertEquals("done", item(obj(move(id, "result_review").second))["stage"])
	}

	@Test
	fun `a stale version and a move the table lacks are refused`() {
		val id = create("stale")
		val (stale, staleBody) = call("POST", "/api/work-items/$id/transitions", """{"to":"cancelled","version":99}""")
		assertEquals(409, stale, staleBody)
		assertContains(staleBody, "changed")
		val (jump, jumpBody) = move(id, "done")
		assertEquals(409, jump, jumpBody)
		assertEquals(400, move(id, "nowhere").first)
		assertEquals("draft", item(detail(id))["stage"])
	}

	@Test
	fun `fields change only when present and a cleared field becomes empty`() {
		val project = Ids.project()
		jdbc.sql("insert into projects (id, org_id, external_ref, name) values (:id, 'org_owner', null, 'test project')")
			.param("id", project).update()
		val id = create("fields", ""","acceptance":"화면에서 확인","next_action":"첫 단계","due_on":"2026-11-01"""")
		val (status, body) = call("PATCH", "/api/work-items/$id",
			"""{"version":${version(id)},"next_action":null,"project_id":"$project","title":"fields 2"}""")
		assertEquals(200, status, body)
		val changed = item(obj(body))
		assertEquals("fields 2", changed["title"])
		assertNull(changed["next_action"])
		assertEquals("2026-11-01", changed["due_on"])
		assertEquals(project, (changed["project"] as Map<*, *>)["id"])
		assertEquals("화면에서 확인", obj(body)["acceptance"])
		assertEquals(400, call("PATCH", "/api/work-items/$id", """{"version":${version(id)},"stage":"done"}""").first)
		assertEquals(400, call("PATCH", "/api/work-items/$id", """{"version":${version(id)},"due_on":"next week"}""").first)
		assertEquals(400, call("PATCH", "/api/work-items/$id", """{"version":${version(id)},"project_id":"prj_missing"}""").first)
		val listed = obj("""{"items":${call("GET", "/api/work-items?limit=500&project_id=$project").second}}""")["items"] as List<*>
		assertEquals(listOf(id), listed.map { (it as Map<*, *>)["id"] })
	}

	@Test
	fun `a person sets and clears their own wait reasons but not the server's`() {
		val id = create("waits")
		assertEquals(400, call("POST", "/api/work-items/$id/wait-reasons", """{"reason":"waiting_other","version":${version(id)}}""").first)
		val (set, setBody) = call("POST", "/api/work-items/$id/wait-reasons",
			"""{"reason":"waiting_other","version":${version(id)},"note":"외부 회신 대기"}""")
		assertEquals(200, set, setBody)
		assertEquals(listOf("waiting_other"), item(obj(setBody))["wait_reasons"])
		assertEquals("외부 회신 대기", item(obj(setBody))["wait_note"])
		assertEquals(400, call("POST", "/api/work-items/$id/wait-reasons", """{"reason":"outcome_unknown","version":${version(id)}}""").first)
		val (cleared, clearedBody) = call("DELETE", "/api/work-items/$id/wait-reasons/waiting_other?version=${version(id)}")
		assertEquals(200, cleared, clearedBody)
		assertEquals(emptyList<Any>(), item(obj(clearedBody))["wait_reasons"])
		assertNull(item(obj(clearedBody))["wait_note"])
		assertEquals(409, call("DELETE", "/api/work-items/$id/wait-reasons/waiting_other?version=${version(id)}").first)
	}

	@Test
	fun `closing a work item drops its wait reasons`() {
		val id = create("close with wait")
		call("POST", "/api/work-items/$id/wait-reasons", """{"reason":"waiting_predecessor","version":${version(id)}}""")
		val cancelled = item(obj(move(id, "cancelled").second))
		assertEquals("cancelled", cancelled["stage"])
		assertEquals(emptyList<Any>(), cancelled["wait_reasons"])
	}

	@Test
	fun `open, project and text filters narrow the list`() {
		val marker = "needle-" + UUID.randomUUID().toString().take(8)
		val open = create("$marker open")
		val closed = create("$marker closed")
		move(closed, "cancelled")
		fun ids(query: String) = (json.readValue(call("GET", "/api/work-items?limit=500&$query").second, List::class.java))
			.map { (it as Map<*, *>)["id"] }.toSet()
		assertEquals(setOf(open, closed), ids("q=$marker"))
		assertEquals(setOf(open), ids("q=$marker&open=true"))
	}

	@Test
	fun `session tokens are refused for work items, projects and settings`() {
		val token = "zmbs_not_a_person"
		assertEquals(403, call("GET", "/api/work-items", token = token).first)
		assertEquals(403, call("POST", "/api/work-items", """{"title":"x","responsible_user_id":"usr_owner"}""", token).first)
		assertEquals(403, call("GET", "/api/projects", token = token).first)
		assertEquals(403, call("PUT", "/api/settings/workflow", """{"skipped_stages":[]}""", token).first)
		assertEquals(403, call("GET", "/api/decisions", token = token).first)
		assertEquals(403, call("POST", "/api/imports/zm-docs", """{"repo_key":"git:abc1234","files":[]}""", token).first)
		assertEquals(403, call("GET", "/api/repositories", token = token).first)
		assertEquals(403, call("POST", "/api/work-items/wrk_x/delegations", """{"executor":"claude_code","device_id":"d","repo_key":"git:abc1234","version":0}""", token).first)
		assertEquals(403, call("POST", "/api/runs/run_x/withdraw", """{"version":0}""", token).first)
		assertEquals(403, call("POST", "/api/runs/run_x/cancel", """{"version":0}""", token).first)
		assertEquals(403, call("POST", "/api/runs/run_x/inputs", """{"text":"x"}""", token).first)
		assertEquals(403, call("POST", "/api/devices/dev_x/repositories", """{"repo_key":"git:abc1234","name":"x"}""", token).first)
	}

	@Test
	fun `a person hands a work item to a device's repository over http and takes it back`() = withSkipped(listOf("plan_review", "result_review")) {
		val (devStatus, devBody) = call("POST", "/api/devices", """{"name":"http-pc","os":"windows"}""")
		assertEquals(201, devStatus, devBody)
		val device = obj(devBody)["device_id"] as String
		val key = "git:" + UUID.randomUUID().toString().replace("-", "")
		val (addStatus, addBody) = call("POST", "/api/devices/$device/repositories", """{"repo_key":"$key","name":"demo","remote":"git@github.com:me/demo.git"}""")
		assertEquals(200, addStatus, addBody)
		assertEquals("github.com:me/demo.git", obj(addBody)["remote"])
		val (listStatus, listBody) = call("GET", "/api/repositories")
		assertEquals(200, listStatus)
		assertContains(listBody, key)
		assertEquals(404, call("POST", "/api/devices/dev_nobody/repositories", """{"repo_key":"$key","name":"demo"}""").first)
		assertEquals(400, call("POST", "/api/devices/$device/repositories", """{"repo_key":"C:/work/demo","name":"demo"}""").first)

		val id = create("맡길 작업")
		assertEquals(200, move(id, "plan_review").first) // a one-person organisation passes through to ready
		val ready = detail(id)
		assertEquals(true, (ready["delegation"] as Map<*, *>)["allowed"])
		val (status, body) = call("POST", "/api/work-items/$id/delegations",
			"""{"executor":"claude_code","device_id":"$device","repo_key":"$key","allowed_tools":["Read","Edit","Write"],"version":${version(id)}}""")
		assertEquals(201, status, body)
		val delegated = obj(body)
		assertEquals("manager", item(delegated)["executor_kind"])
		assertEquals(false, (delegated["delegation"] as Map<*, *>)["allowed"])

		val (runsStatus, runsBody) = call("GET", "/api/work-items/$id/runs")
		assertEquals(200, runsStatus)
		val run = json.readValue(runsBody, List::class.java).single() as Map<*, *>
		assertEquals("requested", run["state"])
		assertEquals(key, (run["delegation"] as Map<*, *>)["repo_key"])
		val poll = call("GET", "/api/runs?state=requested&device_id=$device")
		assertContains(poll.second, run["id"] as String)

		val (refused, why) = call("POST", "/api/work-items/$id/delegations",
			"""{"executor":"claude_code","device_id":"$device","repo_key":"$key","version":${version(id)}}""")
		assertEquals(409, refused)
		assertContains(obj(why)["message"] as String, "살아 있는 실행 시도")

		// Before a connector claims it, the run has no lease to renew and takes no instruction.
		assertEquals(409, call("POST", "/api/runs/${run["id"]}/lease", """{"device_id":"$device","generation":1}""").first)
		val (inputStatus, inputBody) = call("POST", "/api/runs/${run["id"]}/inputs", """{"text":"README도"}""")
		assertEquals(409, inputStatus)
		assertContains(obj(inputBody)["message"] as String, "아직 시작되지 않았다")
		val actions = run["actions"] as Map<*, *>
		assertEquals(true, (actions["cancel"] as Map<*, *>)["allowed"])

		val (backStatus, backBody) = call("POST", "/api/runs/${run["id"]}/withdraw", """{"version":${version(id)}}""")
		assertEquals(200, backStatus, backBody)
		assertNull(item(obj(backBody))["executor_kind"])
		val (claimStatus, claimBody) = call("POST", "/api/work-items/$id/delegations",
			"""{"executor":"claude_code","device_id":"$device","repo_key":"$key","version":${version(id)}}""")
		assertEquals(201, claimStatus, claimBody)
		val second = (json.readValue(call("GET", "/api/work-items/$id/runs").second, List::class.java).last() as Map<*, *>)["id"] as String
		val (claimed, claimedBody) = call("POST", "/api/runs/$second/claim", """{"device_id":"$device"}""")
		assertEquals(200, claimed, claimedBody)
		val generation = (obj(claimedBody)["generation"] as Number).toLong()
		val (leaseStatus, leaseBody) = call("POST", "/api/runs/$second/lease", """{"device_id":"$device","generation":$generation,"inputs":[]}""")
		assertEquals(200, leaseStatus, leaseBody)
		assertEquals("claimed", obj(leaseBody)["state"])
		assertEquals(null, obj(leaseBody)["stop"])
		val (cancelStatus, cancelBody) = call("POST", "/api/runs/$second/cancel", """{"version":${version(id)}}""")
		assertEquals(200, cancelStatus, cancelBody)
		assertEquals("cancel", obj(call("POST", "/api/runs/$second/lease", """{"device_id":"$device","generation":$generation}""").second)["stop"])
		assertEquals(200, call("DELETE", "/api/devices/$device/repositories?repo_key=$key").first)
	}

	@Test
	fun `only the review stages can be skipped`() {
		val (status, body) = call("PUT", "/api/settings/workflow", """{"skipped_stages":["in_progress"]}""")
		assertEquals(400, status, body)
	}
}
