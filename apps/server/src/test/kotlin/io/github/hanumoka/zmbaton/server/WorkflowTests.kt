package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract v1 "단계": the moves a person may make, without a database. */
class WorkflowTests {

	private fun facts(
		stage: String,
		executor: String? = "human",
		liveRun: Boolean = false,
		skipped: Set<String> = emptySet(),
		readOnly: String? = null,
	) = Workflow.Facts(stage, executor, liveRun, skipped, readOnly)

	@Test
	fun `every stage offers the contract's moves and closed stages offer none`() {
		val expected = mapOf(
			"draft" to listOf("plan_review", "cancelled"),
			"plan_review" to listOf("ready", "cancelled"),
			"ready" to listOf("in_progress", "cancelled"),
			"in_progress" to listOf("result_review", "plan_review", "cancelled"),
			"result_review" to listOf("done", "in_progress", "plan_review", "cancelled"),
			"done" to emptyList(),
			"cancelled" to emptyList(),
		)
		for ((stage, targets) in expected) {
			assertEquals(targets, Workflow.moves(facts(stage)).map { it.target }, stage)
			assertTrue(Workflow.moves(facts(stage)).all { it.allowed }, stage)
		}
	}

	@Test
	fun `a move the table does not have is refused with a reason a person can read`() {
		val move = Workflow.move(facts("draft"), "done")
		assertFalse(move.allowed)
		assertEquals("초안에서 완료(으)로는 넘어갈 수 없다", move.reason)
		assertFalse(Workflow.move(facts("done"), "in_progress").allowed)
	}

	@Test
	fun `skipped review stages are passed through to the stage after them`() {
		val both = setOf("plan_review", "result_review")
		val plan = Workflow.move(facts("draft", skipped = both), "plan_review")
		assertEquals(listOf("plan_review", "ready"), plan.path)
		assertEquals("ready", plan.finalStage)
		val result = Workflow.move(facts("in_progress", skipped = both), "result_review")
		assertEquals(listOf("result_review", "done"), result.path)
		// Going back to planning while planning is skipped lands in ready again.
		assertEquals("ready", Workflow.move(facts("in_progress", skipped = both), "plan_review").finalStage)
		// Without skipping the review is a real stop.
		assertEquals(listOf("result_review"), Workflow.move(facts("in_progress"), "result_review").path)
	}

	@Test
	fun `an AI executor enters in_progress only through an accepted run`() {
		val manager = Workflow.move(facts("ready", executor = "manager"), "in_progress")
		assertFalse(manager.allowed)
		assertEquals("AI가 실행 담당이면 실행 시도가 수락될 때 수행으로 넘어간다", manager.reason)
		assertFalse(Workflow.move(facts("ready", executor = "shared_ai"), "in_progress").allowed)
		assertTrue(Workflow.move(facts("ready", executor = "human"), "in_progress").allowed)
		// Compat-test work items have no executor; a person may start them.
		assertTrue(Workflow.move(facts("ready", executor = null), "in_progress").allowed)
	}

	@Test
	fun `a live run blocks cancelling and nothing else`() {
		val cancel = Workflow.move(facts("in_progress", liveRun = true), "cancelled")
		assertFalse(cancel.allowed)
		assertEquals("살아 있는 실행 시도를 먼저 멈춘다", cancel.reason)
		assertTrue(Workflow.move(facts("in_progress", liveRun = true), "result_review").allowed)
	}

	@Test
	fun `sending a result back and re-planning need a note`() {
		assertTrue(Workflow.move(facts("result_review"), "in_progress").needsNote)
		assertTrue(Workflow.move(facts("result_review"), "plan_review").needsNote)
		assertTrue(Workflow.move(facts("in_progress"), "plan_review").needsNote)
		assertFalse(Workflow.move(facts("in_progress"), "result_review").needsNote)
		assertFalse(Workflow.move(facts("draft"), "cancelled").needsNote)
	}

	@Test
	fun `a read-only copy refuses every move with its own reason`() {
		val moves = Workflow.moves(facts("in_progress", readOnly = "정본이 Git 파일이다"))
		assertEquals(3, moves.size)
		assertTrue(moves.none { it.allowed })
		assertTrue(moves.all { it.reason == "정본이 Git 파일이다" })
	}

	@Test
	fun `labels cover every stage and wait reason`() {
		for (stage in Workflow.STAGES) assertTrue(Workflow.label(stage) != stage, stage)
		for (reason in Workflow.WAIT_REASONS) assertTrue(Workflow.waitLabel(reason) != reason, reason)
		assertNull(Workflow.move(facts("draft"), "plan_review").reason)
	}
}
