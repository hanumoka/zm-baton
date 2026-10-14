package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** FN-0604: what a person may do with a decision, without a database. */
class DecisionRulesTests {

	private fun facts(status: String, source: String = "product", hasChoice: Boolean = true, approver: String = "usr_owner") =
		DecisionRules.Facts(status, source, "docs/governance/decisions/DEC-0001-x.md", hasChoice, approver)

	private fun allowed(facts: DecisionRules.Facts, actor: String = "usr_owner") =
		DecisionRules.actions(facts, actor).filter { it.allowed }.map { it.name }

	@Test
	fun `each status allows its own actions`() {
		assertEquals(listOf("edit", "accept", "reject"), allowed(facts("proposed")))
		assertEquals(listOf("supersede"), allowed(facts("accepted")))
		assertEquals(emptyList(), allowed(facts("rejected")))
		assertEquals(emptyList(), allowed(facts("superseded")))
	}

	@Test
	fun `a Git copy allows nothing and every action says where to change it`() {
		for (status in listOf("proposed", "accepted", "rejected", "superseded")) {
			val actions = DecisionRules.actions(facts(status, source = "git"), "usr_owner")
			assertTrue(actions.none { it.allowed }, status)
			assertTrue(actions.all { it.reason == DecisionRules.gitReason("docs/governance/decisions/DEC-0001-x.md") }, status)
		}
	}

	@Test
	fun `only the approver accepts or rejects, and accepting needs something chosen`() {
		val other = DecisionRules.actions(facts("proposed"), "usr_other").associateBy { it.name }
		assertTrue(other.getValue("edit").allowed)
		assertFalse(other.getValue("accept").allowed)
		assertEquals("승인자(소유자(usr_owner))만 채택하거나 기각한다", other.getValue("reject").reason)
		val empty = DecisionRules.action(facts("proposed", hasChoice = false), "usr_owner", DecisionRules.ACCEPT)
		assertEquals("고른 안이나 결정 내용을 적어야 채택한다", empty.reason)
		assertNull(DecisionRules.action(facts("proposed", hasChoice = false), "usr_owner", DecisionRules.REJECT).reason)
	}

	@Test
	fun `a decided decision says why it cannot be judged or edited again`() {
		assertEquals("이미 채택한 결정이다", DecisionRules.action(facts("accepted"), "usr_owner", DecisionRules.ACCEPT).reason)
		assertEquals("이미 대체된 결정이다", DecisionRules.action(facts("superseded"), "usr_owner", DecisionRules.REJECT).reason)
		assertEquals("이미 다른 결정이 대체했다", DecisionRules.action(facts("superseded"), "usr_owner", DecisionRules.SUPERSEDE).reason)
		assertEquals("제안 상태의 결정은 대체하지 않고 고친다", DecisionRules.action(facts("proposed"), "usr_owner", DecisionRules.SUPERSEDE).reason)
	}

	@Test
	fun `the stage 1 approver is the owner, in one place`() {
		assertEquals(SessionService.OWNER, ApprovalPolicy.approverFor(WorkItemService.ORG, "decision"))
		assertEquals("stage1.owner_approves", ApprovalPolicy.POLICY)
	}
}
