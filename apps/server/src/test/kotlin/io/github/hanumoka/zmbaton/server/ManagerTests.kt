package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.Permissions
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A manager per person (FN-0402, DEC-0099 3): the record, the status card built from it, personal
 * notes no one else reads, sessions tied to the record, and a delegation's executor being the manager
 * of the person who hands the work over.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ManagerTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val jdbc: JdbcClient,
	private val org: OrgService,
	private val facts: PermissionFacts,
	private val permissions: Permissions,
	private val managers: ManagerService,
	private val cards: StatusCards,
	private val sessions: SessionService,
	private val workItems: WorkItemService,
	private val delegations: DelegationService,
	private val accounts: AccountService,
	private val export: ExportService,
	transactions: PlatformTransactionManager,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")
	private val http = HttpClient.newHttpClient()
	private val tx = TransactionTemplate(transactions)

	private fun call(method: String, path: String, body: Any? = null): Pair<Int, String> {
		val text = body?.let { json.writeValueAsString(it) }
		val response = http.send(
			HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
				.method(method, text?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString(),
		)
		return response.statusCode() to response.body()
	}

	private fun obj(text: String): Map<*, *> = json.readValue(text, Map::class.java)

	private fun rolledBack(block: () -> Unit) {
		tx.execute { status ->
			try {
				block()
			} finally {
				status.setRollbackOnly()
			}
		}
	}

	@Test
	fun `my record shows who I am, my card, my work and my sessions`() {
		val title = "매니저 기록의 작업 " + UUID.randomUUID().toString().take(6)
		val work = workItems.create(CreateWorkItem(title, "usr_owner", nextAction = "카드를 본다"))
		val (status, body) = call("GET", "/api/me/record")
		assertEquals(200, status, body)
		val record = obj(body)
		assertEquals("usr_owner", (record["person"] as Map<*, *>)["id"])
		assertEquals("mgr_owner", record["manager_id"])
		assertEquals("org_owner", (record["org"] as Map<*, *>)["id"])
		assertEquals(listOf("approver", "assignee", "master_admin", "operator", "requester", "reviewer"), record["roles"])
		val card = record["status_card"] as String
		assertTrue(card.startsWith("[zm-baton] 상태 카드 미리보기"), card)
		assertContains(card, "나를 기다리는 것:")
		assertTrue(card.length <= SessionService.STATUS_CARD_MAX)
		assertTrue((record["work"] as List<*>).any { (it as Map<*, *>)["id"] == work.id })
	}

	@Test
	fun `personal notes are mine only, no other person, operator or export reads them, and the audit row names no words`() {
		val words = "개인 메모 " + UUID.randomUUID()
		val (created, createdBody) = call("POST", "/api/me/notes", mapOf("body" to words))
		assertEquals(201, created, createdBody)
		val id = obj(createdBody)["id"] as String
		assertTrue(id.startsWith("mnt_"))
		assertTrue((obj(call("GET", "/api/me/record").second)["notes"] as List<*>).any { (it as Map<*, *>)["body"] == words })
		val (changed, changedBody) = call("PATCH", "/api/me/notes/$id", mapOf("body" to "$words 고침"))
		assertEquals(200, changed, changedBody)
		assertEquals(400, call("POST", "/api/me/notes", mapOf("body" to " ")).first)

		// No audit row and no export carries the words; the export has no notes at all.
		val details = jdbc.sql("select details::text from audit_log where target_id = :id").param("id", id).query(String::class.java).list()
		assertTrue(details.isNotEmpty() && details.none { it.orEmpty().contains("개인 메모") }, details.toString())
		val bundle = json.writeValueAsString(export.export("usr_owner"))
		assertFalse(bundle.contains(words), "the export carries no note")
		@Suppress("UNCHECKED_CAST")
		assertFalse("manager_notes" in ((export.export("usr_owner")["collections"]) as Map<String, Any?>).keys)

		rolledBack {
			val other = org.addPerson("usr_owner", PersonIn("다른 운영자", listOf("operator")))
			val e = assertFailsWith<NotFoundException> { permissions.require(facts.person(other), Act.OWN_RECORD, permissions.note(id)) }
			assertEquals("no note $id", e.message, "a hidden note answers as missing")
			assertFailsWith<NotFoundException> { managers.updateNote(other, id, NoteIn("남의 메모")) }
			assertFailsWith<NotFoundException> { managers.deleteNote(other, id) }
			assertTrue(managers.record(other).notes.isEmpty())
		}
		assertEquals(204, call("DELETE", "/api/me/notes/$id").first)
		assertEquals(404, call("DELETE", "/api/me/notes/$id").first)
	}

	@Test
	fun `a session belongs to its device's person and manager, and its card is built from that person's record`() = rolledBack {
		val b = org.addPerson("usr_owner", PersonIn("세션 주인", listOf("assignee", "requester")))
		val device = sessions.registerDevice(RegisterDevice("b-pc"), b).deviceId
		val registered = sessions.register(RegisterSession(2, device, "claude_code", null, "b-" + UUID.randomUUID()))
		val row = jdbc.sql("select user_id, manager_id, org_id from agent_sessions where id = :id").param("id", registered.sessionId)
			.query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
		assertEquals(Triple(b, "mgr_" + b.removePrefix("usr_"), "org_owner"), row)
		assertContains(registered.statusCard, "열린 작업: 없음", message = "the owner's work is not B's")

		val title = "B가 맡은 작업 " + UUID.randomUUID().toString().take(6)
		workItems.create(CreateWorkItem(title, b, nextAction = "B의 다음 행동"), b)
		val card = cards.forSession(registered.sessionId)
		assertContains(card, title)
		assertContains(card, "B의 다음 행동")
		assertFalse(cards.preview("usr_owner").contains(title), "not on the owner's card")
		assertEquals(card, sessions.statusCardFor(registered.sessionId, registered.token))

		// The session's waits go to its person's first screen.
		sessions.ingest(registered.sessionId, registered.token,
			SessionEventBatch(2, listOf(SessionEventIn("evt_" + UUID.randomUUID(), 1, "waiting", mapOf("wait_kind" to "idle_prompt")))))
		val waitingFor = jdbc.sql("select user_id from inbox_items where source_id = :s and kind = 'session_waiting'")
			.param("s", registered.sessionId).query(String::class.java).single()
		assertEquals(b, waitingFor)
		assertTrue(managers.record(b).sessions.any { it.id == registered.sessionId })
	}

	@Test
	fun `a delegation's executor is the manager of the person who hands the work over`() = rolledBack {
		val b = org.addPerson("usr_owner", PersonIn("맡기는 사람", listOf("assignee", "requester")))
		val device = sessions.registerDevice(RegisterDevice("b-handoff-pc"), b).deviceId
		val key = "git:" + UUID.randomUUID().toString().replace("-", "")
		delegations.addRepository(device, RepositoryUpsert(key, "b-repo"), b)
		val account = accounts.register(mapOf("name" to "B 조직 계정", "auth_kind" to "org_account"), "usr_owner").account.id
		val work = workItems.create(CreateWorkItem("B의 맡길 작업", b, initialStage = "ready", executorKind = "human"), b)
		val version = workItems.detail(work.id).item.version
		val after = delegations.delegate(work.id, DelegateIn("claude_code", device, key, null, null, version, accountId = account), b)
		val manager = "mgr_" + b.removePrefix("usr_")
		assertEquals("manager", after.item.executorKind)
		assertEquals(manager, after.executorId)
		val (granted, grantee, path) = jdbc.sql("select granted_by_user_id, grantee_id, call_path::text from delegations where work_item_id = :w")
			.param("w", work.id).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
		assertEquals(b, granted)
		assertEquals(manager, grantee)
		assertContains(path, manager)
		// The organisation knows the repository the device added.
		assertEquals(1L, jdbc.sql("select count(*) from repositories where org_id = 'org_owner' and repo_key = :k").param("k", key)
			.query(Long::class.javaObjectType).single())
		// B holds the work through their manager: it is on B's card and record.
		assertTrue(managers.record(b).work.any { it.id == work.id })
	}
}
