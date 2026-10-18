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
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every request that changes the server's state leaves an audit row, and the audit API reads
 * them back (FN-1404). Over real HTTP, as the Web screen and the CLI call the server.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditApiTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
	private val sessions: SessionService,
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

	private fun list(body: String): List<Map<*, *>> = json.readValue(body, List::class.java).map { it as Map<*, *> }

	private fun q(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

	/** The audit rows of one target, newest first, through the API. */
	private fun auditOf(kind: String, id: String, extra: String = ""): List<Map<*, *>> {
		val (status, body) = call("GET", "/api/audit?target_kind=$kind&target_id=${q(id)}$extra")
		assertEquals(200, status, body)
		return list(body)
	}

	private fun actions(rows: List<Map<*, *>>) = rows.map { it["action"] as String }

	private fun repoKey() = "git:" + UUID.randomUUID().toString().replace("-", "")

	private fun device(): String = obj(call("POST", "/api/devices", """{"name":"audit-pc","os":"windows"}""").second)["device_id"] as String

	private fun register(device: String, repo: String): Pair<String, String> {
		val (status, body) = call(
			"POST", "/api/sessions",
			"""{"protocol_version":2,"device_id":"$device","executor":"claude_code","executor_session_id":"${UUID.randomUUID()}",
			   "repository":{"repo_key":"$repo","prefix":"","branch":"main"}}""",
		)
		assertEquals(201, status, body)
		return obj(body)["session_id"] as String to obj(body)["token"] as String
	}

	private fun send(session: Pair<String, String>, seq: Int, kind: String, payload: String = "{}") {
		val (status, body) = call(
			"POST", "/api/sessions/${session.first}/events",
			"""{"protocol_version":2,"events":[{"event_id":"evt_${UUID.randomUUID()}","local_seq":$seq,"kind":"$kind","payload":$payload}]}""",
			token = session.second,
		)
		assertEquals(200, status, body)
		assertEquals(0, (obj(body)["rejected"] as Number).toInt(), body)
	}

	@Test
	fun `device and session registration, sharing, conflicts and their judgement, and session ends are audited`() {
		val dev = device()
		// Both events belong to one transaction. Their timestamps (and UUIDv7 millisecond) may tie.
		assertEquals(listOf("device.registered", "device.token_issued"), actions(auditOf("dev", dev)).sorted())
		val repo = repoKey()
		val a = register(dev, repo)
		val b = register(dev, repo)
		assertEquals(listOf("session.registered"), actions(auditOf("ses", a.first)))

		// Turning sharing off and on again is a decision; a summary with the same setting is not.
		send(a, 1, "current_work", """{"summary":"shared work","share":true}""")
		send(a, 2, "current_work", """{"summary":"private work","share":false}""")
		send(a, 3, "current_work", """{"summary":"still private","share":false}""")
		send(a, 4, "current_work", """{"summary":"shared again","share":true}""")
		val sharing = auditOf("ses", a.first, "&action=session.sharing_changed")
		assertEquals(listOf(true, false), sharing.map { (it["details"] as Map<*, *>)["share"] })
		assertTrue(sharing.all { it["actor_kind"] == "session" && it["actor_id"] == a.first })
		assertFalse(sharing.toString().contains("private work"), "summaries stay in session_events")

		// Two sessions touch one file: the second report raises the notice, as that session.
		send(a, 5, "file_touch", """{"repo_key":"$repo","path":"docs/a.md","tool":"Edit"}""")
		send(b, 1, "file_touch", """{"repo_key":"$repo","path":"docs/a.md","tool":"Edit"}""")
		val notice = jdbc.sql("select id from notices where evidence ->> 'repo_key' = :repo").param("repo", repo)
			.query(String::class.java).single()
		val raised = auditOf("ntc", notice).single()
		assertEquals("notice.raised", raised["action"])
		assertEquals("session", raised["actor_kind"])
		assertEquals(b.first, raised["actor_id"])
		assertEquals("docs/a.md", (raised["details"] as Map<*, *>)["path"])

		assertEquals(200, call("POST", "/api/notices/$notice/status", """{"status":"resolved"}""").first)
		// The same files again reopen a resolved conflict: that is audited, a plain repeat is not.
		send(a, 6, "file_touch", """{"repo_key":"$repo","path":"docs/a.md","tool":"Edit"}""")
		send(a, 7, "file_touch", """{"repo_key":"$repo","path":"docs/a.md","tool":"Edit"}""")
		assertEquals(listOf("notice.reopened", "notice.resolved", "notice.raised"), actions(auditOf("ntc", notice)))

		send(b, 2, "ended")
		send(a, 8, "deregistered")
		assertEquals("session.ended", actions(auditOf("ses", b.first)).first())
		assertEquals("session.deregistered", actions(auditOf("ses", a.first)).first())
	}

	@Test
	fun `an expired registration is ended by the server and audited as the system`() {
		val session = register(device(), repoKey())
		jdbc.sql("update agent_sessions set token_expires_at = now() - interval '1 minute' where id = :id")
			.param("id", session.first).update()
		sessions.endExpired()
		val expired = auditOf("ses", session.first).first()
		assertEquals("session.expired", expired["action"])
		assertEquals("system", expired["actor_kind"])
		assertEquals(SessionService.SYSTEM_ACTOR, expired["actor_id"])
	}

	@Test
	fun `work item changes, the workflow setting and a zm-docs import are audited`() {
		val (created, createdBody) = call(
			"POST", "/api/work-items",
			"""{"title":"audited","responsible_user_id":"usr_owner","executor_kind":"human","initial_stage":"draft"}""",
		)
		assertEquals(201, created, createdBody)
		val id = obj(createdBody)["id"] as String
		fun version() = ((obj(call("GET", "/api/work-items/$id").second)["item"] as Map<*, *>)["version"] as Number).toLong()
		assertEquals(200, call("PATCH", "/api/work-items/$id", """{"version":${version()},"next_action":"write the test"}""").first)
		assertEquals(200, call("POST", "/api/work-items/$id/wait-reasons", """{"reason":"waiting_other","version":${version()},"note":"a reply"}""").first)
		assertEquals(200, call("DELETE", "/api/work-items/$id/wait-reasons/waiting_other?version=${version()}").first)
		assertEquals(200, call("POST", "/api/work-items/$id/transitions", """{"to":"cancelled","version":${version()},"note":"test"}""").first)
		assertEquals(
			listOf("work_item.stage_changed", "work_item.wait_removed", "work_item.wait_added", "work_item.updated", "work_item.created"),
			actions(auditOf("wrk", id)),
		)

		val before = obj(call("GET", "/api/settings/workflow").second)["skipped_stages"]
		assertEquals(200, call("PUT", "/api/settings/workflow", json.writeValueAsString(mapOf("skipped_stages" to listOf("plan_review")))).first)
		call("PUT", "/api/settings/workflow", json.writeValueAsString(mapOf("skipped_stages" to before)))
		assertContains(actions(auditOf("org", WorkItemService.ORG, "&limit=2")), "workflow.skipped_stages")

		val repo = repoKey()
		val prj = "PRJ-" + (100000..999999).random()
		val dec = "DEC-" + (100000..999999).random()
		val decision = listOf(
			"---", "id: $dec", "title: audited decision", "status: accepted", "date: 2026-10-14", "projects: [$prj]", "---",
			"", "## 결정", "", "기록한다.",
		).joinToString("\n")
		val body = json.writeValueAsString(
			mapOf(
				"repo_key" to repo, "apply" to true, "commit" to "abcdef1234567",
				"files" to listOf(
					mapOf("path" to "registry/projects.json", "content" to """{"projects":[{"id":"$prj","name":"audit project"}]}"""),
					mapOf("path" to "docs/governance/decisions/$dec-audited.md", "content" to decision),
				),
			),
		)
		assertEquals(200, call("POST", "/api/imports/zm-docs", body).first)
		val projectId = jdbc.sql("select id from projects where external_ref = :ref").param("ref", prj).query(String::class.java).single()
		val decisionId = jdbc.sql("select id from decisions where external_ref = :ref").param("ref", dec).query(String::class.java).single()
		val projectRow = auditOf("prj", projectId).single()
		assertEquals("project.imported", projectRow["action"])
		assertEquals(mapOf("ref" to prj, "repo_key" to repo, "commit" to "abcdef1234567", "fields" to emptyList<String>()), projectRow["details"])
		assertEquals("decision.imported", auditOf("dcs", decisionId).single()["action"])
	}

	@Test
	fun `runs requested, claimed and ended, delegations and device repositories are audited`() {
		// The compat-test path: a person makes a run, a connector claims it, its report ends it.
		val work = obj(call("POST", "/api/work-items", """{"title":"run audit","responsible_user_id":"usr_owner"}""").second)["id"] as String
		val (made, madeBody) = call("POST", "/api/work-items/$work/runs", """{"executor":"claude_code"}""")
		assertEquals(201, made, madeBody)
		val run = obj(madeBody)["id"] as String
		val dev = device()
		val claimed = obj(call("POST", "/api/runs/$run/claim", """{"device_id":"$dev"}""").second)
		val gen = (claimed["generation"] as Number).toLong()
		val (ended, endedBody) = call(
			"POST", "/api/runs/$run/events",
			"""{"protocol_version":1,"events":[{"event_id":"evt_${UUID.randomUUID()}","generation":$gen,"seq":1,
			    "kind":"lifecycle","payload":{"phase":"ended","end_reason":"failed"}}]}""",
		)
		assertEquals(200, ended, endedBody)
		val rows = auditOf("run", run)
		assertEquals(listOf("run.ended", "run.claimed", "run.requested"), actions(rows))
		assertEquals(listOf("system" to run, "system" to dev, "person" to "usr_owner"), rows.map { it["actor_kind"] to it["actor_id"] })
		assertEquals("failed", (rows.first()["details"] as Map<*, *>)["end_reason"])

		// Handing work to an AI: the repository, the delegation, taking it back, removing the repository.
		val repo = repoKey()
		assertEquals(200, call("POST", "/api/devices/$dev/repositories", """{"repo_key":"$repo","name":"demo"}""").first)
		val ready = obj(call("POST", "/api/work-items", """{"title":"delegated","responsible_user_id":"usr_owner"}""").second)["id"] as String
		fun version() = ((obj(call("GET", "/api/work-items/$ready").second)["item"] as Map<*, *>)["version"] as Number).toLong()
		val (delegated, delegatedBody) = call(
			"POST", "/api/work-items/$ready/delegations",
			"""{"executor":"claude_code","device_id":"$dev","repo_key":"$repo","version":${version()}}""",
		)
		assertEquals(201, delegated, delegatedBody)
		val delegatedRun = list(call("GET", "/api/work-items/$ready/runs").second).single()["id"] as String
		assertEquals(200, call("POST", "/api/runs/$delegatedRun/withdraw", """{"version":${version()}}""").first)
		assertEquals(200, call("DELETE", "/api/devices/$dev/repositories?repo_key=${q(repo)}").first)
		assertEquals(listOf("work_item.delegation_withdrawn", "work_item.delegated", "work_item.created"), actions(auditOf("wrk", ready)))
		assertEquals(listOf("device.repository_removed", "device.repository_added", "device.token_issued", "device.registered"), actions(auditOf("dev", dev)))
	}

	@Test
	fun `the audit API filters, pages and refuses session tokens`() {
		val (_, createdBody) = call("POST", "/api/work-items", """{"title":"paged","responsible_user_id":"usr_owner","initial_stage":"draft"}""")
		val id = obj(createdBody)["id"] as String
		repeat(3) { n ->
			val v = ((obj(call("GET", "/api/work-items/$id").second)["item"] as Map<*, *>)["version"] as Number).toLong()
			assertEquals(200, call("PATCH", "/api/work-items/$id", """{"version":$v,"next_action":"step $n"}""").first)
		}
		val all = auditOf("wrk", id)
		assertEquals(4, all.size)
		val first = auditOf("wrk", id, "&limit=2")
		assertEquals(all.take(2).map { it["id"] }, first.map { it["id"] })
		val second = auditOf("wrk", id, "&limit=2&before=${first.last()["id"]}")
		assertEquals(all.drop(2).map { it["id"] }, second.map { it["id"] })
		assertEquals(emptyList(), auditOf("wrk", id, "&before=${q("2000-01-01T00:00:00Z")}"))
		assertEquals(4, auditOf("wrk", id, "&before=${q("2999-01-01T00:00:00+09:00")}").size)
		assertEquals(4, auditOf("wrk", id, "&action=work_item.").size)
		assertEquals(3, auditOf("wrk", id, "&action=work_item.updated&actor_kind=person").size)
		assertEquals(0, auditOf("wrk", id, "&actor_kind=session").size)

		assertEquals(400, call("GET", "/api/audit?actor_kind=robot").first)
		assertEquals(400, call("GET", "/api/audit?before=yesterday").first)
		assertEquals(400, call("GET", "/api/audit?before=aud_missing").first)
		val session = register(device(), repoKey())
		assertEquals(403, call("GET", "/api/audit", token = session.second).first)
	}

	@Test
	fun `audit details never hold a session token or its hash`() {
		val dev = device()
		val session = register(dev, repoKey())
		send(session, 1, "current_work", """{"summary":"x","share":false}""")
		val hash = SessionTokens.hash(session.second)
		val leaked = jdbc.sql("select count(*) from audit_log where details::text like :token or details::text like :hash")
			.param("token", "%" + session.second + "%").param("hash", "%$hash%").query(Long::class.javaObjectType).single()
		assertEquals(0L, leaked)
		val tokenLike = jdbc.sql("select count(*) from audit_log where details::text like '%zmbs\\_%'").query(Long::class.javaObjectType).single()
		assertEquals(0L, tokenLike)
	}
}
