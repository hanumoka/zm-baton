package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.auth.Requester
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Permissions
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

// A person's control over a run in progress (FN-0410, contract v1 "실행 시도 > 상태" 중단 원인
// 「사용자 중단」, "어댑터가 알려야 하는 능력" 실행 중 입력): stop it, or send its executor a short
// instruction. The server only records the request; the connector holding the run hears it at its
// next lease renewal (RunService.renew), stops the executor or writes the instruction to it, and
// reports how that went. A run whose connector went silent is ended by [RunSweeper].

data class CancelRunIn(val version: Long)

data class RunInputIn(val text: String)

data class RunInputView(
	val id: String,
	val text: String,
	/** `pending`, `sent`, `delivered` or `undelivered`. */
	val state: String,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("sent_at") val sentAt: OffsetDateTime?,
	@JsonProperty("delivered_at") val deliveredAt: OffsetDateTime?,
)

data class ActionAvailability(val allowed: Boolean, val reason: String?)

/** What a person may do with a run that has not ended, each with the reason when not. */
data class RunActions(
	val cancel: ActionAvailability,
	@JsonProperty("send_input") val sendInput: ActionAvailability,
)

/** Which control a run offers now. Pure; the screen draws what this says and decides nothing. */
object RunActionRules {
	const val INPUT_MAX = 2000
	private val OK = ActionAvailability(true, null)

	private fun no(reason: String) = ActionAvailability(false, reason)

	/** Null for an ended run: nothing to control. [liveInput] is what the adapter reported at start. */
	fun of(state: String, delegated: Boolean, liveInput: Boolean): RunActions? {
		val cancel = when (state) {
			"requested" -> if (delegated) OK else no("실행 시험으로 만든 실행은 여기서 거두지 않는다")
			"claimed", "running" -> OK
			"stopping" -> no("이미 취소를 요청했다. 연결 프로그램이 실행기를 멈추고 보고하기를 기다린다")
			else -> return null
		}
		val input = when {
			state == "requested" || state == "claimed" -> no("실행기가 아직 시작되지 않았다. 시작한 뒤에 보낸다")
			state == "stopping" -> no("취소를 요청한 실행이다")
			!liveInput ->
				no("이 실행의 연결 프로그램은 실행 중 지시를 받지 않는다. zm-baton을 새 판으로 바꾸거나, 실행을 취소한 뒤 지시를 덧붙여 이어서 맡긴다")
			else -> OK
		}
		return RunActions(cancel, input)
	}
}

