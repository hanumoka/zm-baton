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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Documents with one source of truth each, their versions and approval (FN-0601, FN-0602), over real HTTP. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentApiTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
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
		val request = builder
			.method(method, text?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
			.build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		return response.statusCode() to response.body()
	}

	private fun obj(body: String): Map<*, *> = json.readValue(body, Map::class.java)

	private fun register(fields: Map<String, Any?>): Map<*, *> {
		val (status, body) = call("POST", "/api/documents", fields)
		assertEquals(201, status, body)
		return obj(body)
	}

	private fun doc(detail: Map<*, *>): Map<*, *> = detail["document"] as Map<*, *>

	private fun id(detail: Map<*, *>): String = doc(detail)["id"] as String

	private fun versions(detail: Map<*, *>): List<Map<*, *>> = (detail["versions"] as List<*>).map { it as Map<*, *> }

	private fun detail(id: String, version: Int? = null): Map<*, *> {
		val (status, body) = call("GET", "/api/documents/$id" + (version?.let { "?version=$it" } ?: ""))
		assertEquals(200, status, body)
		return obj(body)
	}

	private fun history(detail: Map<*, *>) = (detail["history"] as List<*>).map { (it as Map<*, *>)["action"] }

	@Test
	fun `a product document keeps its body here and stacks versions as drafts`() {
		val d = register(mapOf("title" to "1단계 문서 화면 안내", "source_of_truth" to "product", "body_markdown" to "# 안내\r\n\n첫 판이다.", "note" to "처음"))
		val id = id(d)
		assertEquals("product", doc(d)["source_of_truth"])
		assertNull(doc(d)["git_ref"])
		assertNull(d["read_only_reason"])
		assertEquals("usr_owner", doc(d)["owner_user_id"])
		val latest = doc(d)["latest"] as Map<*, *>
		assertEquals(1 to "draft", latest["number"] to latest["state"])
		assertNull(doc(d)["approved_number"])
		val shown = d["shown"] as Map<*, *>
		assertEquals("# 안내\n\n첫 판이다.", shown["body_markdown"])
		assertEquals("처음", shown["note"])
		assertEquals(true, (shown["approve"] as Map<*, *>)["allowed"])

		val (added, addedBody) = call("POST", "/api/documents/$id/versions", mapOf("based_on" to 1, "body_markdown" to "# 안내\n\n둘째 판이다.", "note" to "고침"))
		assertEquals(201, added, addedBody)
		val two = obj(addedBody)
		assertEquals(listOf(2, 1), versions(two).map { it["number"] })
		assertTrue(versions(two).all { it["body_markdown"] == null })
		assertEquals("# 안내\n\n둘째 판이다.", (two["shown"] as Map<*, *>)["body_markdown"])
		assertEquals("# 안내\n\n첫 판이다.", (detail(id, 1)["shown"] as Map<*, *>)["body_markdown"])
		assertEquals(listOf("document.version_added", "document.version_added", "document.registered"), history(two))

		val (stale, staleBody) = call("POST", "/api/documents/$id/versions", mapOf("based_on" to 1, "body_markdown" to "늦은 판"))
		assertEquals(409, stale, staleBody)
		assertContains(staleBody, "newest")
		assertEquals(400, call("POST", "/api/documents/$id/versions", mapOf("based_on" to 2, "body_markdown" to "# 안내\n\n둘째 판이다.")).first)
		assertEquals(400, call("POST", "/api/documents/$id/versions", mapOf("based_on" to 2)).first)
		assertEquals(400, call("POST", "/api/documents/$id/versions", mapOf("based_on" to 2, "git_commit_sha" to "abc1234")).first)
		assertEquals(404, call("GET", "/api/documents/$id?version=9").first)
	}

	@Test
	fun `the approver approves one version at a time and the approval is recorded`() {
		val id = id(register(mapOf("title" to "승인할 문서", "source_of_truth" to "product", "body_markdown" to "판 1")))
		call("POST", "/api/documents/$id/versions", mapOf("based_on" to 1, "body_markdown" to "판 2"))
		val (status, body) = call("POST", "/api/documents/$id/versions/1/approvals", mapOf("decision" to "approved", "note" to "1판으로 간다"))
		assertEquals(200, status, body)
		val d = obj(body)
		val v1 = versions(d).first { it["number"] == 1 }
		assertEquals("approved", v1["state"])
		assertEquals("usr_owner", v1["approved_by"])
		assertEquals(false, (v1["approve"] as Map<*, *>)["allowed"])
		assertEquals("이미 승인한 판이다", (v1["approve"] as Map<*, *>)["reason"])
		assertEquals("draft", versions(d).first { it["number"] == 2 }["state"])
		assertEquals(1, doc(d)["approved_number"])
		assertEquals("draft", (doc(d)["latest"] as Map<*, *>)["state"])
		assertEquals(1, (d["shown"] as Map<*, *>)["number"])
		val approval = jdbc.sql(
			"select target_kind, target_version, decision, approver_id, policy_ref, note from approvals where target_id = :v",
		).param("v", v1["id"]).query { rs, _ -> listOf(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) }.single()
		assertEquals(listOf("document_version", 1L, "approved", "usr_owner", "stage1.owner_approves", "1판으로 간다"), approval)
		assertEquals("document.version_approved", history(d).first())

		val (again, againBody) = call("POST", "/api/documents/$id/versions/1/approvals", mapOf("decision" to "approved"))
		assertEquals(409, again, againBody)
		assertContains(againBody, "이미 승인한 판이다")
		assertEquals(400, call("POST", "/api/documents/$id/versions/2/approvals", mapOf("decision" to "rejected")).first)
		assertEquals(404, call("POST", "/api/documents/$id/versions/7/approvals", mapOf("decision" to "approved")).first)
	}

	@Test
	fun `a Git document points at commits and keeps its body in Git`() {
		val d = register(mapOf(
			"title" to "결정 기록 안내", "source_of_truth" to "git",
			"git" to mapOf("repo" to "github.com/hanumoka/zm-docs", "path" to "docs\\governance\\decisions\\README.md", "branch" to "main", "commit" to "ABCDEF1"),
		))
		val id = id(d)
		assertEquals(mapOf("repo" to "github.com/hanumoka/zm-docs", "path" to "docs/governance/decisions/README.md", "branch" to "main"), doc(d)["git_ref"])
		assertContains(d["read_only_reason"] as String, "Git에서 고치고")
		val v1 = d["shown"] as Map<*, *>
		assertEquals("abcdef1", v1["git_commit_sha"])
		assertNull(v1["body_markdown"])

		val (body, bodyText) = call("POST", "/api/documents/$id/versions", mapOf("based_on" to 1, "body_markdown" to "여기서 고친 본문"))
		assertEquals(400, body, bodyText)
		assertContains(bodyText, "Git 파일(docs/governance/decisions/README.md)이 정본이다")
		assertEquals(400, call("POST", "/api/documents/$id/versions", mapOf("based_on" to 1, "git_commit_sha" to "abcdef1")).first)
		assertEquals(400, call("POST", "/api/documents/$id/versions", mapOf("based_on" to 1, "git_commit_sha" to "not-a-commit")).first)
		val (added, addedBody) = call("POST", "/api/documents/$id/versions", mapOf("based_on" to 1, "git_commit_sha" to "1234567890abcdef", "note" to "README 고침"))
		assertEquals(201, added, addedBody)
		assertEquals("1234567890abcdef", ((obj(addedBody))["document"] as Map<*, *>).let { (it["latest"] as Map<*, *>)["git_commit_sha"] })
		assertEquals(200, call("POST", "/api/documents/$id/versions/2/approvals", mapOf("decision" to "approved")).first)

		val bare = register(mapOf("title" to "경로만", "source_of_truth" to "git", "git" to mapOf("repo" to "zm-docs", "path" to "README.md")))
		assertNull(doc(bare)["latest"])
		assertNull(bare["shown"])
		assertEquals(emptyList<Any>(), bare["versions"])
	}

	@Test
	fun `a location that is absolute, carries credentials or does not match the source is refused`() {
		fun git(path: String, repo: String = "zm-docs") =
			call("POST", "/api/documents", mapOf("title" to "x", "source_of_truth" to "git", "git" to mapOf("repo" to repo, "path" to path))).first
		assertEquals(400, git("/etc/passwd"))
		assertEquals(400, git("C:/Users/someone/a.md"))
		assertEquals(400, git("docs/../../a.md"))
		assertEquals(400, git("~/a.md"))
		assertEquals(400, git("a.md", "https://user:secret@github.com/x/y"))
		assertEquals(400, call("POST", "/api/documents", mapOf("title" to "x", "source_of_truth" to "git")).first)
		assertEquals(400, call("POST", "/api/documents", mapOf("title" to "x", "source_of_truth" to "product", "git" to mapOf("repo" to "r", "path" to "a.md"))).first)
		assertEquals(400, call("POST", "/api/documents", mapOf("title" to "x", "source_of_truth" to "notion")).first)
		assertEquals(400, call("POST", "/api/documents", mapOf("title" to " ", "source_of_truth" to "product")).first)
		assertEquals(400, call("POST", "/api/documents", mapOf("title" to "x", "source_of_truth" to "git", "body_markdown" to "본문",
			"git" to mapOf("repo" to "r", "path" to "a.md"))).first)
	}

	@Test
	fun `the document's own fields change with the version seen, and its links show in the detail`() {
		val (made, madeBody) = call("POST", "/api/work-items", """{"title":"문서가 영향을 주는 작업","responsible_user_id":"usr_owner","executor_kind":"human"}""")
		assertEquals(201, made, madeBody)
		val work = obj(madeBody)["id"] as String
		val (dec, decBody) = call("POST", "/api/decisions", mapOf("question" to "문서가 기대는 결정"))
		assertEquals(201, dec, decBody)
		val decision = (obj(decBody)["decision"] as Map<*, *>)["id"] as String
		val d = register(mapOf("title" to "고칠 문서", "source_of_truth" to "product", "work_item_ids" to listOf(work), "decision_ids" to listOf(decision)))
		val id = id(d)
		assertEquals(listOf(work), (d["work_items"] as List<*>).map { (it as Map<*, *>)["id"] })
		val linked = (d["decisions"] as List<*>).single() as Map<*, *>
		assertEquals("proposed", linked["status"])
		assertTrue((linked["ref"] as String).startsWith("DCS-"))
		assertNull(doc(d)["latest"])

		val (changed, changedBody) = call("PATCH", "/api/documents/$id", mapOf("version" to 0, "title" to "고친 문서", "work_item_ids" to emptyList<String>()))
		assertEquals(200, changed, changedBody)
		val after = obj(changedBody)
		assertEquals("고친 문서", doc(after)["title"])
		assertEquals(1, (doc(after)["version"] as Number).toInt())
		assertEquals(emptyList<Any>(), after["work_items"])
		assertEquals(listOf("title", "work_item_ids"), (((after["history"] as List<*>).first() as Map<*, *>)["details"] as Map<*, *>)["fields"])

		val (stale, staleBody) = call("PATCH", "/api/documents/$id", mapOf("version" to 0, "title" to "늦은 고침"))
		assertEquals(409, stale, staleBody)
		assertEquals(400, call("PATCH", "/api/documents/$id", mapOf("version" to 1, "title" to "고친 문서")).first)
		assertEquals(400, call("PATCH", "/api/documents/$id", mapOf("version" to 1, "source_of_truth" to "git")).first)
		assertEquals(400, call("PATCH", "/api/documents/$id", mapOf("version" to 1, "git" to mapOf("repo" to "r", "path" to "a.md"))).first)
		assertEquals(400, call("PATCH", "/api/documents/$id", mapOf("version" to 1, "decision_ids" to listOf("dcs_missing"))).first)
		assertEquals(404, call("PATCH", "/api/documents/doc_missing", mapOf("version" to 0, "title" to "x")).first)
	}

	@Test
	fun `the list filters by source, project and words in the title or path`() {
		val marker = "needle-" + UUID.randomUUID().toString().take(8)
		val product = id(register(mapOf("title" to "$marker 제품", "source_of_truth" to "product")))
		val git = id(register(mapOf("title" to "Git 문서", "source_of_truth" to "git", "git" to mapOf("repo" to "zm-docs", "path" to "docs/$marker.md"))))
		fun ids(query: String) = json.readValue(call("GET", "/api/documents?limit=500&$query").second, List::class.java).map { (it as Map<*, *>)["id"] }
		val q = URLEncoder.encode(marker, Charsets.UTF_8)
		assertEquals(setOf(product, git), ids("q=$q").toSet())
		assertEquals(listOf(git), ids("q=$q&source=git"))
		assertEquals(listOf(product), ids("q=$q&source=product"))
		assertEquals(400, call("GET", "/api/documents?source=notion").first)
		assertFalse(ids("q=$q&project_id=prj_none").contains(product))
	}

	@Test
	fun `session tokens are refused for documents`() {
		val token = "zmbs_not_a_person"
		assertEquals(403, call("GET", "/api/documents", token = token).first)
		assertEquals(403, call("POST", "/api/documents", mapOf("title" to "x", "source_of_truth" to "product"), token).first)
		assertEquals(403, call("PATCH", "/api/documents/doc_x", mapOf("version" to 0), token).first)
		assertEquals(403, call("POST", "/api/documents/doc_x/versions", mapOf("body_markdown" to "x"), token).first)
		assertEquals(403, call("POST", "/api/documents/doc_x/versions/1/approvals", mapOf("decision" to "approved"), token).first)
	}

	@Test
	fun `the version rules say why a version cannot be approved`() {
		assertEquals(DocumentRules.Action(true, null), DocumentRules.approve("draft", "usr_owner", "usr_owner"))
		assertEquals("이미 승인한 판이다", DocumentRules.approve("approved", "usr_owner", "usr_owner").reason)
		assertEquals("승인자(소유자(usr_owner))만 승인한다", DocumentRules.approve("draft", "usr_owner", "usr_other").reason)
		assertEquals("approved", DocumentRules.state("usr_owner"))
		assertEquals("draft", DocumentRules.state(null))
	}
}
