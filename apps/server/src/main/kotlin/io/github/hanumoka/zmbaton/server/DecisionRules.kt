package io.github.hanumoka.zmbaton.server

/**
 * What a person may do with a decision record (FN-0604) and why not. Core module 1 ("업무 판정:
 * 상태 전이·권한·승인"). Pure, like [Workflow]: the caller loads the facts, asks, and applies.
 *
 * The four statuses are zm-docs's (V3). A decision made here starts `proposed`, may be edited
 * only while proposed, and leaves that status once: its approver accepts or rejects it. An
 * accepted decision is never edited; a later decision that names it in `supersedes` replaces it
 * when that later one is accepted. A copy whose source of truth is a Git file changes only there.
 */
object DecisionRules {

	const val EDIT = "edit"
	const val ACCEPT = "accept"
	const val REJECT = "reject"
	const val SUPERSEDE = "supersede"

	/** In the order a screen shows them. */
	val ACTIONS = listOf(EDIT, ACCEPT, REJECT, SUPERSEDE)

	/** Why a decision that has left `proposed` cannot be accepted or rejected again. */
	private val DONE = mapOf(
		"accepted" to "이미 채택한 결정이다", "rejected" to "이미 기각한 결정이다", "superseded" to "이미 대체된 결정이다",
	)

	/** The facts the rules depend on, loaded by the caller. */
	data class Facts(
		val status: String,
		val sourceOfTruth: String,
		/** The Git file of a copy, for the reason a person reads. */
		val gitPath: String?,
		/** A chosen option or the decision's own text: there is something to adopt. */
		val hasChoice: Boolean,
		/** Who may accept or reject it ([ApprovalPolicy.approverFor]). */
		val approver: String,
	)

	data class Action(val name: String, val allowed: Boolean, val reason: String?)

	fun actions(facts: Facts, actor: String): List<Action> = ACTIONS.map { action(facts, actor, it) }

	fun action(facts: Facts, actor: String, name: String): Action {
		val reason = when {
			facts.sourceOfTruth == "git" -> gitReason(facts.gitPath)
			name == EDIT -> when (facts.status) {
				"proposed" -> null
				"accepted" -> "채택한 결정은 고치지 않는다. 바꾸려면 새 결정으로 대체한다"
				"rejected" -> "기각한 결정은 고치지 않는다. 다시 정하려면 새 결정을 제안한다"
				else -> "대체된 결정은 고치지 않는다"
			}
			name == ACCEPT || name == REJECT -> when {
				facts.status != "proposed" -> DONE.getValue(facts.status)
				actor != facts.approver -> "승인자(${ApprovalPolicy.label(facts.approver)})만 채택하거나 기각한다"
				name == ACCEPT && !facts.hasChoice -> "고른 안이나 결정 내용을 적어야 채택한다"
				else -> null
			}
			name == SUPERSEDE -> when (facts.status) {
				"accepted" -> null
				"proposed" -> "제안 상태의 결정은 대체하지 않고 고친다"
				"rejected" -> "기각한 결정은 대체할 것이 없다"
				else -> "이미 다른 결정이 대체했다"
			}
			else -> "모르는 동작이다: $name"
		}
		return Action(name, reason == null, reason)
	}

	fun gitReason(path: String?): String = "정본이 Git 파일(${path ?: "?"})이다. 원본에서 고치고 다시 가져온다"
}
