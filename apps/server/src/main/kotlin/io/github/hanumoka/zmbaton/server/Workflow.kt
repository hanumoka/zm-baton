package io.github.hanumoka.zmbaton.server

/**
 * Work item stages and the moves a person may make between them (contract v1 "작업": "단계",
 * "대기 사유"). Core module 1 ("업무 판정"). Pure: the caller loads the facts and applies the move.
 *
 * The seven stage values are fixed; AI and conflict checks read them. An organisation may skip
 * the two review stages, and the server then passes through them on its own. What the server
 * always checks is only what safety and conflict checks need: a responsible person, a matching
 * `version` (checked by the caller), no move into `in_progress` for an AI executor except by an
 * accepted run, and no cancelling while a run is alive. A delegated run moves its work item on its
 * own (WorkItemService.followRun): started into `in_progress`, submitted on to `result_review`.
 */
object Workflow {

	val STAGES = listOf("draft", "plan_review", "ready", "in_progress", "result_review", "done", "cancelled")
	val SKIPPABLE = setOf("plan_review", "result_review")
	val CLOSED = setOf("done", "cancelled")

	val STAGE_LABELS = mapOf(
		"draft" to "초안", "plan_review" to "계획 검토", "ready" to "수행 준비", "in_progress" to "수행",
		"result_review" to "결과 검토", "done" to "완료", "cancelled" to "취소",
	)

	/**
	 * Contract wait reasons plus `waiting_other` (beyond contract v1): a reason a person writes
	 * down, for what zm-docs calls a blocker.
	 */
	val WAIT_REASONS = setOf(
		"waiting_answer", "waiting_approval", "waiting_predecessor", "waiting_device", "waiting_account",
		"waiting_limit_reset", "billing_stopped", "outcome_unknown", "cancelling", "readiness_failed", "waiting_other",
	)

	/** The wait reasons a person sets and clears by hand. The rest belong to the server's own paths. */
	val PERSON_WAITS = setOf("waiting_other", "waiting_predecessor")

	val WAIT_LABELS = mapOf(
		"waiting_answer" to "질문 답 대기", "waiting_approval" to "승인 대기", "waiting_predecessor" to "선행 작업 대기",
		"waiting_device" to "기기 연결 대기", "waiting_account" to "쓸 수 있는 AI 계정 없음",
		"waiting_limit_reset" to "한도 리셋 대기", "billing_stopped" to "추가 과금으로 멈춤",
		"outcome_unknown" to "결과 확인 필요", "cancelling" to "취소 처리 중", "readiness_failed" to "시작 전 준비 미달",
		"waiting_other" to "다른 사정으로 막힘",
	)

	/** Where each stage may go (contract "단계" transition table), in the order a screen shows them. */
	private val TARGETS = mapOf(
		"draft" to listOf("plan_review", "cancelled"),
		"plan_review" to listOf("ready", "cancelled"),
		"ready" to listOf("in_progress", "cancelled"),
		"in_progress" to listOf("result_review", "plan_review", "cancelled"),
		"result_review" to listOf("done", "in_progress", "plan_review", "cancelled"),
	)

	/** A skipped stage is passed through to the stage after it. */
	private val PASS_TO = mapOf("plan_review" to "ready", "result_review" to "done")

	/** The facts a move depends on, loaded by the caller. */
	data class Facts(
		val stage: String,
		val executorKind: String?,
		val hasLiveRun: Boolean,
		val skippedStages: Set<String>,
		/** Non-null when this copy may not be changed here, e.g. its source of truth is a Git file. */
		val readOnlyReason: String? = null,
	)

	/**
	 * One move a person can ask for. [path] is every stage the work item enters, the last one
	 * where it stays; more than one when it passes through skipped stages.
	 */
	data class Move(
		val target: String,
		val path: List<String>,
		val allowed: Boolean,
		/** Why it is not allowed, in words a person reads on the screen. */
		val reason: String?,
		/** The move needs a note, e.g. why a result goes back. */
		val needsNote: Boolean,
	) {
		val finalStage: String get() = path.last()
	}

	fun moves(facts: Facts): List<Move> = TARGETS[facts.stage].orEmpty().map { move(facts, it) }

	/** The move to [target], or a not-allowed move when [target] is not reachable from here. */
	fun move(facts: Facts, target: String): Move {
		val path = path(target, facts.skippedStages)
		val needsNote = facts.stage == "result_review" && target == "in_progress" ||
			facts.stage in setOf("in_progress", "result_review") && target == "plan_review"
		val reason = when {
			facts.readOnlyReason != null -> facts.readOnlyReason
			target !in TARGETS[facts.stage].orEmpty() ->
				"${label(facts.stage)}에서 ${label(target)}(으)로는 넘어갈 수 없다"
			target == "in_progress" && facts.stage == "ready" && facts.executorKind !in setOf(null, "human") ->
				"AI가 실행 담당이면 실행 시도가 수락될 때 수행으로 넘어간다"
			target == "cancelled" && facts.hasLiveRun -> "살아 있는 실행 시도를 먼저 멈춘다"
			else -> null
		}
		return Move(target, path, reason == null, reason, needsNote)
	}

	/**
	 * Why this work item cannot be handed to an AI now, or null when it can (contract "실행 권리
	 * 청구" 1). Stage 1 hands over only the first run of a `ready` work item. Continuing an
	 * `in_progress` one (an answer arrived, a result was sent back, a cut run resumes) comes with
	 * resuming. A Git copy is refused: a run moves its stage, and only an import changes that copy.
	 */
	fun delegationRefusal(facts: Facts): String? = when {
		facts.readOnlyReason != null ->
			"${facts.readOnlyReason}. AI가 실행하면 단계가 바뀌므로 이 복사본은 맡길 수 없다. 「새 작업」으로 만들어 맡긴다"
		facts.hasLiveRun -> "살아 있는 실행 시도가 있다. 끝나거나 거둔 뒤에 다시 맡긴다"
		facts.stage == "ready" -> null
		facts.stage in CLOSED -> "${label(facts.stage)}된 작업은 맡길 수 없다"
		facts.stage in setOf("in_progress", "result_review") ->
			"${label(facts.stage)} 단계다. 이어서 맡기기(반려·끊김 뒤 다시 실행)는 아직 없다. 「다시 계획하기」로 수행 준비까지 돌린 뒤 맡긴다"
		else -> "${label(facts.stage)} 단계다. 수행 준비로 옮긴 뒤에 맡긴다"
	}

	private fun path(target: String, skipped: Set<String>): List<String> {
		val path = mutableListOf(target)
		var stage = target
		while (stage in skipped && stage in PASS_TO) {
			stage = PASS_TO.getValue(stage)
			path += stage
		}
		return path
	}

	fun label(stage: String): String = STAGE_LABELS[stage] ?: stage

	fun waitLabel(reason: String): String = WAIT_LABELS[reason] ?: reason
}
