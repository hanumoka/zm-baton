package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Document contradiction candidates on the local path (contract v1 "문서 모순 후보", MasterChecks.kt),
 * over real HTTP: what starts a check, what it compares, the owner's run the connector claims, how its
 * answer is checked and becomes a notice, and how a person judges it. The API path is off here: its
 * key variable is a name nothing sets. The sweep runs only when a test calls it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = [
		"zm-baton.master.sweep-ms=3600000", "zm-baton.master.daily-limit=50",
		"zm-baton.master.api.key-env=ZM_BATON_TEST_KEY_NOBODY_SETS",
	],
)
class MasterCheckTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
	private val checks: MasterCheckService,
	private val material: ContradictionMaterial,
	private val settings: MasterSettings,
	transactions: PlatformTransactionManager,
	private val org: OrgService,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()
	private val tx = TransactionTemplate(transactions)

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

	private fun ok(method: String, path: String, body: Any? = null): Map<*, *> {
		val (status, text) = call(method, path, body)
		assertTrue(status in 200..299, "$method $path: $status $text")
		return json.readValue(text, Map::class.java)
	}

	private fun list(path: String): List<Map<*, *>> {
		val (status, text) = call("GET", path)
		assertEquals(200, status, text)
		return (json.readValue(text, List::class.java) as List<*>).map { it as Map<*, *> }
	}

	private fun project(): String {
		val id = "prj_" + UUID.randomUUID().toString().replace("-", "")
		jdbc.sql("insert into projects (id, org_id, name) values (:id, 'org_owner', :name)").param("id", id).param("name", "master $id").update()
		return id
	}

	private fun device(): String = ok("POST", "/api/devices", mapOf("name" to "master-pc"))["device_id"] as String

	/** A product document with one version, approved; returns the document id. */
	private fun approvedDocument(title: String, body: String, project: String?, decisions: List<String> = emptyList()): String {
		val d = ok("POST", "/api/documents", mapOf("title" to title, "source_of_truth" to "product", "project_id" to project,
			"body_markdown" to body, "decision_ids" to decisions))
		val id = (d["document"] as Map<*, *>)["id"] as String
		ok("POST", "/api/documents/$id/versions/1/approvals", mapOf("decision" to "approved"))
		return id
	}

	private fun versionId(doc: String, number: Int = 1): String =
		jdbc.sql("select id from document_versions where doc_id = :d and number = :n").param("d", doc).param("n", number).query(String::class.java).single()

	private fun checkFor(subjectId: String): Map<String, Any?> =
		jdbc.sql(
			"""
			select id, state, path, trigger, reason, work_item_id, run_id, api_run_id, outcome::text from master_checks
			 where subjects @> cast(:s as jsonb) order by created_at desc, id desc limit 1
			""".trimIndent(),
		).param("s", json.writeValueAsString(listOf(mapOf("id" to subjectId)))).query { rs, _ ->
			mapOf("id" to rs.getString(1), "state" to rs.getString(2), "path" to rs.getString(3), "trigger" to rs.getString(4),
				"reason" to rs.getString(5), "work_item_id" to rs.getString(6), "run_id" to rs.getString(7), "api_run_id" to rs.getString(8),
				"outcome" to rs.getString(9)?.let { json.readValue(it, Map::class.java) })
		}.single()

	private fun claim(run: String, device: String): Map<*, *> = ok("POST", "/api/runs/$run/claim", mapOf("device_id" to device))

	private fun report(run: String, generation: Long, vararg events: Pair<String, Map<String, Any?>>) {
		val batch = mapOf("protocol_version" to 2, "events" to events.mapIndexed { i, (kind, payload) ->
			mapOf("event_id" to "evt_" + UUID.randomUUID(), "generation" to generation, "seq" to i + 1, "kind" to kind, "payload" to payload)
		})
		val result = ok("POST", "/api/runs/$run/events", batch)
		assertEquals(0, result["rejected"], "rejected events: $result")
	}

	/** The device the server handed the run to: the one that claimed a run last, else the one seen last. */
	private fun deviceOf(run: String): String =
		jdbc.sql("select d.device_id from runs r join delegations d on d.id = r.delegation_id where r.id = :id").param("id", run)
			.query(String::class.java).single()

	/** What the connector reports for a master run that answered [answer]. */
	private fun answer(run: String, answer: String?) {
		val c = claim(run, deviceOf(run))
		val generation = (c["generation"] as Number).toLong()
		val end = mutableMapOf<String, Any?>("phase" to "ended", "end_reason" to "submitted", "executor_started" to true, "result_seen" to true)
		if (answer != null) end["result"] = mapOf("text" to answer, "truncated" to false)
		report(run, generation, "lifecycle" to mapOf("phase" to "started", "session_id" to UUID.randomUUID().toString()), "lifecycle" to end)
	}

	private val first = "# 실행 안내\n\n마스터는 서버에서 API 키로 돈다.\n크레딧이 바닥나면 소유자 PC의 연결 프로그램이 돈다."
	private val second = "# 마스터 운영\n\n마스터는 개인 구독으로 서버에서 돈다.\n크레딧 이야기는 없다."

	@Test
	fun `the master's work is the organisation owner's through the owner's manager, and a notice goes to the records' owners`() {
		device()
		val p = project()
		val a = approvedDocument("소유자 문서", first, p)
		val other = org.addPerson("usr_owner", PersonIn("문서 주인", listOf("requester", "assignee")))
		val d = ok("POST", "/api/documents", mapOf("title" to "다른 사람 문서", "source_of_truth" to "product", "project_id" to p, "body_markdown" to second))
		val b = (d["document"] as Map<*, *>)["id"] as String
		jdbc.sql("update documents set owner_user_id = :o where id = :id").param("o", other).param("id", b).update()
		ok("POST", "/api/documents/$b/versions/1/approvals", mapOf("decision" to "approved"))
		val check = checkFor(versionId(b))
		val work = jdbc.sql("select org_id, responsible_user_id, executor_kind, executor_id from work_items where id = :id")
			.param("id", check["work_item_id"]).query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.single()
		assertEquals(listOf("org_owner", "usr_owner", "manager", "mgr_owner"), work)
		val delegation = jdbc.sql("select granted_by_user_id, grantee_id from delegations where work_item_id = :id").param("id", check["work_item_id"])
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		assertEquals("usr_owner" to "mgr_owner", delegation)
		assertEquals("org_owner", jdbc.sql("select org_id from master_checks where id = :id").param("id", check["id"]).query(String::class.java).single())

		answer(check["run_id"] as String, """
			{"candidates":[{"left":"S1","left_quote":"마스터는 개인 구독으로 서버에서 돈다.","right":"C1","right_quote":"마스터는 서버에서 API 키로 돈다.","reason":"다르다"}]}
		""".trimIndent())
		checks.sweep()
		val notice = ((checkFor(versionId(b))["outcome"] as Map<*, *>)["notice_ids"] as List<*>).single() as String
		val recipients = jdbc.sql("select array_to_string(recipients, ',') from notices where id = :id").param("id", notice).query(String::class.java).single()
		assertEquals(setOf(other, "usr_owner"), recipients.split(',').toSet(), "the owners of both documents")
		assertEquals(setOf(other, "usr_owner"), jdbc.sql("select user_id from inbox_items where source_id = :id").param("id", notice).query(String::class.java).list().toSet(),
			"both record owners receive an inbox item")
		assertTrue(a.isNotEmpty())
	}

	@Test
	fun `an approved version becomes the owner's master run, and its checked answer a notice a person judges`() {
		device()
		val p = project()
		val a = approvedDocument("실행 안내", first, p)
		// Nothing else in the project yet: nothing to compare, so the check is skipped and counts for nothing.
		val skipped = checkFor(versionId(a))
		assertEquals("skipped", skipped["state"])
		assertContains(skipped["reason"] as String, "비교할 문서")
		assertNull(skipped["work_item_id"])

		val b = approvedDocument("마스터 운영", second, p)
		val check = checkFor(versionId(b))
		assertEquals("requested" to "local", check["state"] to check["path"])
		assertEquals("document_version_approved", check["trigger"])
		val run = check["run_id"] as String

		// The connector sees a run with no repository, meant for the master.
		val dev = deviceOf(run)
		val pending = list("/api/runs?device_id=$dev").single { it["id"] == run }
		assertEquals("master_check", pending["purpose"])
		assertNull(pending["repo_key"])
		val c = claim(run, dev)
		assertEquals("master_check", c["purpose"])
		val delegation = c["delegation"] as Map<*, *>
		assertEquals(listOf("Read", "Glob", "Grep"), delegation["allowed_tools"])
		assertNull(delegation["repo_key"])
		val limits = c["limits"] as Map<*, *>
		assertEquals(5, limits["max_turns"])
		assertEquals(10, limits["max_minutes"])
		assertEquals(2.0, (limits["max_budget_usd"] as Number).toDouble())
		val instruction = c["instruction"] as String
		assertContains(instruction, "S1 시작")
		assertContains(instruction, "마스터는 개인 구독으로 서버에서 돈다.")
		assertContains(instruction, "C1 시작")
		assertContains(instruction, "마스터는 서버에서 API 키로 돈다.")
		val generation = (c["generation"] as Number).toLong()
		val answerText = """
			{"candidates":[
			 {"left":"S1","left_quote":"마스터는 개인 구독으로 서버에서 돈다.","right":"C1","right_quote":"마스터는 서버에서 API 키로 돈다.","reason":"마스터의 인증 방식이 다르다"},
			 {"left":"S1","left_quote":"마스터는 로컬에서만 돈다.","right":"C1","right_quote":"마스터는 서버에서 API 키로 돈다.","reason":"지어낸 문장"}]}
		""".trimIndent()
		report(run, generation,
			"lifecycle" to mapOf("phase" to "started", "session_id" to UUID.randomUUID().toString()),
			"lifecycle" to mapOf("phase" to "ended", "end_reason" to "submitted", "executor_started" to true, "result_seen" to true,
				"result" to mapOf("text" to answerText, "truncated" to false)))
		checks.sweep()

		val done = checkFor(versionId(b))
		assertEquals("done", done["state"])
		val outcome = done["outcome"] as Map<*, *>
		assertEquals(2 to 1, outcome["proposed"] to outcome["kept"])
		assertEquals("quote_not_found", ((outcome["dropped"] as List<*>).single() as Map<*, *>)["reason"])
		val work = ok("GET", "/api/work-items/${done["work_item_id"]}")
		assertEquals("done", (work["item"] as Map<*, *>)["stage"], "a one-person organisation skips the result review")

		// The first screen's bucket 5, alerted with the digest, with both sentences and both items.
		val noticeId = (outcome["notice_ids"] as List<*>).single() as String
		val item = list("/api/inbox").single { it["source_id"] == noticeId }
		assertEquals(5 to "digest", item["bucket"] to item["notify"])
		val detail = item["detail"] as Map<*, *>
		assertEquals("doc_contradiction", detail["notice_kind"])
		val sides = (detail["sides"] as List<*>).map { it as Map<*, *> }
		assertEquals(listOf(b, a), sides.map { it["id"] })
		assertEquals("마스터는 개인 구독으로 서버에서 돈다.", sides[0]["quote"])
		assertEquals(versionId(a), sides[1]["version_id"])

		val about = ok("GET", "/api/contradictions?about=$a")
		val open = (about["open"] as List<*>).single() as Map<*, *>
		assertEquals(noticeId to "open", open["id"] to open["status"])
		assertEquals(item["id"], open["inbox_item_id"])
		assertEquals("skipped", ((about["checks"] as List<*>).first() as Map<*, *>)["state"])
		assertEquals(1, ((ok("GET", "/api/contradictions?about=$b")["open"]) as List<*>).size)

		// "Not a contradiction" needs a reason, takes it off the first screen and out of the open list.
		assertEquals(400, call("POST", "/api/notices/$noticeId/status", mapOf("status" to "not_a_conflict")).first)
		ok("POST", "/api/notices/$noticeId/status", mapOf("status" to "not_a_conflict", "note" to "구독 문장은 옛 판이다"))
		assertTrue(list("/api/inbox").none { it["source_id"] == noticeId })
		val after = ok("GET", "/api/contradictions?about=$a")
		assertEquals(0, (after["open"] as List<*>).size)
		assertEquals(1, after["closed_count"])

		// The same evidence found again is a repeat of the same notice and stays judged.
		val v2 = ok("POST", "/api/documents/$b/versions", mapOf("based_on" to 1, "body_markdown" to "$second\n덧붙인 줄."))
		assertNotNull(v2)
		ok("POST", "/api/documents/$b/versions/2/approvals", mapOf("decision" to "approved"))
		answer(checkFor(versionId(b, 2))["run_id"] as String, answerText)
		checks.sweep()
		val repeated = jdbc.sql("select status, repeat_count from notices where id = :id").param("id", noticeId)
			.query { rs, _ -> rs.getString(1) to rs.getInt(2) }.single()
		assertEquals("not_a_conflict" to 1, repeated)
		assertTrue(list("/api/inbox").none { it["source_id"] == noticeId })

		val audit = list("/api/audit?target_kind=mck&target_id=${done["id"]}").map { it["action"] }
		assertEquals(listOf("master_check.done", "master_check.requested"), audit)
	}

	@Test
	fun `a resolved candidate that comes back opens again on the first screen`() {
		device()
		val p = project()
		val a = approvedDocument("보존 안내", "# 보존\n\n감사 기록은 영원히 둔다.", p)
		val b = approvedDocument("보존 정책", "# 정책\n\n감사 기록은 감사 보존 30일 뒤 지운다.", p)
		val text = """{"candidates":[{"left":"S1","left_quote":"감사 기록은 감사 보존 30일 뒤 지운다.","right":"C1","right_quote":"감사 기록은 영원히 둔다.","reason":"보존 기간"}]}"""
		answer(checkFor(versionId(b))["run_id"] as String, text)
		checks.sweep()
		val notice = ((checkFor(versionId(b))["outcome"] as Map<*, *>)["notice_ids"] as List<*>).single() as String
		ok("POST", "/api/notices/$notice/status", mapOf("status" to "resolved"))
		ok("POST", "/api/documents/$b/versions", mapOf("based_on" to 1, "body_markdown" to "# 정책\n\n감사 기록은 감사 보존 30일 뒤 지운다.\n다시 쓴 판."))
		ok("POST", "/api/documents/$b/versions/2/approvals", mapOf("decision" to "approved"))
		answer(checkFor(versionId(b, 2))["run_id"] as String, text)
		checks.sweep()
		assertEquals("open", jdbc.sql("select status from notices where id = :id").param("id", notice).query(String::class.java).single())
		assertTrue(list("/api/inbox").any { it["source_id"] == notice })
		val open = ok("GET", "/api/contradictions?about=$a")["open"] as List<*>
		assertEquals(listOf(notice), open.map { (it as Map<*, *>)["id"] })
	}

	@Test
	fun `accepting a decision and one that replaces another start checks, and rejecting one does not`() {
		device()
		val p = project()
		approvedDocument("배포 안내", "# 배포\n\n배포는 금요일에 한다.", p)
		val d1 = ok("POST", "/api/decisions", mapOf("question" to "배포는 언제 하나", "choice" to "배포는 화요일에 한다", "project_ids" to listOf(p)))
		val id1 = (d1["decision"] as Map<*, *>)["id"] as String
		ok("POST", "/api/decisions/$id1/approvals", mapOf("decision" to "approved", "version" to 0))
		assertEquals("decision_accepted", checkFor(id1)["trigger"])
		assertEquals("requested", checkFor(id1)["state"])

		val d2 = ok("POST", "/api/decisions", mapOf("question" to "배포 요일을 바꾼다", "choice" to "배포는 수요일에 한다", "project_ids" to listOf(p),
			"supersedes_id" to id1))
		val id2 = (d2["decision"] as Map<*, *>)["id"] as String
		ok("POST", "/api/decisions/$id2/approvals", mapOf("decision" to "approved", "version" to 0))
		val replaced = checkFor(id2)
		assertEquals("decision_superseded", replaced["trigger"])
		// The decision it replaced no longer holds: it is not compared.
		val compared = jdbc.sql("select material::text from master_checks where id = :id").param("id", replaced["id"]).query(String::class.java).single()
		assertTrue(id1 !in compared, compared)

		val d3 = ok("POST", "/api/decisions", mapOf("question" to "배포를 멈추나", "choice" to "배포는 하지 않는다", "project_ids" to listOf(p)))
		val id3 = (d3["decision"] as Map<*, *>)["id"] as String
		ok("POST", "/api/decisions/$id3/approvals", mapOf("decision" to "rejected", "version" to 0))
		assertEquals(0, jdbc.sql("select count(*) from master_checks where subjects @> cast(:s as jsonb)")
			.param("s", json.writeValueAsString(listOf(mapOf("id" to id3)))).query(Int::class.javaObjectType).single())
	}

	@Test
	fun `a zm-docs import makes one check for a few changed accepted decisions, none for many, none for a dry run`() {
		device()
		val repo = "git:" + UUID.randomUUID().toString().replace("-", "")
		val prj = "PRJ-" + (100..999).random()
		fun dec(n: Int, status: String, choice: String) = mapOf(
			"path" to "docs/governance/decisions/DEC-$n-x.md",
			"content" to "---\nid: DEC-$n\ntitle: 결정 $n\nstatus: $status\ndate: 2026-10-14\nprojects: [$prj]\n---\n\n## 결정\n\n$choice\n",
		)
		val projects = mapOf("path" to "registry/projects.json", "content" to """{"projects":[{"id":"$prj","name":"가져온 프로젝트"}]}""")
		val base = (1..4).map { 90000 + (it * 100) + (0..99).random() }
		fun import(apply: Boolean, files: List<Map<String, Any?>>) = ok("POST", "/api/imports/zm-docs", mapOf("repo_key" to repo, "apply" to apply, "files" to files))
		val before = jdbc.sql("select count(*) from master_checks where trigger = 'zm_docs_import'").query(Int::class.javaObjectType).single()

		import(false, listOf(projects, dec(base[0], "accepted", "배포는 화요일에 한다")))
		assertEquals(before, jdbc.sql("select count(*) from master_checks where trigger = 'zm_docs_import'").query(Int::class.javaObjectType).single())

		import(true, listOf(projects, dec(base[0], "accepted", "배포는 화요일에 한다"), dec(base[1], "accepted", "배포는 수요일에 한다"),
			dec(base[2], "proposed", "아직 정하지 않았다")))
		val bundled = jdbc.sql("select subjects::text, state from master_checks where trigger = 'zm_docs_import' order by created_at desc limit 1")
			.query { rs, _ -> json.readValue(rs.getString(1), List::class.java).size to rs.getString(2) }.single()
		assertEquals(2, bundled.first, "the two accepted decisions, not the proposed one, in one check")

		// The same files again change nothing, so no check.
		import(true, listOf(projects, dec(base[0], "accepted", "배포는 화요일에 한다"), dec(base[1], "accepted", "배포는 수요일에 한다")))
		assertEquals(before + 1, jdbc.sql("select count(*) from master_checks where trigger = 'zm_docs_import'").query(Int::class.javaObjectType).single())

		val many = (1..(settings.importLimit + 1)).map { dec(base[3] + it * 7, "accepted", "많은 결정 $it") }
		import(true, listOf(projects) + many)
		val skipped = jdbc.sql("select state, reason from master_checks where trigger = 'zm_docs_import' order by created_at desc limit 1")
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
		assertEquals("skipped", skipped.first)
		assertContains(skipped.second, "${settings.importLimit}건을 넘어")
	}

	@Test
	fun `today's limit skips the rest of the day, and skipped checks do not count`() {
		device()
		val p = project()
		approvedDocument("한도 비교 대상", "# 대상\n\n하루 상한 문장이 여기 있다.", p)
		val used = checks.usedToday()
		val left = settings.dailyLimit - used
		assertTrue(left >= 1, "the class limit leaves room: used $used of ${settings.dailyLimit}")
		val subjects = (1..left + 1).map { i ->
			val d = ok("POST", "/api/documents", mapOf("title" to "한도 $i", "source_of_truth" to "product", "project_id" to p,
				"body_markdown" to "# 한도\n\n하루 상한 문장 $i 이다."))
			versionId((d["document"] as Map<*, *>)["id"] as String)
		}
		val made = mutableListOf<String>()
		try {
			subjects.forEach { v -> made += tx.execute { checks.request(ContradictionTrigger("document_version_approved", listOf(CheckSubject("document_version", v)))) }!! }
			limitReached(made)
		} finally {
			// The other tests of this class need today's room back; the checks' runs and work items stay.
			jdbc.sql("delete from master_checks where id in (:ids)").param("ids", made).update()
		}
	}

	private fun limitReached(made: MutableList<String>) {
		val states = made.map { id -> jdbc.sql("select state, reason from master_checks where id = :id").param("id", id).query { rs, _ -> rs.getString(1) to rs.getString(2) }.single() }
		assertTrue(states.dropLast(1).all { it.first == "requested" }, states.toString())
		assertEquals("skipped", states.last().first)
		assertContains(states.last().second, "다 썼다")
		assertEquals(settings.dailyLimit, checks.usedToday())
		// A Git document's version has no body here: skipped too, and still not counted.
		val git = ok("POST", "/api/documents", mapOf("title" to "Git 문서", "source_of_truth" to "git",
			"git" to mapOf("repo" to "github.com/hanumoka/zm-docs", "path" to "docs/x.md", "commit" to "0123456789abcdef0123456789abcdef01234567")))
		val gitVersion = versionId((git["document"] as Map<*, *>)["id"] as String)
		val g = tx.execute { checks.request(ContradictionTrigger("document_version_approved", listOf(CheckSubject("document_version", gitVersion)))) }!!
		made += g
		assertContains(jdbc.sql("select reason from master_checks where id = :id").param("id", g).query(String::class.java).single(), "본문이 이 서버에 없어")
		assertEquals(settings.dailyLimit, checks.usedToday())
	}

	@Test
	fun `a master run that ends without an answer fails its check and closes its work item`() {
		device()
		val p = project()
		approvedDocument("취소 대상 A", "# A\n\n취소 문장 하나다.", p)
		val cancelled = approvedDocument("취소 대상 B", "# B\n\n취소 문장 둘이다.", p)
		val c1 = checkFor(versionId(cancelled))
		ok("POST", "/api/runs/${c1["run_id"]}/withdraw", mapOf("version" to
			(ok("GET", "/api/work-items/${c1["work_item_id"]}")["item"] as Map<*, *>)["version"]))
		checks.sweep()
		val failed = checkFor(versionId(cancelled))
		assertEquals("failed", failed["state"])
		assertContains(failed["reason"] as String, "취소")
		assertEquals("cancelled", (ok("GET", "/api/work-items/${failed["work_item_id"]}")["item"] as Map<*, *>)["stage"])

		val garbled = approvedDocument("취소 대상 C", "# C\n\n취소 문장 셋이다.", p)
		answer(checkFor(versionId(garbled))["run_id"] as String, "모순이 없어 보입니다.")
		checks.sweep()
		assertEquals("failed", checkFor(versionId(garbled))["state"])
		assertContains(checkFor(versionId(garbled))["reason"] as String, "JSON")

		val old = approvedDocument("취소 대상 D", "# D\n\n취소 문장 넷이다.", p)
		answer(checkFor(versionId(old))["run_id"] as String, null)
		checks.sweep()
		assertContains(checkFor(versionId(old))["reason"] as String, "답이 없다")
	}

	@Test
	fun `what is compared is the same project's approved documents and accepted decisions that share words, linked ones first`() {
		val p = project()
		val elsewhere = project()
		val near = approvedDocument("배포 요일", "# 배포\n\n배포 요일은 화요일이다. 배포 창은 오후다.", p)
		approvedDocument("점심 메뉴", "# 점심\n\n김밥과 라면.", p)
		val draft = ok("POST", "/api/documents", mapOf("title" to "배포 초안", "source_of_truth" to "product", "project_id" to p,
			"body_markdown" to "배포 요일은 월요일이다."))
		val otherProject = approvedDocument("배포 다른 곳", "# 배포\n\n배포 요일은 목요일이다.", elsewhere)
		val linked = ok("POST", "/api/decisions", mapOf("question" to "회의록 양식", "choice" to "양식은 하나다", "project_ids" to listOf(elsewhere)))
		val linkedId = (linked["decision"] as Map<*, *>)["id"] as String
		ok("POST", "/api/decisions/$linkedId/approvals", mapOf("decision" to "approved", "version" to 0))
		val subjectDoc = approvedDocument("배포 정책", "# 정책\n\n배포 요일은 수요일이다. 배포 창은 저녁이다.", p, listOf(linkedId))
		val subject = material.subject("document_version", versionId(subjectDoc))!!
		val compared = material.compare(listOf(subject), 5, 6000)
		assertEquals(listOf(linkedId, near), compared.map { it.id }, compared.map { it.label }.toString())
		assertEquals(listOf(true, false), compared.map { it.linked })
		assertEquals(listOf("C1", "C2"), compared.map { it.key })
		assertTrue(compared.none { it.id == otherProject || it.id == (draft["document"] as Map<*, *>)["id"] })
		assertEquals(1, compared[1].number)
	}

	@Test
	fun `the API path is off by default and the reads refuse a session token`() {
		val s = ok("GET", "/api/settings/master")
		assertEquals(false, s["api_path"])
		assertEquals("ZM_BATON_TEST_KEY_NOBODY_SETS", s["api_key_env"])
		assertEquals(50, s["daily_limit"])
		assertNull(EnvironmentMasterApiKey().value("ZM_BATON_TEST_KEY_NOBODY_SETS"))
		assertNull(EnvironmentMasterApiKey().value("not a name; echo"), "only a variable name is read")
		val device = device()
		val session = ok("POST", "/api/sessions", mapOf("protocol_version" to 2, "device_id" to device, "executor" to "claude_code",
			"executor_session_id" to UUID.randomUUID().toString()))
		val token = session["token"] as String
		assertEquals(403, call("GET", "/api/contradictions?about=doc_x", token = token).first)
		assertEquals(403, call("GET", "/api/settings/master", token = token).first)
		assertEquals(400, call("GET", "/api/contradictions?about=wrk_x").first)
	}
}
