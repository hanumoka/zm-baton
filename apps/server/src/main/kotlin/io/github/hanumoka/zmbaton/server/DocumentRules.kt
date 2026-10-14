package io.github.hanumoka.zmbaton.server

/**
 * What a person may do with a document and its versions (FN-0601, FN-0602), and why not. Core
 * module 1 ("업무 판정: 상태 전이·권한·승인"). Pure, like [DecisionRules].
 *
 * A document has one source of truth. A product document's body is written here, one version at a
 * time. A Git document's body stays in its repository: zm-baton only records which commit a version
 * points at, and a change to the text is made in Git. Every version starts as a draft and its
 * approver may approve it once; a newer version is a new draft and leaves the older approval alone.
 */
object DocumentRules {

	const val DRAFT = "draft"
	const val APPROVED = "approved"

	/** The state of a version: approved once someone approved it, a draft before. */
	fun state(approvedBy: String?): String = if (approvedBy == null) DRAFT else APPROVED

	data class Action(val allowed: Boolean, val reason: String?)

	/** Whether [actor] may approve a version in [state] whose approver is [approver]. */
	fun approve(state: String, approver: String, actor: String): Action {
		val reason = when {
			state == APPROVED -> "이미 승인한 판이다"
			actor != approver -> "승인자(${ApprovalPolicy.label(approver)})만 승인한다"
			else -> null
		}
		return Action(reason == null, reason)
	}

	/** Why the body of a Git document is not written here, for the screen to show. */
	fun gitReason(path: String?): String =
		"본문은 Git 파일(${path ?: "?"})이 정본이다. Git에서 고치고 커밋한 뒤 그 커밋을 새 판으로 기록한다"
}
