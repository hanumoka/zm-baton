package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.Permissions
import io.github.hanumoka.zmbaton.server.permission.Role
import io.github.hanumoka.zmbaton.server.permission.Target
import io.github.hanumoka.zmbaton.server.permission.Visibility
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The judge against real rows (FN-0103): several people in one organisation, records in each
 * sharing scope, and what each person may see and do. Each test runs in a transaction that is rolled
 * back, so the people it adds do not reach other tests.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PermissionScopeTests @Autowired constructor(
	private val org: OrgService,
	private val facts: PermissionFacts,
	private val permissions: Permissions,
	private val workItems: WorkItemService,
	private val decisions: DecisionService,
	private val decisionWrites: DecisionWriteService,
	private val documents: DocumentService,
	private val documentWrites: DocumentWriteService,
	private val jdbc: JdbcClient,
	transactions: PlatformTransactionManager,
) {
	private val tx = TransactionTemplate(transactions)

	private fun rolledBack(block: () -> Unit) {
		tx.execute { status ->
			try {
				block()
			} finally {
				status.setRollbackOnly()
			}
		}
	}

	private class People(val b: String, val operator: String, val member: String, val teamMate: String, val outsider: String, val project: String)

	/** B (assignee), an operator, a project member, a team mate of the project's team, and a person in no project or team. */
	private fun people(): People {
		val b = org.addPerson("usr_owner", PersonIn("B", listOf("assignee", "requester")))
		val operator = org.addPerson("usr_owner", PersonIn("운영자", listOf("operator")))
		val member = org.addPerson("usr_owner", PersonIn("프로젝트 사람", listOf("requester")))
		val teamMate = org.addPerson("usr_owner", PersonIn("팀 사람", listOf("requester")))
		val outsider = org.addPerson("usr_owner", PersonIn("밖의 사람", listOf("requester")))
		val project = org.createItem("usr_owner", "project", mapOf("name" to "제한 프로젝트"))
		val team = org.createItem("usr_owner", "team", mapOf("name" to "그 팀"))
		org.setMembers("usr_owner", project, listOf(member))
		org.setMembers("usr_owner", team, listOf(teamMate))
		org.link("usr_owner", LinkIn(team, project))
		return People(b, operator, member, teamMate, outsider, project)
	}

	private fun viewers(p: People): List<Actor> = listOf("usr_owner", p.b, p.operator, p.member, p.teamMate, p.outsider).map { facts.person(it) }

	@Test
	fun `each viewer's work list holds exactly what the rules let them see, and an operator never sees another's personal work`() = rolledBack {
		val p = people()
		val ids = Visibility.entries.associateWith { v ->
			workItems.create(CreateWorkItem("범위 ${v.wire}", p.b, projectId = p.project, visibility = v.wire), p.b).id
		}
		for (viewer in viewers(p)) {
			val listed = workItems.list(500, p.project, false, null, viewer).map { it.id }.toSet()
			for ((v, id) in ids) {
				val expected = PermissionRules.canSee(viewer, permissions.workItem(id))
				assertEquals(expected, id in listed, "${viewer.person} / ${v.wire}: list and rules agree")
			}
		}
		val personal = ids.getValue(Visibility.PERSONAL)
		for (who in listOf("usr_owner", p.operator)) {
			val viewer = facts.person(who)
			assertFalse(personal in workItems.list(500, null, false, null, viewer).map { it.id }, "$who does not list it")
			val e = assertFailsWith<NotFoundException> { permissions.see(viewer, permissions.workItem(personal)) }
			assertEquals("no work item $personal", e.message, "answered as missing")
		}
		permissions.see(facts.person(p.b), permissions.workItem(personal))
		assertTrue(ids.getValue(Visibility.RESTRICTED) in workItems.list(500, null, false, null, facts.person(p.member)).map { it.id })
		assertFalse(ids.getValue(Visibility.RESTRICTED) in workItems.list(500, null, false, null, facts.person(p.teamMate)).map { it.id })
		assertTrue(ids.getValue(Visibility.TEAM) in workItems.list(500, null, false, null, facts.person(p.teamMate)).map { it.id })
		assertFalse(ids.getValue(Visibility.TEAM) in workItems.list(500, null, false, null, facts.person(p.outsider)).map { it.id })
		// The project's count of open work is what the viewer may see.
		val count = { who: String -> workItems.projects(facts.person(who)).single { it.id == p.project }.openWorkItems }
		assertEquals(5L, count(p.b))
		assertEquals(2L, count(p.outsider), "org and public")
	}

	@Test
	fun `decisions and documents are filtered by the same rules as their records`() = rolledBack {
		val p = people()
		val decisionIds = Visibility.entries.associateWith { v ->
			decisionWrites.propose(mapOf("question" to "범위 ${v.wire}", "project_ids" to listOf(p.project), "visibility" to v.wire), p.b).decision.id
		}
		val documentIds = Visibility.entries.associateWith { v ->
			documentWrites.register(
				mapOf("title" to "범위 ${v.wire}", "source_of_truth" to "product", "project_id" to p.project, "body_markdown" to "본문", "visibility" to v.wire),
				p.b,
			).document.id
		}
		for (viewer in viewers(p)) {
			val listedDecisions = decisions.list(500, p.project, null, null, viewer).map { it.id }.toSet()
			val listedDocuments = documents.list(500, p.project, null, null, viewer).map { it.id }.toSet()
			for (v in Visibility.entries) {
				val d = decisionIds.getValue(v)
				assertEquals(PermissionRules.canSee(viewer, permissions.decision(d)), d in listedDecisions, "decision ${viewer.person} / ${v.wire}")
				val doc = documentIds.getValue(v)
				assertEquals(PermissionRules.canSee(viewer, permissions.document(doc)), doc in listedDocuments, "document ${viewer.person} / ${v.wire}")
			}
		}
		assertFalse(decisionIds.getValue(Visibility.PERSONAL) in decisions.list(500, null, null, null, facts.person("usr_owner")).map { it.id })
		// The decision's approver (the organisation's owner) sees a restricted proposal to decide on it.
		assertTrue(decisionIds.getValue(Visibility.RESTRICTED) in decisions.list(500, null, null, null, facts.person("usr_owner")).map { it.id })
		assertEquals("restricted", decisions.load(decisionIds.getValue(Visibility.RESTRICTED))!!.visibility)
	}

	@Test
	fun `a role a person lacks is refused with the reason, and the refusal is audited`() = rolledBack {
		val p = people()
		val b = facts.person(p.b)
		val e = assertFailsWith<ForbiddenException> { permissions.require(b, Act.SETTINGS) }
		assertEquals("이 일(조직 설정 바꾸기)에는 운영자 역할 가운데 하나가 필요하다", e.message)
		val audited = jdbc.sql("select details ->> 'act' from audit_log where action = 'permission.refused' and actor_id = :b")
			.param("b", p.b).query(String::class.java).list()
		assertEquals(listOf("settings"), audited)
		assertFailsWith<ForbiddenException> { permissions.require(b, Act.ORG_MANAGE) }
		assertFailsWith<ForbiddenException> { permissions.require(b, Act.DECISION_APPROVE, permissions.decision(anyDecision(p))) }

		// Changing someone else's work needs a relation or the operator role; the scope only its owner changes.
		val owners = workItems.create(CreateWorkItem("소유자의 작업", "usr_owner"), "usr_owner").id
		val change = assertFailsWith<ForbiddenException> { permissions.require(b, Act.WORK_CHANGE, permissions.workItem(owners)) }
		assertContains(change.message!!, "책임자·담당·받는 사람이나 운영자만")
		permissions.require(facts.person(p.operator), Act.WORK_CHANGE, permissions.workItem(owners))
		assertFailsWith<ForbiddenException> { permissions.require(facts.person(p.operator), Act.SHARE, permissions.workItem(owners)) }
		permissions.require(facts.person("usr_owner"), Act.SHARE, permissions.workItem(owners))

		// An inactive person may do nothing, whatever roles they still hold.
		org.updatePerson("usr_owner", p.operator, mapOf("status" to "inactive"))
		val inactive = assertFailsWith<ForbiddenException> { permissions.require(facts.person(p.operator), Act.SETTINGS) }
		assertEquals(PermissionRules.NOT_MEMBER, inactive.message)
		assertFalse(facts.person(p.operator).member)
	}

	@Test
	fun `the responsible person of new work must be an active member of the organisation`() = rolledBack {
		val e = assertFailsWith<BadRequestException> { workItems.create(CreateWorkItem("남의 작업", "usr_stranger"), "usr_owner") }
		assertContains(e.message!!, "활동 중인 사람이어야 한다")
		val p = people()
		org.updatePerson("usr_owner", p.outsider, mapOf("status" to "inactive"))
		assertFailsWith<BadRequestException> { workItems.create(CreateWorkItem("떠난 사람의 작업", p.outsider), "usr_owner") }
	}

	@Test
	fun `the owner keeps every act on the records stage 1 made`() = rolledBack {
		val owner = facts.person("usr_owner")
		assertEquals(Role.entries.toSet(), owner.roles)
		assertEquals("mgr_owner", owner.manager)
		val work = workItems.create(CreateWorkItem("소유자 작업", "usr_owner"), "usr_owner").id
		val target: Target = permissions.workItem(work)
		for (act in Act.entries) assertTrue(PermissionRules.check(owner, act, target).allowed, act.name)
		assertEquals("usr_owner", facts.approverFor("org_owner", "decision"))
		assertEquals(ApprovalPolicy.approverFor("org_owner", "decision"), facts.approverFor("org_owner", "decision"))
	}

	private fun anyDecision(p: People): String =
		decisionWrites.propose(mapOf("question" to "승인할 결정"), p.b).decision.id
}
