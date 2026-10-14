package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Permissions
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

// Wire format follows contract v1 ("보고 한 건의 모양"): snake_case field names.

data class CreateRun(val executor: String)

data class RunCreated(
	val id: String,
	@JsonProperty("work_item_id") val workItemId: String,
	val state: String,
)

data class PendingRun(
	val id: String,
	@JsonProperty("work_item_id") val workItemId: String,
	val executor: String,
	// Added with the third stage-1 slice; null for a compat-test run, which has no delegation.
	@JsonProperty("delegation_id") val delegationId: String? = null,
	/** The device the delegation names: only it may claim the run. */
	@JsonProperty("device_id") val deviceId: String? = null,
	/** The repository the run works in, so a connector claims only runs for repositories it has. */
	@JsonProperty("repo_key") val repoKey: String? = null,
	/**
	 * Added with contradiction checks: `master_check` for the master's run, which needs no repository
	 * (MasterChecks.kt); null for a work item's own run.
	 */
	val purpose: String? = null,
)

data class ClaimRequest(@JsonProperty("device_id") val deviceId: String)

data class Claimed(
	@JsonProperty("run_id") val runId: String,
	@JsonProperty("work_item_id") val workItemId: String,
	val generation: Long,
	@JsonProperty("lease_seconds") val leaseSeconds: Int,
	// Added with the third stage-1 slice; null for a compat-test run.
	val delegation: ClaimedDelegation? = null,
	/** What the executor is asked to do, built by the server from the work item. */
	val instruction: String? = null,
	/** Added with run control: set when the run continues an earlier one (contract "실행 권리 청구" 1-2). */
	val resume: ClaimedResume? = null,
	/** Added with AI accounts: the account the run goes on and how its executor signs in. Null for a run handed over before accounts. */
	val account: ClaimedAccount? = null,
	/** Added with AI accounts: the run's usage limits; an absent field has no limit. */
	val limits: ClaimedLimits? = null,
	/** Added with contradiction checks: `master_check` for the master's run (no repository, read-only tools). */
	val purpose: String? = null,
)

/**
 * The AI account a run goes on (contract "실행할 때 넘기는 것" 인증). The connector keeps only this
 * sign-in method in the executor's environment and reports the method it used. Never a secret.
 */
data class ClaimedAccount(
	val id: String,
	val name: String,
	/** `subscription_login` or `api_key`. */
	@JsonProperty("auth_method") val authMethod: String,
	/** `device_login` or `env:ANTHROPIC_API_KEY`: where the device keeps the sign-in. */
	@JsonProperty("auth_source") val authSource: String,
)

/**
 * A run's usage limits (FN-0409): turns and the price-list cost go to Claude Code's `--max-turns`
 * and `--max-budget-usd`, minutes to the connector's timer. `budget_from` says whether the budget is
 * the run's own limit or what is left of the account's daily limit.
 */
data class ClaimedLimits(
	@JsonProperty("max_turns") val maxTurns: Int?,
	@JsonProperty("max_budget_usd") val maxBudgetUsd: java.math.BigDecimal?,
	@JsonProperty("max_minutes") val maxMinutes: Int?,
	@JsonProperty("budget_from") val budgetFrom: String?,
)

/**
 * The run a continuing run follows. On the device that ran it, its executor session and worktree,
 * which the connector reuses only after confirming that the earlier executor and its child
 * processes ended (contract "실행마다 별도 작업 트리" 6). Elsewhere neither is sent.
 */
data class ClaimedResume(
	@JsonProperty("run_id") val runId: String,
	@JsonProperty("continue_reason") val continueReason: String,
	@JsonProperty("same_device") val sameDevice: Boolean,
	@JsonProperty("session_id") val sessionId: String?,
	/** `repo_key`, `base_commit`, `branch`, `base_ref` of the earlier run: never a path on the device. */
	val workspace: Map<String, Any?>?,
	/** Added with AI accounts: false when the earlier run went on another account; then neither its session nor its worktree is sent. */
	@JsonProperty("same_account") val sameAccount: Boolean = true,
)

