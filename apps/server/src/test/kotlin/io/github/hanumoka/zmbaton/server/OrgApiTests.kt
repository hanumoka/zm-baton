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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The organisation's structure over real HTTP (FN-0101, FN-0104): items, people, roles and links. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrgApiTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
	private val sessions: SessionService,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()

	private fun call(method: String, path: String, body: Any? = null, token: String? = null): Pair<Int, String> {
		val builder = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
		if (token != null) builder.header("Authorization", "Bearer $token")
		val text = when (body) {
			null -> null
			is String -> body
			else -> json.writeValueAsString(body)
		}
		val response = http.send(
			builder.method(method, text?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		return response.statusCode() to response.body()
	}

	private fun ok(method: String, path: String, body: Any? = null, expect: Int = 200): Map<*, *> {
		val (status, text) = call(method, path, body)
		assertEquals(expect, status, text)
		return json.readValue(text, Map::class.java)
	}

	@Suppress("UNCHECKED_CAST")
	private fun list(view: Map<*, *>, key: String) = view[key] as List<Map<String, Any?>>

	private fun unique(prefix: String) = prefix + " " + UUID.randomUUID().toString().take(8)

	@Test
	fun `the one-person organisation is there with its owner holding every role and the owner's manager`() {
		val org = ok("GET", "/api/org")
		assertEquals("org_owner", org["id"])
		assertEquals("usr_owner", org["owner_user_id"])
		assertEquals(true, org["can_manage"])
		val owner = list(org, "people").single { it["id"] == "usr_owner" }
		assertEquals(listOf("requester", "assignee", "reviewer", "approver", "operator", "master_admin"), owner["roles"])
		assertEquals("mgr_owner", owner["manager_id"])
		assertEquals(true, owner["owner"])
		assertEquals(listOf("personal", "restricted", "team", "org", "public"), list(org, "visibilities").map { it["id"] })
	}

	@Test
	fun `adding a person gives them roles and their own manager record, and the owner's roles never change`() {
		val name = unique("팀원")
		val view = ok("POST", "/api/org/people", mapOf("display_name" to name, "roles" to listOf("assignee", "requester")), 201)
		val person = list(view, "people").single { it["display_name"] == name }
		val id = person["id"] as String
		assertTrue(id.startsWith("usr_"))
		assertEquals(listOf("requester", "assignee"), person["roles"], "in the roles' order")
		assertEquals("mgr_" + id.removePrefix("usr_"), person["manager_id"])
		assertEquals(false, view["solo"], "two active people")
		assertEquals(1L, jdbc.sql("select count(*) from managers where user_id = :id").param("id", id).query(Long::class.javaObjectType).single())
		assertTrue(audits("org.person_added", id) > 0)

		val changed = ok("PATCH", "/api/org/people/$id", mapOf("roles" to listOf("reviewer"), "status" to "inactive"))
		assertEquals(listOf("reviewer"), list(changed, "people").single { it["id"] == id }["roles"])
		assertEquals("inactive", list(changed, "people").single { it["id"] == id }["status"])

		val (owner, ownerBody) = call("PATCH", "/api/org/people/usr_owner", mapOf("roles" to listOf("requester")))
		assertEquals(409, owner, ownerBody)
		assertContains(ownerBody, "조직 소유자의 역할과 상태는 바꾸지 않는다")
		assertEquals(400, call("POST", "/api/org/people", mapOf("display_name" to "x", "roles" to listOf("boss"))).first)
		assertEquals(400, call("POST", "/api/org/people", mapOf("display_name" to " ")).first)
		assertEquals(404, call("PATCH", "/api/org/people/usr_nobody", mapOf("display_name" to "x")).first)
	}

	@Test
	fun `teams, products, projects, repositories and environments are items of their own, tied by links`() {
		val team = unique("백엔드")
		val withTeam = ok("POST", "/api/org/teams", mapOf("name" to team), 201)
		val teamId = list(withTeam, "teams").single { it["name"] == team }["id"] as String
		val project = unique("결제")
		val projectId = list(ok("POST", "/api/org/projects", mapOf("name" to project, "kind" to "BE"), 201), "projects")
			.single { it["name"] == project }["id"] as String
		val product = unique("결제 서비스")
		val productId = list(ok("POST", "/api/org/products", mapOf("name" to product), 201), "products").single { it["name"] == product }["id"] as String
		val key = "git:" + UUID.randomUUID().toString().replace("-", "")
		val repoId = list(ok("POST", "/api/org/repositories", mapOf("name" to "pay", "repo_key" to key, "remote" to "https://me:pw@github.com/me/pay.git"), 201), "repositories")
			.single { it["repo_key"] == key }.let {
				assertEquals("https://github.com/me/pay.git", it["remote"], "credentials are dropped")
				it["id"] as String
			}
		val env = unique("운영")
		val envId = list(ok("POST", "/api/org/environments", mapOf("name" to env, "kind" to "production"), 201), "environments")
			.single { it["name"] == env }["id"] as String

		for ((from, to) in listOf(teamId to projectId, productId to projectId, projectId to repoId, projectId to envId)) {
			val linked = ok("POST", "/api/org/links", mapOf("from_id" to from, "to_id" to to))
			assertTrue(list(linked, "links").any { it["from_id"] == from && it["to_id"] == to }, "$from → $to")
		}
		val (bad, badBody) = call("POST", "/api/org/links", mapOf("from_id" to repoId, "to_id" to teamId))
		assertEquals(400, bad, badBody)
		assertContains(badBody, "is not a link")
		assertEquals(409, call("POST", "/api/org/repositories", mapOf("name" to "again", "repo_key" to key)).first, "one repository per key")
		assertEquals(400, call("POST", "/api/org/repositories", mapOf("name" to "abs", "repo_key" to "C:/work/pay")).first)
		assertEquals(400, call("POST", "/api/org/environments", mapOf("name" to "x", "kind" to "moon")).first)

		val members = ok("PUT", "/api/org/teams/$teamId/members", mapOf("user_ids" to listOf("usr_owner")))
		assertEquals(listOf("usr_owner"), list(members, "teams").single { it["id"] == teamId }["members"])
		assertTrue((list(members, "people").single { it["id"] == "usr_owner" }["teams"] as List<*>).contains(teamId))
		assertEquals(400, call("PUT", "/api/org/projects/$projectId/members", mapOf("user_ids" to listOf("usr_stranger"))).first)

		val archived = ok("PATCH", "/api/org/teams/$teamId", mapOf("archived" to true))
		assertNotNull(list(archived, "teams").single { it["id"] == teamId }["archived_at"])
		val unlinked = ok("DELETE", "/api/org/links?from_id=$productId&to_id=" + URLEncoder.encode(projectId, Charsets.UTF_8))
		assertTrue(list(unlinked, "links").none { it["from_id"] == productId })
		assertEquals(404, call("DELETE", "/api/org/links?from_id=$productId&to_id=$projectId").first)
		assertEquals(400, call("PATCH", "/api/org/projects/$projectId", mapOf("archived" to true)).first, "projects are not archived here")
		assertTrue(audits("org.linked", teamId) > 0)
	}

	@Test
	fun `a project imported from Git keeps the name its source gives it`() {
		val id = "prj_" + UUID.randomUUID()
		val ref = "PRJ-" + (100 + (Math.random() * 800).toInt()) + UUID.randomUUID().toString().take(4)
		jdbc.sql("insert into projects (id, org_id, external_ref, name) values (:id, 'org_owner', :ref, 'zm-docs의 이름')")
			.param("id", id).param("ref", ref).update()
		val (status, body) = call("PATCH", "/api/org/projects/$id", mapOf("name" to "다른 이름"))
		assertEquals(409, status, body)
		assertContains(body, "원본에서 고치고 다시 가져온다")
		ok("PATCH", "/api/org/projects/$id", mapOf("kind" to "FE"))
	}

	@Test
	fun `a session token never changes the organisation, and the refusal is audited`() {
		val device = sessions.registerDevice(RegisterDevice("org-pc")).deviceId
		val token = sessions.register(RegisterSession(2, device, "claude_code", null, "org-" + UUID.randomUUID())).token
		val (status, body) = call("POST", "/api/org/teams", mapOf("name" to "AI가 만든 팀"), token)
		assertEquals(403, status, body)
		assertTrue(audits("person_action.refused", "add to the organisation") > 0)
		assertEquals(403, call("GET", "/api/org", token = token).first)
		assertEquals(403, call("GET", "/api/me/record", token = token).first)
	}

	private fun audits(action: String, target: String): Long =
		jdbc.sql("select count(*) from audit_log where action = :a and target_id = :t").param("a", action).param("t", target)
			.query(Long::class.javaObjectType).single()
}
