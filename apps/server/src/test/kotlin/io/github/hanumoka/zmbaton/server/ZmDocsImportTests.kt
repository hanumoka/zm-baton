package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The zm-docs trial import against a real PostgreSQL 17. Every test makes its own numbers, so
 * tests sharing one database do not see each other's projects, tasks or decisions.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ZmDocsImportTests @Autowired constructor(
	private val imports: ZmDocsImportService,
	private val workItems: WorkItemService,
	private val decisions: DecisionService,
	private val sessions: SessionService,
	private val jdbc: JdbcClient,
) {

	private fun repoKey() = "git:" + UUID.randomUUID().toString().replace("-", "")

	private fun num(digits: Int) = (1..digits).joinToString("") { Random.nextInt(0, 10).toString() }.let { "9$it" }

	private fun projectsJson(vararg projects: Pair<String, String>) =
		"""{"projects":[${projects.joinToString(",") { """{"id":"${it.first}","name":"${it.second}","lifecycle":"active"}""" }}]}"""

	private fun tasksJson(vararg tasks: String) = """{"schema_version":1,"open_ceiling":20,"tasks":[${tasks.joinToString(",")}]}"""

	private fun task(
		id: String, state: String, project: String? = null, nextAction: String = "다음에 할 일",
		blocker: String? = null, closed: String? = null, title: String = "작업 $id",
	) = """{"id":"$id","title":"$title","state":"$state","project":${project?.let { "\"$it\"" } ?: "null"},
		"next_action":"$nextAction","source":"docs/a.md","opened":"2026-10-01","due":null,
		"blocker":${blocker?.let { "\"$it\"" } ?: "null"},"closed":${closed?.let { "\"$it\"" } ?: "null"}}"""

	/** A decision file; [projects] is the rest of the `projects:` line, or more lines for a block list. */
	private fun decision(ref: String, title: String, projects: String, extraFront: String = "") =
		(listOf("---", "doc_type: decision", "id: $ref", "title: $title", "status: accepted", "date: 2026-10-12",
			"owner: PER-001", "projects: $projects", extraFront, "---", "", "# $ref: $title") + DEFAULT_BODY).joinToString("\n")

	private val DEFAULT_BODY = listOf(
		"", "## 맥락", "", "어떤 일이 있었다.", "", "```text", "## 이것은 코드 안의 줄이라 절이 아니다", "```", "",
		"## 결정", "", "이렇게 한다. 첫 문단이다.", "", "둘째 문단이다.", "",
		"## 재검토 조건", "", "| 조건 | 판정일 |", "|---|---|", "| 불편하면 | 사건 발생 시 |",
	)

	private fun run(repo: String, apply: Boolean, vararg files: Pair<String, String>) =
		imports.import(ZmDocsImportIn(repo, files.map { ImportFile(it.first, it.second) }, apply))

	private fun byRef(ref: String): WorkItemDetail {
		val id = jdbc.sql("select id from work_items where external_ref = :ref").param("ref", ref).query(String::class.java).single()
		return workItems.detail(id)
	}

	@Test
	fun `a dry run reports what an apply then does, and a second apply changes nothing`() {
		val repo = repoKey()
		val prj = "PRJ-" + num(5)
		val t1 = "TSK-" + num(6)
		val t2 = "TSK-" + num(6)
		val files = arrayOf(
			"registry/projects.json" to projectsJson(prj to "alpha"),
			"registry/tasks.json" to tasksJson(task(t1, "open", prj), task(t2, "doing", prj)),
		)
		val dry = run(repo, false, *files)
		assertFalse(dry.applied)
		assertEquals(1, dry.projects.created)
		assertEquals(2, dry.workItems.created)
		assertEquals(0L, jdbc.sql("select count(*) from work_items where external_ref in (:refs)").param("refs", listOf(t1, t2))
			.query(Long::class.javaObjectType).single())

		val applied = run(repo, true, *files)
		assertTrue(applied.applied)
		assertEquals(dry.changes, applied.changes)
		assertEquals(2, applied.workItems.created)

		val again = run(repo, true, *files)
		assertEquals(0, again.workItems.created + again.workItems.updated)
		assertEquals(2, again.workItems.unchanged)
		assertEquals(1, again.projects.unchanged)
		assertTrue(again.changes.isEmpty())
	}

	@Test
	fun `task states become stages and wait reasons, and blocked keeps its reason`() {
		val repo = repoKey()
		val prj = "PRJ-" + num(5)
		val refs = List(5) { "TSK-" + num(6) }
		run(repo, true,
			"registry/projects.json" to projectsJson(prj to "alpha"),
			"registry/tasks.json" to tasksJson(
				task(refs[0], "open", prj), task(refs[1], "doing", prj), task(refs[2], "blocked", prj, blocker = "컨테이너가 안 뜬다"),
				task(refs[3], "done", prj, closed = "2026-10-05"), task(refs[4], "dropped", null, closed = "2026-10-06"),
			),
		)
		val stages = refs.map { byRef(it).item.stage }
		assertEquals(listOf("ready", "in_progress", "in_progress", "done", "cancelled"), stages)
		val blocked = byRef(refs[2]).item
		assertEquals(listOf("waiting_other"), blocked.waitReasons)
		assertEquals("컨테이너가 안 뜬다", blocked.waitNote)
		val done = byRef(refs[3])
		assertEquals("2026-10-05", done.item.closedAt!!.toLocalDate().toString())
		assertEquals(prj, done.item.project!!.externalRef)
		assertEquals("다음에 할 일", done.item.nextAction)
		assertEquals("docs/a.md", done.sourceRef)
		assertEquals("2026-10-01", done.item.openedOn.toString())
		assertNull(byRef(refs[4]).item.project)
		assertEquals("git", done.item.sourceOfTruth)
		assertEquals("registry/tasks.json", done.gitRef!!["path"])
	}

	@Test
	fun `a changed task is synced with the fields that changed, and the copy cannot be edited here`() {
		val repo = repoKey()
		val ref = "TSK-" + num(6)
		run(repo, true, "registry/tasks.json" to tasksJson(task(ref, "open")))
		val before = byRef(ref)
		val synced = run(repo, true, "registry/tasks.json" to tasksJson(task(ref, "doing", nextAction = "바뀐 다음 할 일")))
		assertEquals(1, synced.workItems.updated)
		assertEquals(listOf("stage", "next_action"), synced.changes.single().fields)
		val after = byRef(ref)
		assertEquals("in_progress", after.item.stage)
		assertEquals(before.item.version + 1, after.item.version)
		assertEquals("work_item.synced", after.history.first().action)

		assertNotNull(after.readOnlyReason)
		assertTrue(after.moves.none { it.allowed })
		val stale = assertFailsWith<ConflictException> {
			workItems.update(after.item.id, mapOf("version" to after.item.version, "title" to "고치기"))
		}
		assertContains(stale.message!!, "정본이 Git")
		assertFailsWith<ConflictException> { workItems.transition(after.item.id, TransitionIn("cancelled", after.item.version)) }
	}

	@Test
	fun `a work item whose source of truth moved here is no longer imported`() {
		val repo = repoKey()
		val ref = "TSK-" + num(6)
		run(repo, true, "registry/tasks.json" to tasksJson(task(ref, "open")))
		jdbc.sql("update work_items set source_of_truth = 'product' where external_ref = :ref").param("ref", ref).update()
		val result = run(repo, true, "registry/tasks.json" to tasksJson(task(ref, "done", closed = "2026-10-07")))
		assertEquals(1, result.workItems.skipped)
		assertEquals("ready", byRef(ref).item.stage)
	}

	@Test
	fun `decisions keep their sections, projects and supersession`() {
		val repo = repoKey()
		val p1 = "PRJ-" + num(5)
		val p2 = "PRJ-" + num(5)
		val old = "DEC-" + num(5)
		val new = "DEC-" + num(5)
		val result = run(repo, true,
			"registry/projects.json" to projectsJson(p1 to "alpha", p2 to "beta"),
			"docs/governance/decisions/$old-old.md" to decision(old, "예전 결정", "[$p1]", "superseded_by: $new"),
			"docs/governance/decisions/$new-new.md" to decision(new, "새 결정: 콜론이 있는 제목", "\n  - $p1\n  - $p2\n  - PRJ-000000",
				"supersedes: $old"),
		)
		assertEquals(2, result.decisions.created)
		assertTrue(result.warnings.any { "PRJ-000000" in it })

		val newId = jdbc.sql("select id from decisions where external_ref = :ref").param("ref", new).query(String::class.java).single()
		val d = decisions.detail(newId)
		assertEquals("새 결정: 콜론이 있는 제목", d.decision.question)
		assertEquals("accepted", d.decision.status)
		assertEquals("2026-10-12", d.decision.decidedOn.toString())
		assertEquals(listOf(p1, p2), d.decision.projects.map { it.externalRef })
		assertEquals(listOf("맥락", "결정", "재검토 조건"), d.sections.map { it.heading })
		assertContains(d.sections.first().body, "## 이것은 코드 안의 줄이라 절이 아니다")
		assertEquals("이렇게 한다. 첫 문단이다.\n\n둘째 문단이다.", d.choice)
		assertEquals("이렇게 한다. 첫 문단이다.", d.decision.choicePreview)
		assertEquals(old, d.decision.supersedes!!.externalRef)
		assertEquals(new, decisions.list(500, null, null, old).single().supersededBy!!.externalRef)
		assertEquals("docs/governance/decisions/$new-new.md", d.gitRef!!["path"])

		val listed = decisions.list(500, jdbc.sql("select id from projects where external_ref = :r").param("r", p2)
			.query(String::class.java).single(), null, null)
		assertEquals(listOf(new), listed.map { it.externalRef })
	}

	@Test
	fun `files that do not fit are skipped with a warning and nothing else breaks`() {
		val repo = repoKey()
		val ref = "DEC-" + num(5)
		val result = run(repo, true,
			"docs/governance/decisions/$ref-x.md" to decision("DEC-0001", "번호가 다르다", "[]"),
			"docs/other/notes.md" to "# 대상이 아니다",
			"registry/tasks.json" to "{ not json",
		)
		assertEquals(0, result.decisions.created)
		assertEquals(3, result.warnings.size)
		assertFailsWith<BadRequestException> { run("C:/Users/someone/repo", false) }
	}

	@Test
	fun `the status card shows next actions and this repository's newest decisions`() {
		val repo = repoKey()
		val ref = "TSK-" + num(6)
		val dec = "DEC-" + num(5)
		run(repo, true,
			"registry/tasks.json" to tasksJson(task(ref, "doing", nextAction = "카드에 보일 다음 행동", title = "카드에 보일 작업")),
			"docs/governance/decisions/$dec-card.md" to decision(dec, "카드에 보일 결정", "[]"),
		)
		val device = sessions.registerDevice(RegisterDevice("card-pc", "windows")).deviceId
		val registered = sessions.register(
			RegisterSession(2, device, "claude_code", null, UUID.randomUUID().toString(), RepositoryIn(repo, null, "", "main")),
		)
		val card = registered.statusCard
		assertContains(card, "이 저장소의 최근 결정:")
		assertContains(card, "$dec (2026-10-12) 카드에 보일 결정")
		assertTrue(card.length <= SessionService.STATUS_CARD_MAX)
		assertTrue(card.endsWith("올린다."))
		// The work item is in progress, so it comes before older open work in the card.
		assertContains(card, "$ref 카드에 보일 작업 (수행)")
		assertContains(card, "  다음: 카드에 보일 다음 행동")
	}
}