/** A lease renewal (contract "실행 권리 청구" 5), with what became of the instructions the connector took. */
data class LeaseIn(
	@JsonProperty("device_id") val deviceId: String,
	val generation: Long,
	val inputs: List<InputReport> = emptyList(),
)

/** `sent`: written to the executor; `delivered`: the executor took it; `undelivered`: it could not. */
data class InputReport(val id: String, val state: String)

data class LeaseOut(
	/** `claimed`, `running` or `stopping`. */
	val state: String,
	@JsonProperty("lease_seconds") val leaseSeconds: Int,
	@JsonProperty("lease_expires_at") val leaseExpiresAt: OffsetDateTime,
	/** `cancel` when a person asked to stop the run (state `stopping`); null otherwise. */
	val stop: String?,
	/** Instructions a person sent that wait for the executor, oldest first. */
	val inputs: List<PendingInput>,
)

data class PendingInput(val id: String, val text: String)

/** The part of a delegation the connector turns into executor options (contract "실행할 때 넘기는 것"). */
data class ClaimedDelegation(
	val id: String,
	val executor: String,
	/** Null for the master's run, which works in no repository. */
	@JsonProperty("repo_key") val repoKey: String?,
	@JsonProperty("base_branch") val baseBranch: String?,
	@JsonProperty("allowed_tools") val allowedTools: List<String>,
)

data class EventIn(
	@JsonProperty("event_id") val eventId: String,
	val generation: Long,
	val seq: Long,
	val kind: String,
	val payload: Map<String, Any?> = emptyMap(),
	@JsonProperty("occurred_at") val occurredAt: OffsetDateTime? = null,
)

data class EventBatch(
	@JsonProperty("protocol_version") val protocolVersion: Int,
	val events: List<EventIn>,
)

data class EventResult(val stored: Int, val duplicates: Int, val late: Int, val rejected: Int)

@ResponseStatus(HttpStatus.CONFLICT)
class ConflictException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.NOT_FOUND)
class NotFoundException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.BAD_REQUEST)
class BadRequestException(message: String) : RuntimeException(message)

@RestController
@RequestMapping("/api")
class ApiController(private val service: RunService, private val permissions: Permissions) {

	/** The compat test's run: a person asks for it on a work item they may hand over. */
	@PostMapping("/work-items/{id}/runs")
	@ResponseStatus(HttpStatus.CREATED)
	fun createRun(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: CreateRun,
	): RunCreated {
		val me = permissions.person(authorization, "start a compat-test run")
		permissions.require(me, Act.WORK_HANDOFF, permissions.workItem(id))
		return service.createRun(id, body.executor, me.person!!)
	}

	/**
	 * Work poll (contract v1 "전송 경로" 2): runs waiting to be claimed. With `device_id`, only the
	 * delegated runs that device may claim.
	 */
	@GetMapping("/runs")
	fun pendingRuns(
		@RequestParam(defaultValue = "requested") state: String,
		@RequestParam(defaultValue = "20") limit: Int,
		@RequestParam(name = "device_id", required = false) deviceId: String?,
	): List<PendingRun> {
		if (state != "requested") throw BadRequestException("only state=requested can be polled")
		return service.pendingRuns(limit.coerceIn(1, 100), deviceId?.trim()?.ifEmpty { null })
	}

	@PostMapping("/runs/{id}/claim")
	fun claim(@PathVariable id: String, @RequestBody body: ClaimRequest): Claimed =
		service.claim(id, body.deviceId)

	@PostMapping("/runs/{id}/events")
	fun events(@PathVariable id: String, @RequestBody body: EventBatch): EventResult =
		service.ingest(id, body)

	/** The connector renews the lease of a run it holds and hears whether to stop and what to pass on. */
	@PostMapping("/runs/{id}/lease")
	fun renew(@PathVariable id: String, @RequestBody body: LeaseIn): LeaseOut =
		service.renew(id, body)
}
