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
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Decisions written in zm-baton, over real HTTP as the Web screen calls them (FN-0604). */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecisionWriteTests @Autowired constructor(
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

	private fun propose(fields: Map<String, Any?>): Map<*, *> {
		val (status, body) = call("POST", "/api/decisions", json.writeValueAsString(fields))
		assertEquals(201, status, body)
		return obj(body)
	}

	private fun view(detail: Map<*, *>): Map<*, *> = detail["decision"] as Map<*, *>

	private fun id(detail: Map<*, *>): String = view(detail)["id"] as String

	private fun detail(id: String): Map<*, *> {
		val (status, body) = call("GET", "/api/decisions/$id")
		assertEquals(200, status, body)
		return obj(body)
	}

	private fun version(id: String): Long = (view(detail(id))["version"] as Number).toLong()

	private fun verdict(id: String, decision: String, note: String? = null): Pair<Int, String> =
		call("POST", "/api/decisions/$id/approvals",
			json.writeValueAsString(mapOf("decision" to decision, "version" to version(id), "note" to note)))

	private fun actions(detail: Map<*, *>): Map<String, Map<*, *>> =
		(detail["actions"] as List<*>).associate { a -> (a as Map<*, *>)["name"] as String to a }

	private fun accepted(question: String): String {
		val d = propose(mapOf("question" to question, "choice" to "이렇게 한다"))
		val (status, body) = verdict(id(d), "approved")
		assertEquals(200, status, body)
		return id(d)
	}

	/** A copy of a zm-docs decision, as an import leaves it. */
	private fun gitDecision(): String {
		val id = Ids.decision()
		val ref = "DEC-9" + (1..5).joinToString("") { Random.nextInt(0, 10).toString() }
		jdbc.sql(
			"""
			insert into decisions (id, org_id, external_ref, question, status, decided_on, choice, git_ref)
			values (:id, 'org_owner', :ref, 'Git의 결정', 'accepted', '2026-10-12', '정했다',
			        cast('{"repo_key":"git:abc1234","path":"docs/governance/decisions/x.md"}' as jsonb))
			""".trimIndent(),
		).param("id", id).param("ref", ref).update()
		return id
	}

	@Test
	fun `a proposal gets a DCS number, its owner as approver and the actions a proposal allows`() {
		val d = propose(mapOf(
			"question" to "문서 화면을 어디에 둘까",
			"context" to "사이드바에 자리가 있다.",
			"options" to listOf(mapOf("title" to "사이드바"), mapOf("title" to "결정 화면 안", "body" to "탭으로 둔다")),
			"chosen_option" to "A",
			"rationale" to "다른 화면과 같은 문법이다",
			"review_conditions" to "화면이 열 개를 넘으면",
			"roles" to mapOf("contributors" to listOf("Codex", "Codex", " "), "informed" to listOf("PER-001")),
		))
		val v = view(d)
		assertEquals("proposed", v["status"])
		assertEquals("product", v["source_of_truth"])
		assertNull(v["external_ref"])
		assertTrue((v["ref"] as String).matches(Regex("DCS-[0-9]{4,}")), v["ref"].toString())
		assertEquals((v["number"] as Number).toInt(), (v["ref"] as String).removePrefix("DCS-").toInt())
		assertNull(v["decided_on"])
		// The list shows the chosen option when there is no decision text yet.
		assertEquals("사이드바", v["choice_preview"])
		assertEquals(listOf("A", "B"), (d["options"] as List<*>).map { (it as Map<*, *>)["key"] })
		val roles = d["roles"] as Map<*, *>
		assertEquals("usr_owner", roles["driver"])
		assertEquals("usr_owner", roles["approver"])
		assertEquals(listOf("Codex"), roles["contributors"])
		assertEquals("usr_owner", d["approver"])
		assertNull(d["read_only_reason"])
		val a = actions(d)
		assertEquals(listOf(true, true, true, false), listOf("edit", "accept", "reject", "supersede").map { a.getValue(it)["allowed"] })
		assertEquals("decision.proposed", ((d["history"] as List<*>).single() as Map<*, *>)["action"])
		val audit = jdbc.sql("select actor_kind, actor_id from audit_log where target_kind = 'dcs' and target_id = :id")
			.param("id", id(d)).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		assertEquals("person" to "usr_owner", audit)
	}

	@Test
	fun `a proposal is edited with the version it was read at and a stale version is refused`() {
		val d = propose(mapOf("question" to "고칠 결정", "options" to listOf(mapOf("title" to "하나"), mapOf("title" to "둘"))))
		val id = id(d)
		val (status, body) = call("PATCH", "/api/decisions/$id", json.writeValueAsString(mapOf(
			"version" to 0, "question" to "고친 결정",
			// B removed, a new option added: A keeps its key and the new one takes the next free letter.
			"options" to listOf(mapOf("key" to "A", "title" to "하나"), mapOf("title" to "셋")),
			"chosen_option" to "C",
		)))
		assertEquals(200, status, body)
		val changed = obj(body)
		assertEquals("고친 결정", view(changed)["question"])
		assertEquals(1, (view(changed)["version"] as Number).toInt())
		assertEquals(listOf("A", "C"), (changed["options"] as List<*>).map { (it as Map<*, *>)["key"] })
		assertEquals("C", changed["chosen_option"])
		val updated = (changed["history"] as List<*>).first() as Map<*, *>
		assertEquals("decision.updated", updated["action"])
		assertEquals(listOf("question", "options", "chosen_option"), (updated["details"] as Map<*, *>)["fields"])

		val (stale, staleBody) = call("PATCH", "/api/decisions/$id", """{"version":0,"question":"늦은 고침"}""")
		assertEquals(409, stale, staleBody)
		assertContains(staleBody, "changed")
		assertEquals(400, call("PATCH", "/api/decisions/$id", """{"version":1,"status":"accepted"}""").first)
		assertEquals(400, call("PATCH", "/api/decisions/$id", """{"version":1,"question":"고친 결정"}""").first)
		assertEquals(400, call("PATCH", "/api/decisions/$id", """{"version":1,"question":" "}""").first)
		assertEquals(400, call("PATCH", "/api/decisions/$id", """{"version":1,"chosen_option":"Z"}""").first)
		assertEquals(400, call("PATCH", "/api/decisions/$id", """{"version":1,"project_ids":["prj_missing"]}""").first)
		assertEquals(400, call("POST", "/api/decisions", """{"context":"질문이 없다"}""").first)
		assertEquals("고친 결정", view(detail(id))["question"])
	}

	@Test
	fun `accepting records an approval and closes the decision to edits`() {
		val d = propose(mapOf("question" to "채택할 결정", "choice" to "이렇게 한다"))
		val id = id(d)
		val (status, body) = verdict(id, "approved", "좋다")
		assertEquals(200, status, body)
		val done = obj(body)
		assertEquals("accepted", view(done)["status"])
		assertNotNull(view(done)["decided_on"])
		val approval = (done["approvals"] as List<*>).single() as Map<*, *>
		assertEquals("approved", approval["decision"])
		assertEquals("human", approval["approver_kind"])
		assertEquals("usr_owner", approval["approver_id"])
		assertEquals(0, (approval["target_version"] as Number).toInt())
		assertEquals("stage1.owner_approves", approval["policy_ref"])
		assertEquals("좋다", approval["note"])
		val a = actions(done)
		assertEquals(listOf(false, false, false, true), listOf("edit", "accept", "reject", "supersede").map { a.getValue(it)["allowed"] })
		assertContains(a.getValue("edit")["reason"] as String, "대체한다")

		val (edit, editBody) = call("PATCH", "/api/decisions/$id", json.writeValueAsString(mapOf("version" to version(id), "question" to "x")))
		assertEquals(409, edit, editBody)
		assertContains(editBody, "채택한 결정은 고치지 않는다")
		assertEquals(409, verdict(id, "rejected").first)
		assertEquals(400, verdict(id, "revoked").first)
		assertEquals(listOf("decision.accepted", "decision.proposed"), (done["history"] as List<*>).map { (it as Map<*, *>)["action"] })
	}

	@Test
	fun `a proposal with nothing chosen cannot be accepted, and a rejection keeps its reason`() {
		val id = id(propose(mapOf("question" to "아직 고르지 않은 결정")))
		val (empty, emptyBody) = verdict(id, "approved")
		assertEquals(409, empty, emptyBody)
		assertContains(emptyBody, "고른 안이나 결정 내용을 적어야 채택한다")
		assertEquals("proposed", view(detail(id))["status"])

		val (rejected, rejectedBody) = verdict(id, "rejected", "지금은 필요 없다")
		assertEquals(200, rejected, rejectedBody)
		val done = obj(rejectedBody)
		assertEquals("rejected", view(done)["status"])
		val approval = (done["approvals"] as List<*>).single() as Map<*, *>
		assertEquals("rejected" to "지금은 필요 없다", approval["decision"] to approval["note"])
		assertEquals(1L, jdbc.sql("select count(*) from approvals where target_id = :id").param("id", id).query(Long::class.javaObjectType).single())
	}

	@Test
	fun `accepting a decision that names an earlier one replaces it and links both ways`() {
		val old = accepted("먼저 정한 결정")
		val (early, earlyBody) = call("POST", "/api/decisions",
			json.writeValueAsString(mapOf("question" to "제안을 대체", "supersedes_id" to id(propose(mapOf("question" to "제안"))))))
		assertEquals(409, early, earlyBody)
		assertContains(earlyBody, "제안 상태의 결정은 대체하지 않고 고친다")

		val first = id(propose(mapOf("question" to "새로 정한 결정", "choice" to "다르게 한다", "supersedes_id" to old)))
		val second = id(propose(mapOf("question" to "또 다른 대체안", "choice" to "또 다르게", "supersedes_id" to old)))
		assertEquals(old, (view(detail(first))["supersedes"] as Map<*, *>)["id"])
		assertEquals("accepted", view(detail(old))["status"])

		val (status, body) = verdict(first, "approved")
		assertEquals(200, status, body)
		val replaced = detail(old)
		assertEquals("superseded", view(replaced)["status"])
		assertEquals(first, (view(replaced)["superseded_by"] as Map<*, *>)["id"])
		assertEquals(view(obj(body))["ref"], (view(replaced)["superseded_by"] as Map<*, *>)["ref"])
		assertEquals("decision.superseded", ((replaced["history"] as List<*>).first() as Map<*, *>)["action"])
		assertEquals(2, (view(replaced)["version"] as Number).toInt())

		// The other proposal named the same decision; it is already replaced.
		val (late, lateBody) = verdict(second, "approved")
		assertEquals(409, late, lateBody)
		assertContains(lateBody, "이미 다른 결정이 대체했다")
		assertEquals("proposed", view(detail(second))["status"])
	}

	@Test
	fun `a decision copied from Git is refused with the reason`() {
		val git = gitDecision()
		val d = detail(git)
		assertContains(d["read_only_reason"] as String, "정본이 Git 파일(docs/governance/decisions/x.md)")
		assertTrue(actions(d).values.none { it["allowed"] == true })
		val (edit, editBody) = call("PATCH", "/api/decisions/$git", """{"version":0,"question":"고침"}""")
		assertEquals(409, edit, editBody)
		assertContains(editBody, "원본에서 고치고 다시 가져온다")
		assertEquals(409, call("POST", "/api/decisions/$git/approvals", """{"decision":"rejected","version":0}""").first)
		val (replace, replaceBody) = call("POST", "/api/decisions", json.writeValueAsString(mapOf("question" to "Git 결정 대체", "supersedes_id" to git)))
		assertEquals(409, replace, replaceBody)
		assertContains(replaceBody, "정본이 Git 파일")
		assertEquals(0L, jdbc.sql("select count(*) from approvals where target_id = :id").param("id", git).query(Long::class.javaObjectType).single())
	}

	@Test
	fun `the approver is the owner and nobody else in stage 1`() {
		val (status, body) = call("POST", "/api/decisions",
			json.writeValueAsString(mapOf("question" to "다른 승인자", "roles" to mapOf("approver" to "usr_other"))))
		assertEquals(400, status, body)
		assertContains(body, "승인자는")
		assertEquals(201, call("POST", "/api/decisions",
			json.writeValueAsString(mapOf("question" to "같은 승인자", "roles" to mapOf("approver" to "usr_owner", "driver" to "PER-001")))).first)
	}

	@Test
	fun `linked work shows on the decision and on the work item`() {
		val (created, createdBody) = call("POST", "/api/work-items", """{"title":"결정과 이을 작업","responsible_user_id":"usr_owner","executor_kind":"human"}""")
		assertEquals(201, created, createdBody)
		val work = obj(createdBody)["id"] as String
		val d = propose(mapOf("question" to "작업과 이은 결정", "work_item_ids" to listOf(work, work)))
		assertEquals(listOf(work), (d["work_items"] as List<*>).map { (it as Map<*, *>)["id"] })
		val (listed, listedBody) = call("GET", "/api/work-items/$work/decisions")
		assertEquals(200, listed, listedBody)
		assertEquals(listOf(id(d)), (json.readValue(listedBody, List::class.java)).map { (it as Map<*, *>)["id"] })
		assertEquals(404, call("GET", "/api/work-items/wrk_missing/decisions").first)
		assertEquals(400, call("POST", "/api/decisions", """{"question":"없는 작업","work_item_ids":["wrk_missing"]}""").first)

		val (unlinked, unlinkedBody) = call("PATCH", "/api/decisions/${id(d)}", """{"version":0,"work_item_ids":[]}""")
		assertEquals(200, unlinked, unlinkedBody)
		assertEquals("[]", call("GET", "/api/work-items/$work/decisions").second)
	}

	@Test
	fun `the list puts proposals first and finds a decision by its DCS number`() {
		val marker = "needle-" + UUID.randomUUID().toString().take(8)
		val accepted = accepted("$marker 채택")
		val proposal = propose(mapOf("question" to "$marker 제안"))
		fun ids(q: String) = json.readValue(call("GET", "/api/decisions?limit=500&q=$q").second, List::class.java).map { (it as Map<*, *>)["id"] }
		assertEquals(listOf(id(proposal), accepted), ids(marker))
		assertEquals(listOf(id(proposal)), ids(view(proposal)["ref"] as String))
		val all = ids("")
		assertTrue(all.indexOf(id(proposal)) < all.indexOf(accepted))
	}

	@Test
	fun `session tokens are refused for proposing, changing and judging decisions`() {
		val token = "zmbs_not_a_person"
		assertEquals(403, call("POST", "/api/decisions", """{"question":"x"}""", token).first)
		assertEquals(403, call("PATCH", "/api/decisions/dcs_x", """{"version":0}""", token).first)
		assertEquals(403, call("POST", "/api/decisions/dcs_x/approvals", """{"decision":"approved","version":0}""", token).first)
		assertEquals(403, call("GET", "/api/work-items/wrk_x/decisions", token = token).first)
	}
}
