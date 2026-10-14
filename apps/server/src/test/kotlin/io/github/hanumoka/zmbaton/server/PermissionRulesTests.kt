package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.ActorKind
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.Role
import io.github.hanumoka.zmbaton.server.permission.Target
import io.github.hanumoka.zmbaton.server.permission.Visibility
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The judge's rules alone (FN-0103): roles, sharing scope and relation, allowed and refused. */
class PermissionRulesTests {

	private val org = "org_a"

	private fun person(
		id: String,
		vararg roles: Role,
		teams: Set<String> = emptySet(),
		projects: Set<String> = emptySet(),
		member: Boolean = true,
		orgId: String = org,
	) = Actor(ActorKind.PERSON, id, id, "mgr_" + id.removePrefix("usr_"), orgId, member, roles.toSet(), teams, projects)

	private val owner = person("usr_owner", *Role.entries.toTypedArray())

	private fun work(visibility: Visibility, owner: String = "usr_a", executor: String? = null, project: String? = "prj_x", teams: Set<String> = emptySet()) =
		Target("wrk", "wrk_1", org, visibility, owner, setOfNotNull(executor), setOfNotNull(project), teams, "no work item wrk_1")

	@Test
	fun `the owner of a one-person organisation may do every act on every record of theirs`() {
		for (act in Act.entries) {
			val target = if (act == Act.READ || act == Act.WORK_CREATE) null else work(Visibility.ORG, owner = "usr_owner")
			assertTrue(PermissionRules.check(owner, act, target).allowed, act.name)
		}
		for (v in Visibility.entries) assertTrue(PermissionRules.canSee(owner, work(v, owner = "usr_owner")), v.name)
	}

	@Test
	fun `a personal record is its owner's only, an operator and the organisation's owner included`() {
		val note = work(Visibility.PERSONAL, owner = "usr_a")
		assertTrue(PermissionRules.canSee(person("usr_a"), note))
		assertFalse(PermissionRules.canSee(owner, note), "the owner with every role")
		assertFalse(PermissionRules.canSee(person("usr_op", Role.OPERATOR), note), "an operator")
		val refused = PermissionRules.check(owner, Act.READ, note)
		assertFalse(refused.allowed)
		assertTrue(refused.hidden, "a hidden record answers as missing")
		assertEquals("no work item wrk_1", refused.reason)
		// Not even through the person's executor slot: the manager of someone else is not the owner.
		assertFalse(PermissionRules.canSee(person("usr_b"), work(Visibility.PERSONAL, owner = "usr_a", executor = "mgr_b")))
	}

	@Test
	fun `restricted is the owner, the people it names and the project's members, and team adds the project's teams`() {
		val restricted = work(Visibility.RESTRICTED, executor = "mgr_e")
		assertTrue(PermissionRules.canSee(person("usr_a"), restricted), "owner")
		assertTrue(PermissionRules.canSee(person("usr_e"), restricted), "executor through their manager")
		assertTrue(PermissionRules.canSee(person("usr_m", projects = setOf("prj_x")), restricted), "project member")
		assertFalse(PermissionRules.canSee(person("usr_t", teams = setOf("tem_1")), restricted), "a team is not enough for restricted")
		assertFalse(PermissionRules.canSee(person("usr_o", *Role.entries.toTypedArray()), restricted), "every role is not enough")

		val team = work(Visibility.TEAM, teams = setOf("tem_1"))
		assertTrue(PermissionRules.canSee(person("usr_t", teams = setOf("tem_1")), team), "a team that takes on the project")
		assertFalse(PermissionRules.canSee(person("usr_u", teams = setOf("tem_2")), team), "another team's (G06.1)")
		assertTrue(PermissionRules.canSee(person("usr_m", projects = setOf("prj_x")), team), "a project member")
	}

	@Test
	fun `org is every active member of the same organisation, public is anyone`() {
		val shared = work(Visibility.ORG)
		assertTrue(PermissionRules.canSee(person("usr_z"), shared))
		assertFalse(PermissionRules.canSee(person("usr_z", member = false), shared), "inactive")
		assertFalse(PermissionRules.canSee(person("usr_z", orgId = "org_b"), shared), "another organisation")
		assertTrue(PermissionRules.canSee(person("usr_z", orgId = "org_b"), work(Visibility.PUBLIC)))
		assertTrue(PermissionRules.check(person("usr_z", orgId = "org_b"), Act.READ, shared).hidden)
		val othersPublic = PermissionRules.check(person("usr_op", Role.OPERATOR, orgId = "org_b"), Act.WORK_CHANGE, work(Visibility.PUBLIC))
		assertFalse(othersPublic.allowed, "another organisation's public record is read, never changed")
		assertTrue(othersPublic.hidden)
		// A record the actor may not see answers as missing even where a role is also missing.
		val hidden = PermissionRules.check(person("usr_r", Role.REQUESTER), Act.WORK_CHANGE, work(Visibility.PERSONAL))
		assertTrue(hidden.hidden && hidden.reason == "no work item wrk_1", hidden.toString())
	}