@Service
class RunControlService(
	private val jdbc: JdbcClient,
	private val delegations: DelegationService,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
) {

	/**
	 * A person stops a run (contract "실행 시도 > 상태" 사용자 중단). A run no connector took yet is
	 * taken back at once (DelegationService.withdraw). A claimed or running one becomes `stopping`
	 * and its delegation is revoked (contract "위임": no new claims or external actions); the
	 * connector stops the executor at its next renewal and reports `cancelled`. Its worktree and
	 * branch stay. The work item keeps its stage; the end report decides `outcome_unknown`.
	 */
	@Transactional
	fun cancel(runId: String, version: Long, actor: String = Requester.STAGE1_PERSON): WorkItemDetail {
		// The run first, then the work item: the same lock order as a report (RunService.ingest).
		val (workItemId, delegationId, state) = jdbc.sql("select work_item_id, delegation_id, state from runs where id = :id for update")
			.param("id", runId).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.optional()
			.orElseThrow { NotFoundException("no run $runId") }
		if (state == "requested") return delegations.withdraw(runId, version)
		val actions = RunActionRules.of(state, delegationId != null, liveInput = false)
			?: throw ConflictException("이미 끝난 실행이다")
		if (!actions.cancel.allowed) throw ConflictException(actions.cancel.reason ?: "취소할 수 없다")
		workItems.lockFacts(workItemId, version)
		jdbc.sql("update runs set state = 'stopping' where id = :id and state in ('claimed', 'running')").param("id", runId).update()
		if (delegationId != null) {
			jdbc.sql("update delegations set revoked_at = now() where id = :id and revoked_at is null").param("id", delegationId).update()
		}
		jdbc.sql("update work_items set version = version + 1, updated_at = now() where id = :id").param("id", workItemId).update()
		workItems.record(workItemId, "work_item.run_cancel_requested", mapOf("run_id" to runId, "state" to state), actor)
		return workItems.detail(workItemId)
	}

	/**
	 * A person sends a short instruction to a running executor that takes input while it runs
	 * (contract "어댑터가 알려야 하는 능력": Claude Code takes it at its next turn). It waits for the
	 * connector's next renewal; whether the executor took it shows as its state.
	 */
	@Transactional
	fun sendInput(runId: String, body: RunInputIn, actor: String = Requester.STAGE1_PERSON): RunInputView {
		val text = body.text.trim()
		if (text.isEmpty() || text.length > RunActionRules.INPUT_MAX) {
			throw BadRequestException("text must be 1-${RunActionRules.INPUT_MAX} characters")
		}
		val run = jdbc.sql(
			"""
			select r.work_item_id, r.state, r.delegation_id,
			       exists (select 1 from run_events e where e.run_id = r.id and e.kind = 'lifecycle'
			                  and e.payload ->> 'phase' = 'started'
			                  and (e.payload -> 'capabilities' ->> 'live_input') = 'true') as live_input
			  from runs r where r.id = :id for update of r
			""".trimIndent(),
		).param("id", runId).query { rs, _ -> LiveRun(rs.getString(1), rs.getString(2), rs.getString(3) != null, rs.getBoolean(4)) }
			.optional().orElseThrow { NotFoundException("no run $runId") }
		val workItemId = run.workItemId
		val actions = RunActionRules.of(run.state, run.delegated, run.liveInput) ?: throw ConflictException("이미 끝난 실행이다")
		if (!actions.sendInput.allowed) throw ConflictException(actions.sendInput.reason ?: "지시를 보낼 수 없다")
		val id = Ids.input()
		jdbc.sql("insert into run_inputs (id, run_id, body, created_by) values (:id, :run, :body, :by)")
			.param("id", id).param("run", runId).param("body", text).param("by", actor).update()
		sessions.audit("person", actor, "run.input_sent", "wrk", workItemId,
			mapOf("run_id" to runId, "input_id" to id, "text" to text.take(200)))
		return jdbc.sql("select id, body, state, created_at, sent_at, delivered_at from run_inputs where id = :id").param("id", id)
			.query { rs, _ -> inputView(rs) }.single()
	}

	private data class LiveRun(val workItemId: String, val state: String, val delegated: Boolean, val liveInput: Boolean)

	companion object {
		fun inputView(rs: java.sql.ResultSet) = RunInputView(
			rs.getString("id"), rs.getString("body"), rs.getString("state"),
			rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("sent_at", OffsetDateTime::class.java),
			rs.getObject("delivered_at", OffsetDateTime::class.java),
		)
	}
}

@RestController
@RequestMapping("/api")
class RunControlController(private val control: RunControlService, private val permissions: Permissions) {

	@PostMapping("/runs/{id}/cancel")
	fun cancel(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: CancelRunIn,
	): WorkItemDetail {
		val me = permissions.person(authorization, "cancel a run")
		permissions.require(me, Act.WORK_HANDOFF, permissions.run(id))
		return control.cancel(id, body.version, me.person!!)
	}

	@PostMapping("/runs/{id}/inputs")
	@ResponseStatus(HttpStatus.CREATED)
	fun sendInput(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: RunInputIn,
	): RunInputView {
		val me = permissions.person(authorization, "send an instruction to a run")
		permissions.require(me, Act.WORK_HANDOFF, permissions.run(id))
		return control.sendInput(id, body, me.person!!)
	}
}

/**
 * Ends runs whose connector stopped renewing their lease (contract "실행 권리 청구" 6). Runs inside
 * the server, like the session sweep.
 */
@Component
class RunSweeper(private val runs: RunService) {
	@Scheduled(fixedDelayString = "\${zm-baton.run-sweep-ms:15000}", initialDelayString = "\${zm-baton.run-sweep-ms:15000}")
	fun sweep() {
		runs.sweepLost()
	}
}
