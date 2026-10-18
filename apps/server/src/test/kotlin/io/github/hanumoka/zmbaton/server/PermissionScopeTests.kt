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
	private val exports: ExportService,
	private val questions: QuestionService,
	private val peopleService: PersonService,
	private val accounts: AccountService,
	private val audit: AuditService,
	private val sessions: SessionService,
	private val cards: StatusCards,
	private val material: ContradictionMaterial,
	private val master: MasterCheckService,
	private val masterAccess: MasterAccess,
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
	fun `shared document and decision details do not reveal linked private records`() = rolledBack {
		val p = people()
		val privateWork = workItems.create(CreateWorkItem("private linked work", p.b, visibility = "personal"), p.b).id
		val sharedDecision = decisionWrites.propose(mapOf("question" to "shared decision"), p.b).decision.id
		val privateDecision = decisionWrites.propose(mapOf("question" to "private decision", "visibility" to "personal"), p.b).decision.id
		val doc = documentWrites.register(mapOf("title" to "shared document", "source_of_truth" to "product", "body_markdown" to "shared body"), p.b).document.id
		jdbc.sql("insert into decision_work_items (decision_id, work_item_id) values (:d, :w)").param("d", sharedDecision).param("w", privateWork).update()
		jdbc.sql("insert into document_work_items (document_id, work_item_id) values (:d, :w)").param("d", doc).param("w", privateWork).update()
		jdbc.sql("insert into document_decisions (document_id, decision_id) values (:d, :decision)").param("d", doc).param("decision", privateDecision).update()
		assertEquals(listOf(privateWork), decisions.detail(sharedDecision, p.b).workItems.map { it.id })
		assertEquals(listOf(privateDecision), documents.detail(doc, null, p.b).decisions.map { it.id })
		for (viewer in listOf("usr_owner", p.operator, p.outsider)) {
			assertTrue(decisions.detail(sharedDecision, viewer).workItems.isEmpty())
			assertTrue(documents.detail(doc, null, viewer).workItems.isEmpty())
			assertTrue(documents.detail(doc, null, viewer).decisions.isEmpty())
		}
	}

	@Test
	fun `a question or review recipient cannot bypass its private work through the inbox`() = rolledBack {
		val p = people()
		val work = workItems.create(CreateWorkItem("private parent title", p.b, visibility = "personal"), p.b)
		val before = workItems.detail(work.id).item
		assertFailsWith<ConflictException> {
			questions.ask(work.id, AskIn("cannot reach recipient", "approval", version = before.version), p.b)
		}
		assertEquals(before.version, workItems.detail(work.id).item.version)
		assertTrue(questions.forWorkItem(work.id).isEmpty())
		// Existing requests can lose access after their parent becomes private.
		jdbc.sql("update work_items set visibility = 'org' where id = :id").param("id", work.id).update()
		questions.ask(work.id, AskIn("private question body", "approval", version = workItems.detail(work.id).item.version), p.b)
		jdbc.sql("update work_items set visibility = 'personal' where id = :id").param("id", work.id).update()
		val question = questions.forWorkItem(work.id).single().id
		val review = Ids.review()
		jdbc.sql("""insert into review_items (id, org_id, work_item_id, recipient_user_id, target_kind, target_ref, source_key, reason, raised_by)
			values (:r, 'org_owner', :w, 'usr_owner', 'unpinned', 'fixture', :w, 'owner_watch', 'human')""")
			.param("r", review).param("w", work.id).update()
		jdbc.sql("""insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, notify)
			values (:i, 'usr_owner', 4, 'risk_review', 'rvw', :r, 'private review title', 'immediate')""")
			.param("i", Ids.inbox()).param("r", review).update()
		for (viewer in listOf("usr_owner", p.operator, p.outsider)) {
			assertFailsWith<NotFoundException> { permissions.see(facts.person(viewer), permissions.question(question)) }
			assertFailsWith<NotFoundException> { permissions.see(facts.person(viewer), permissions.review(review)) }
			assertFalse(peopleService.inbox(viewer).any { it.sourceId in listOf(question, review) })
		}
		assertTrue(PermissionRules.canSee(facts.person(p.b), permissions.question(question)))
		// Explicitly sharing the parent makes the existing recipient's requests visible again.
		jdbc.sql("update work_items set visibility = 'org' where id = :id").param("id", work.id).update()
		assertEquals(setOf(question, review), peopleService.inbox("usr_owner").filter { it.sourceId in listOf(question, review) }.map { it.sourceId }.toSet())
	}

	@Test
	fun `audit list detail facets and nested evidence follow the referenced work visibility`() = rolledBack {
		val p = people()
		val privateWork = workItems.create(CreateWorkItem("hidden audit title", p.b, visibility = "personal"), p.b).id
		val publicWork = workItems.create(CreateWorkItem("shared audit title", p.b), p.b).id
		sessions.audit("person", p.b, "privacy.fixture", "wrk", publicWork, mapOf("title" to "visible evidence"))
		sessions.audit("person", p.b, "privacy.fixture", "wrk", privateWork, mapOf("title" to "private evidence"))
		sessions.audit("person", p.b, "privacy.fixture", "wrk", publicWork,
			mapOf("comparison" to listOf(mapOf("work_item_id" to privateWork, "quote" to "copied private evidence"))))
		val query = AuditQuery(action = "privacy.fixture")
		val mine = audit.list(query, facts.person(p.b))
		assertEquals(3, mine.size)
		val hidden = mine.filter { it.targetId == privateWork || it.details.containsKey("comparison") }
		for (person in listOf("usr_owner", p.operator, p.outsider)) {
			val viewer = facts.person(person)
			assertEquals(1, audit.list(query, viewer).size)
			assertEquals(1, audit.list(query.copy(limit = 1), viewer).size)
			assertEquals(1L, audit.facets(query, viewer).total)
			for (row in hidden) assertFailsWith<NotFoundException> { audit.one(row.id, viewer) }
		}
	}

	@Test
	fun `export uses every visibility scope and removes private versions runs events and audit copies`() = rolledBack {
		val p = people()
		val works = Visibility.entries.associateWith { v ->
			workItems.create(CreateWorkItem("export work ${v.wire}", p.b, projectId = p.project, visibility = v.wire), p.b).id
		}
		val doc = documentWrites.register(mapOf("title" to "private export document", "source_of_truth" to "product",
			"body_markdown" to "hidden-version-content", "visibility" to "personal"), p.b).document.id
		val run = Ids.run()
		jdbc.sql("insert into runs (id, work_item_id, state, end_reason, executor, instruction, account_id) values (:id, :w, 'ended', 'submitted', 'claude_code', 'hidden-run-instruction', 'acc_owner_login')")
			.param("id", run).param("w", works.getValue(Visibility.PERSONAL)).update()
		jdbc.sql("insert into run_events (event_id, run_id, generation, seq, kind, payload) values (:e, :r, 1, 1, 'answer', '{\"text\":\"hidden-run-answer\"}'::jsonb)")
			.param("e", "evt_" + Ids.uuidV7()).param("r", run).update()
		sessions.audit("system", "fixture", "account.private-run", "acc", "acc_owner_login", mapOf("run_id" to run))
		for (viewer in viewers(p)) {
			val bundle = exports.export(viewer.person!!)
			@Suppress("UNCHECKED_CAST")
			val collections = bundle["collections"] as Map<String, Map<String, Any?>>
			@Suppress("UNCHECKED_CAST")
			val exported = (collections.getValue("work_items")["records"] as List<Map<String, Any?>>).map { it["id"] }.toSet()
			for ((visibility, id) in works) assertEquals(PermissionRules.canSee(viewer, permissions.workItem(id)), id in exported, "${viewer.person} / $visibility")
			val raw = bundle.toString()
			assertEquals(viewer.person == p.b, accounts.detail("acc_owner_login", viewer.person!!).runs.any { it.id == run })
			assertEquals(viewer.person == p.b, accounts.detail("acc_owner_login", viewer.person!!).history.any { it.details["run_id"] == run })
			if (viewer.person == p.b) {
				assertContains(raw, "hidden-version-content")
				assertContains(raw, "hidden-run-answer")
			} else {
				for (hidden in listOf(doc, run, "hidden-version-content", "hidden-run-instruction", "hidden-run-answer")) assertFalse(hidden in raw, hidden)
			}
		}
	}

	@Test
	fun `master work copies follow all original scopes in lists details runs accounts cards and exports`() = rolledBack {
		val p = people()
		val doc = documentWrites.register(mapOf("title" to "master source", "source_of_truth" to "product", "body_markdown" to "source body",
			"project_id" to p.project), p.b).document.id
		val decision = decisionWrites.propose(mapOf("question" to "comparison", "project_ids" to listOf(p.project)), p.b).decision.id
		val work = workItems.create(CreateWorkItem("copied-master-title", "usr_owner"), "usr_owner").id
		val run = Ids.run()
		jdbc.sql("insert into runs (id, work_item_id, state, end_reason, executor, instruction, account_id) values (:r, :w, 'ended', 'submitted', 'claude_code', 'copied-master-prompt', 'acc_owner_login')")
			.param("r", run).param("w", work).update()
		jdbc.sql("""insert into master_checks (id, org_id, trigger, subjects, material, state, work_item_id, run_id)
			values (:id, 'org_owner', 'document_version_approved', jsonb_build_array(jsonb_build_object('kind', 'document_version', 'document_id', cast(:doc as text))),
			jsonb_build_array(jsonb_build_object('kind', 'document', 'id', cast(:doc as text)), jsonb_build_object('kind', 'decision', 'id', cast(:d as text))), 'done', :w, :r)""")
			.param("id", Ids.masterCheck()).param("doc", doc).param("d", decision).param("w", work).param("r", run).update()
		for (visibility in Visibility.entries) {
			jdbc.sql("update documents set visibility = :v where id = :id").param("v", visibility.wire).param("id", doc).update()
			for (viewer in viewers(p)) {
				val expected = PermissionRules.canSee(viewer, permissions.document(doc)) && PermissionRules.canSee(viewer, permissions.decision(decision))
				assertEquals(expected, work in workItems.list(500, null, false, null, viewer).map { it.id }, "master list ${viewer.person} / $visibility")
				assertEquals(expected, PermissionRules.canSee(viewer, permissions.workItem(work)))
				assertEquals(expected, PermissionRules.canSee(viewer, permissions.run(run)))
				assertEquals(expected, accounts.detail("acc_owner_login", viewer.person!!).runs.any { it.id == run })
			}
		}
		// Either input can narrow the copied record, including after the check has finished.
		jdbc.sql("update documents set visibility = 'org' where id = :id").param("id", doc).update()
		jdbc.sql("update decisions set visibility = 'personal' where id = :id").param("id", decision).update()
		val owner = facts.person("usr_owner")
		assertFailsWith<NotFoundException> { permissions.see(owner, permissions.run(run)) }
		assertFalse("copied-master-title" in cards.preview("usr_owner"))
		val raw = exports.export("usr_owner").toString()
		for (hidden in listOf(work, run, "copied-master-title", "copied-master-prompt")) assertFalse(hidden in raw, hidden)
		assertTrue(PermissionRules.canSee(facts.person(p.b), permissions.run(run)))
		jdbc.sql("update runs set state = 'requested', end_reason = null, executor = 'anthropic_api' where id = :id").param("id", run).update()
		assertTrue(masterAccess.refuseClaim(run))
		assertEquals("cancelled", jdbc.sql("select end_reason from runs where id = :id").param("id", run).query(String::class.java).single())
	}

	@Test
	fun `the master does not send another person's private source or comparison to the organisation owner's executor`() = rolledBack {
		val p = people()
		fun doc(title: String, visibility: String): String = documentWrites.register(mapOf("title" to title,
			"source_of_truth" to "product", "body_markdown" to "uniqueprivacycomparison", "project_id" to p.project, "visibility" to visibility), p.b).document.id
		val subject = doc("shared subject", "org")
		val shared = doc("shared comparison", "org")
		val hidden = doc("hidden comparison", "personal")
		jdbc.sql("update document_versions set approved_by = 'usr_owner', approved_at = now() where doc_id in (:docs)")
			.param("docs", listOf(subject, shared, hidden)).update()
		fun version(doc: String): String = jdbc.sql("select id from document_versions where doc_id = :id").param("id", doc).query(String::class.java).single()
		val row = material.subject("document_version", version(subject))!!
		val compared = material.compare(listOf(row), 20, 6000, facts.person("usr_owner")).map { it.id }
		assertTrue(shared in compared)
		assertFalse(hidden in compared)
		assertTrue(hidden in material.compare(listOf(row), 20, 6000, facts.person(p.b)).map { it.id })
		val check = master.request(ContradictionTrigger("document_version_approved", listOf(CheckSubject("document_version", version(hidden)))))
		val result = jdbc.sql("select state, reason, run_id from master_checks where id = :id").param("id", check).query().singleRow()
		assertEquals("skipped", result["state"])
		assertEquals(null, result["run_id"])
		assertContains(result["reason"].toString(), "원본 자료를 볼 수 없어")
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
