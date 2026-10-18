package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.LoginService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["zm-baton.auth.mode=required", "zm-baton.auth.bcrypt-strength=4"])
class NoticeRecipientsTests @Autowired constructor(
	private val sessions: SessionService, private val org: OrgService, private val people: PersonService,
	private val recovery: NoticeInboxRecovery, private val jdbc: JdbcClient,
	private val usage: UsageService, private val accounts: AccountService, private val workItems: WorkItemService,
	env: Environment, private val json: ObjectMapper, private val logins: LoginService,
) {
	private val base = "http://localhost:" + env.getRequiredProperty("local.server.port")

	private fun signedIn(person: String): AuthHttp {
		val web = AuthHttp(base, json)
		val token = logins.issueSetupToken(person, "first_password").first
		val setup = web.send("POST", "/api/auth/setup", json.writeValueAsString(mapOf(
			"setup_token" to token, "login" to "notice-${UUID.randomUUID()}", "password" to AuthHttp.OWNER_PASSWORD,
		)), signedIn = false)
		assertEquals(200, setup.status)
		web.takeCookie(setup)
		web.csrf = web.obj(setup.body)["csrf_token"] as String
		return web
	}

	@Test fun `second worker on a shared account receives and can judge the repeated usage notice`() {
		val other = org.addPerson("usr_owner", PersonIn("shared account recipient", listOf("requester", "assignee")))
		val account = accounts.register(mapOf("name" to "shared ${UUID.randomUUID()}", "auth_kind" to "api_key"), "usr_owner").account.id
		fun work(person: String) = workItems.create(CreateWorkItem("shared account work", person, acceptance = "checked", executorKind = "human"), "usr_owner").id
		val a = work("usr_owner")
		val b = work(other)
		val key = "usage_alert|account|daily_budget|$account|2026-10-18"
		fun raise(work: String) = usage.raise(key, mapOf("notice_kind" to "usage_alert", "account_id" to account,
			"work_item_id" to work, "cause" to "daily_budget"), "shared account limit", true)
		raise(a)
		val notice = people.inbox("usr_owner").single { it.detail?.get("work_item_id") == a }.sourceId
		val otherWeb = signedIn(other)
		assertEquals(403, otherWeb.call("POST", "/api/notices/$notice/status", """{"status":"resolved"}""").first,
			"before receiving the repeat, the other person cannot judge the notice")
		raise(b)
		raise(b)
		assertEquals(1, people.inbox(other).count { it.sourceId == notice })
		assertEquals(1, people.inbox("usr_owner").count { it.sourceId == notice })
		val recipients = jdbc.sql("select array_to_string(recipients, ',') from notices where id = :id").param("id", notice).query(String::class.java).single().split(',')
		assertEquals(setOf("usr_owner", other), recipients.toSet())
		assertEquals(2, recipients.size, "repeated delivery does not duplicate judgement authority")
		assertEquals(200, otherWeb.call("POST", "/api/notices/$notice/status", """{"status":"resolved"}""").first,
			"the repeated notice grants judgement through the authenticated HTTP permission path")
		assertFalse(people.inbox("usr_owner").any { it.sourceId == notice })
		assertFalse(people.inbox(other).any { it.sourceId == notice })
	}

	private data class Fixture(val person: String, val key: String, val a: SessionRegistered, val b: SessionRegistered, val notice: String)

	private fun touch(s: SessionRegistered, key: String, seq: Long) = sessions.ingest(s.sessionId, s.token,
		SessionEventBatch(2, listOf(SessionEventIn(Ids.event(), seq, "file_touch",
			mapOf("repo_key" to key, "path" to "README.md", "tool" to "Edit", "content_hash" to "ab12cd34ef")))))

	private fun fixture(): Fixture {
		val person = org.addPerson("usr_owner", PersonIn("notice recipient", listOf("requester")))
		val key = "git:" + UUID.randomUUID().toString().replace("-", "") + "00000000"
		fun session(user: String): SessionRegistered {
			val device = sessions.registerDevice(RegisterDevice("notice-test"), user).deviceId
			return sessions.register(RegisterSession(2, device, "claude_code", executorSessionId = UUID.randomUUID().toString(),
				repository = RepositoryIn(key, "https://example.org/fixture.git", "", "main")))
		}
		val a = session("usr_owner")
		val b = session(person)
		touch(a, key, 1)
		touch(b, key, 1)
		val notice = people.inbox(person).single { it.detail?.get("repo_key") == key }.sourceId
		return Fixture(person, key, a, b, notice)
	}

	@Test fun `both people receive the shared conflict once and either judgement closes both`() {
		val f = fixture()
		fun mine(person: String) = people.inbox(person).filter { it.sourceId == f.notice }
		assertEquals(1, mine("usr_owner").size)
		assertEquals(1, mine(f.person).size)
		assertNotEquals(mine("usr_owner").single().id, mine(f.person).single().id)
		touch(f.a, f.key, 2)
		assertEquals(1, mine("usr_owner").size)
		assertEquals(1, mine(f.person).size)
		people.judgeNotice(f.notice, NoticeStatusIn("resolved"), f.person)
		assertTrue(mine("usr_owner").isEmpty())
		assertTrue(mine(f.person).isEmpty())
		touch(f.b, f.key, 2)
		assertEquals(1, mine("usr_owner").size)
		assertEquals(1, mine(f.person).size)
	}

	@Test fun `upgrade recovery preserves the old recipient row and fills only missing open recipients`() {
		val f = fixture()
		val original = people.inbox("usr_owner").single { it.sourceId == f.notice }.id
		jdbc.sql("delete from inbox_items where source_id = :id and user_id = :p").param("id", f.notice).param("p", f.person).update()
		assertFalse(people.inbox(f.person).any { it.sourceId == f.notice })
		recovery.recover()
		recovery.recover()
		assertEquals(original, people.inbox("usr_owner").single { it.sourceId == f.notice }.id)
		assertEquals(1, people.inbox(f.person).count { it.sourceId == f.notice })
		people.judgeNotice(f.notice, NoticeStatusIn("acknowledged"), "usr_owner")
		recovery.recover()
		assertFalse(people.inbox(f.person).any { it.sourceId == f.notice })
	}
}
