package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class NoticeRecipientsTests @Autowired constructor(
	private val sessions: SessionService, private val org: OrgService, private val people: PersonService,
	private val recovery: NoticeInboxRecovery, private val jdbc: JdbcClient,
) {
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