	@Test
	fun `roles decide the acts and each refusal names what is missing`() {
		val requester = person("usr_r", Role.REQUESTER)
		assertTrue(PermissionRules.check(requester, Act.WORK_CREATE).allowed)
		assertTrue(PermissionRules.check(requester, Act.DECISION_WRITE).allowed)
		val settings = PermissionRules.check(requester, Act.SETTINGS)
		assertFalse(settings.allowed)
		assertEquals("이 일(조직 설정 바꾸기)에는 운영자 역할 가운데 하나가 필요하다", settings.reason)
		assertFalse(PermissionRules.check(requester, Act.DECISION_APPROVE, work(Visibility.ORG)).allowed)
		assertFalse(PermissionRules.check(requester, Act.ORG_MANAGE).allowed)
		assertFalse(PermissionRules.check(person("usr_none"), Act.WORK_CREATE).allowed, "no role, no new work")
		assertTrue(PermissionRules.check(person("usr_none"), Act.READ).allowed, "any member reads what is shared")
		assertTrue(PermissionRules.check(person("usr_ap", Role.APPROVER), Act.DOCUMENT_APPROVE, work(Visibility.ORG)).allowed)
		assertEquals(PermissionRules.NOT_MEMBER, PermissionRules.check(person("usr_x", Role.OPERATOR, member = false), Act.SETTINGS).reason)
	}

	@Test
	fun `changing a record needs a relation to it unless the role overrides, and its scope only its owner changes`() {
		val assignee = person("usr_b", Role.ASSIGNEE)
		val others = work(Visibility.ORG, owner = "usr_a")
		val change = PermissionRules.check(assignee, Act.WORK_CHANGE, others)
		assertFalse(change.allowed)
		assertEquals("이 일(작업 고치기·단계 옮기기)은 그 기록의 책임자·담당·받는 사람이나 운영자만 한다", change.reason)
		assertTrue(PermissionRules.check(assignee, Act.WORK_CHANGE, work(Visibility.ORG, executor = "usr_b")).allowed, "the executor")
		assertTrue(PermissionRules.check(assignee, Act.WORK_HANDOFF, work(Visibility.ORG, executor = "mgr_b")).allowed, "through the manager")
		assertTrue(PermissionRules.check(person("usr_op", Role.OPERATOR), Act.WORK_CHANGE, others).allowed, "operator override")
		assertFalse(PermissionRules.check(person("usr_op", Role.OPERATOR), Act.SHARE, others).allowed, "no override for the scope")
		assertTrue(PermissionRules.check(person("usr_a"), Act.SHARE, others).allowed)
		assertFalse(PermissionRules.check(person("usr_op", Role.OPERATOR), Act.OWN_RECORD, work(Visibility.PERSONAL)).allowed)
		val notice = Target("ntc", "ntc_1", org, people = setOf("usr_a"))
		assertTrue(PermissionRules.check(person("usr_a"), Act.NOTICE_JUDGE, notice).allowed, "a recipient")
		assertFalse(PermissionRules.check(person("usr_op", Role.OPERATOR), Act.NOTICE_JUDGE, notice).allowed, "operator is not the override")
		assertTrue(PermissionRules.check(person("usr_ma", Role.MASTER_ADMIN), Act.NOTICE_JUDGE, notice).allowed, "master admin")
	}

	@Test
	fun `a session, a run or a manager never does what only a person does, whatever its person may do`() {
		for (kind in listOf(ActorKind.SESSION, ActorKind.RUN, ActorKind.MANAGER, ActorKind.SYSTEM)) {
			val ai = owner.copy(kind = kind, id = "x_1")
			val review = PermissionRules.check(ai, Act.REVIEW_DECIDE, work(Visibility.ORG, owner = "usr_owner"))
			assertFalse(review.allowed, kind.name)
			assertEquals("사람 검토 요청은 사람만 승인하거나 반려한다. AI는 닫을 수 없다", review.reason)
			for (act in listOf(Act.DECISION_APPROVE, Act.DOCUMENT_APPROVE, Act.RELAY_ANSWER, Act.NOTICE_JUDGE, Act.SETTINGS,
				Act.ACCOUNTS, Act.ORG_MANAGE, Act.AUDIT_READ, Act.EXPORT)) {
				assertFalse(PermissionRules.check(ai, act, work(Visibility.ORG, owner = "usr_owner")).allowed, "${kind.name} ${act.name}")
			}
			assertTrue(PermissionRules.check(ai, Act.READ, work(Visibility.ORG)).allowed, "${kind.name} still reads what its person may")
		}
	}

	@Test
	fun `a stored scope the rules do not know counts as the narrowest`() {
		assertEquals(Visibility.PERSONAL, Visibility.stored("something"))
		assertEquals(Visibility.PERSONAL, Visibility.stored(null))
		assertEquals(Visibility.TEAM, Visibility.stored("team"))
	}
}
