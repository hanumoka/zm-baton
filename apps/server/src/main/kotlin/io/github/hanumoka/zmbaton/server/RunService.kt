package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.Requester
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Claim and report handling from contract v1 ("실행 권리 청구", "세대 번호로 늦은 보고 거르기",
 * "보고 전달", "실행이 끊겼을 때"). A delegated run (DelegationService) is claimable only by the
 * device it names, and its reports move its work item (WorkItemService.followRun). The connector
 * renews the claim's lease while it runs; its renewal also carries a person's stop request and
 * instructions to the connector. A run whose lease lapsed longer than the reconnection grace ends
 * `lost` ([sweepLost]). A run on an AI account gets that account's sign-in method and usage limits
 * with its claim, is not started while the account is stopped, and its usage reports and limit
 * signals go to UsageService. request_id dedup is not built yet.
 */
@Service
class RunService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
	/** Pins a submitted result and picks who must see it (FN-0411). */
	private val reviews: ReviewService,
	/** Settles the questions whose answers a continuing run carried (FN-0210). */
	private val questions: QuestionService,
	private val usage: UsageService,
	/** The master's own run limits, narrower than an account's (MasterChecks.kt). */
	private val master: MasterSettings,
	transactions: PlatformTransactionManager,
	/** Contract "실행 권리 청구" 6: how long a lapsed lease may stay silent before the run is lost. */
	@Value("\${zm-baton.run-lost-grace-seconds:300}") private val lostGraceSeconds: Long,
) {

	companion object {
		/** Contract "실행 권리 청구" 5: a lease lasts 45 seconds; the connector renews it while it runs. */
		const val LEASE_SECONDS = 45
		/** Executor activity (contract "공통 활동 종류"). Only these count for 응답 없음 (`last_activity_at`). */
		val ACTIVITY_KINDS = setOf("thought", "action", "action_result", "answer", "question", "error", "usage")
		/** protocol_version 1, which the compat-test connector still sends. */
		val KINDS = ACTIVITY_KINDS + "lifecycle"
		/** protocol_version 2 adds the Git change list ("보고 한 건의 모양"). */
		val KINDS_V2 = KINDS + "git_changes"
		val PROTOCOL_VERSIONS = setOf(1, 2)
		val END_REASONS = setOf("submitted", "failed", "cancelled", "replaced", "lost")
		val LIVE_STATES = setOf("claimed", "running", "stopping")
		/** How a run's worktree came to be: its own new one, the previous run's, or one from the previous run's branch. */
		val WORKSPACE_ORIGINS = setOf("new", "reused", "previous_branch")
		val INPUT_REPORTS = setOf("sent", "delivered", "undelivered")
		const val MAX_CHANGED_FILES = 2000
		private val COMMIT = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
		/** A drive, a backslash or a word starting with `/`: what an absolute path on a device looks like. */
		private val DEVICE_PATH = Regex("""[A-Za-z]:[\\/]|\\|(^|\s)[/~]""")
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val tx = TransactionTemplate(transactions)

	/**
	 * When this server started. No connector could renew while the server was down, so a lease
	 * counts as lapsed only from this time on: an update or a restore never cuts runs whose
	 * connectors are still there. Tests set it to look like a recent start.
	 */
	internal var startedAt: OffsetDateTime = OffsetDateTime.now()

	/** The compat test's work item: `ready`, no executor. */
	fun createWorkItem(title: String, responsibleUserId: String): WorkItemCreated =
		workItems.create(CreateWorkItem(title, responsibleUserId))

	/** The compat test's run: no delegation, any connector may claim it. [actor] asked for it. */
	@Transactional
	fun createRun(workItemId: String, executor: String, actor: String = Requester.STAGE1_PERSON): RunCreated {
		val id = Ids.run()
		val inserted = try {
			jdbc.sql(
				"""
				insert into runs (id, work_item_id, state, executor)
				select :id, w.id, 'requested', :executor from work_items w where w.id = :wid and w.stage = 'ready'
				""".trimIndent(),
			).param("id", id).param("executor", executor).param("wid", workItemId).update()
		} catch (e: DuplicateKeyException) {
			throw ConflictException("work item $workItemId already has a live run")
		}
		if (inserted == 0) throw NotFoundException("no ready work item $workItemId")
		sessions.audit("person", actor, "run.requested", "run", id, mapOf("work_item_id" to workItemId, "executor" to executor))
		return RunCreated(id, workItemId, "requested")
	}

	/**
	 * Runs waiting to be claimed, oldest first. With [deviceId], only the delegated runs that device
	 * may claim now; without it every requested run, compat-test runs included.
	 */
	fun pendingRuns(limit: Int, deviceId: String? = null): List<PendingRun> {
		val forDevice = if (deviceId == null) "" else
			" and d.device_id = :device and d.revoked_at is null and d.expires_at > now()"
		val stmt = jdbc.sql(
			"""
			select r.id, r.work_item_id, r.executor, r.delegation_id, d.device_id, d.scope ->> 'repo_key', d.scope ->> 'purpose'
			  from runs r left join delegations d on d.id = r.delegation_id
			 where r.state = 'requested'$forDevice
			 order by r.created_at limit :limit
			""".trimIndent(),
		).param("limit", limit)
		if (deviceId != null) stmt.param("device", deviceId)
		return stmt.query { rs, _ ->
			PendingRun(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7))
		}.list()
	}

	/**
	 * One conditional update; locked rows are skipped, so concurrent claims get exactly one winner.
	 * A delegated run goes only to the device its delegation names, while the delegation is neither
	 * revoked nor expired (contract "위임"). A continuing run also says which run it follows, and on
	 * the same device which executor session and worktree that run left ("실행이 끊겼을 때" 2).
	 * A run whose AI account is stopped now (UsageService.block) is ended before any executor starts and
	 * the claim is refused; that ending stays, so it commits in its own transaction first.
	 */
	fun claim(runId: String, deviceId: String): Claimed {
		if (deviceId.isBlank()) throw BadRequestException("device_id is required")
		val refused = tx.execute { usage.refuseClaim(runId, deviceId.trim()) }
		if (refused != null) throw ConflictException(refused)
		return tx.execute { claimNow(runId, deviceId) }!!
	}

	private fun claimNow(runId: String, deviceId: String): Claimed {
		val won = jdbc.sql(
			"""
			update runs
			   set state = 'claimed', device_id = :device, claimed_at = now(),
			       lease_expires_at = now() + make_interval(secs => :lease)
			 where id = (select r.id from runs r left join delegations d on d.id = r.delegation_id
			              where r.id = :runId and r.state = 'requested'
			                and (r.delegation_id is null
			                     or (d.device_id = :device and d.revoked_at is null and d.expires_at > now()))
			                for update of r skip locked)
			returning work_item_id, delegation_id, instruction, resumed_from_run_id, continue_reason, account_id
			""".trimIndent(),
		).param("device", deviceId).param("lease", LEASE_SECONDS).param("runId", runId)
			.query { rs, _ -> Won(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) }
			.optional()
			.orElseThrow { ConflictException("run $runId is not claimable") }
		val (workItemId, delegationId, instruction, resumedFrom, continueReason, accountId) = won
		val generation = jdbc.sql(
			"update work_items set generation = generation + 1, updated_at = now() where id = :wid returning generation",
		).param("wid", workItemId).query(Long::class.javaObjectType).single()
		jdbc.sql("update runs set generation = :gen where id = :runId")
			.param("gen", generation).param("runId", runId).update()
		// The device's local connector claimed it: no person or session, so the actor is the device.
		sessions.audit("system", deviceId, "run.claimed", "run", runId,
			mapOf("work_item_id" to workItemId, "delegation_id" to delegationId, "generation" to generation))
		jdbc.sql("update devices set last_seen_at = now() where id = :id").param("id", deviceId).update()
		val scope = delegationId?.let { id ->
			jdbc.sql("select scope::text from delegations where id = :id").param("id", id)
				.query(String::class.java).single().let { json.readValue(it, mapType) }
		}
		val delegation = scope?.let {
			ClaimedDelegation(
				delegationId, it["executor"] as String, it["repo_key"] as String?, it["base_branch"] as String?,
				(it["allowed_tools"] as List<*>).map { t -> t as String },
			)
		}
		// The master's run (MasterChecks.kt) works in no repository and gets the master's smaller limits.
		val purpose = scope?.get("purpose") as String?
		val resume = resumedFrom?.let { previousForClaim(it, deviceId, accountId, continueReason ?: "resume_after_cut") }
		// The account's sign-in method and the limits this run gets now (UsageService.limitsForClaim).
		val acc = accountId?.let { usage.account(it) }
		val limits = acc?.let { usage.limitsForClaim(it) }?.let { if (purpose == MasterCheckService.PURPOSE) master.cap(it) else it }
		if (limits != null) {
			jdbc.sql("update runs set limits = cast(:l as jsonb) where id = :id").param("l", json.writeValueAsString(limits)).param("id", runId).update()
		}
		val account = acc?.let { ClaimedAccount(it.id, it.name, UsageService.authMethod(it.authKind), it.authSource) }
		return Claimed(runId, workItemId, generation, LEASE_SECONDS, delegation, instruction, resume, account, limits?.let(::claimedLimits), purpose)
	}

	private fun claimedLimits(l: Map<String, Any?>) = ClaimedLimits(
		(l["max_turns"] as? Number)?.toInt(), (l["max_budget_usd"] as? Number)?.let { java.math.BigDecimal(it.toString()) }, (l["max_minutes"] as? Number)?.toInt(),
		l["budget_from"] as? String,
	)

	private data class Won(
		val workItemId: String,
		val delegationId: String?,
		val instruction: String?,
		val resumedFrom: String?,
		val continueReason: String?,
		val accountId: String?,
	)

	/**
	 * What a continuing run may reuse (contract "실행마다 별도 작업 트리" 6). Only on the device that
	 * ran the previous run, on the same account: there its executor session and branch exist, signed in
	 * the same way. The connector still reuses them only after it confirms that the previous executor
	 * and its child processes ended. Another account gets neither ("실행마다 별도 작업 트리" 6.2).
	 */
	private fun previousForClaim(previousId: String, deviceId: String, accountId: String?, continueReason: String): ClaimedResume =
		jdbc.sql("select device_id, executor_session_id, workspace::text, account_id from runs where id = :id").param("id", previousId)
			.query { rs, _ ->
				val sameDevice = rs.getString(1) == deviceId
				val sameAccount = rs.getString(4) == accountId
				val reusable = sameDevice && sameAccount
				ClaimedResume(
					previousId, continueReason, sameDevice,
					if (reusable) rs.getString(2) else null,
					if (reusable) rs.getString(3)?.let { json.readValue(it, mapType) } else null,
					sameAccount,
				)
			}.single()

	/**
	 * The connector renews the lease of a run it holds (contract "실행 권리 청구" 5) and hears back
	 * whether a person asked to stop it and which instructions wait for the executor. It also says
	 * what became of the instructions it took. A run that ended, was claimed again or is held by
	 * another device is refused: the connector must stop working on it.
	 */
	@Transactional
	fun renew(runId: String, body: LeaseIn): LeaseOut {
		val row = jdbc.sql(
			"""
			update runs set lease_expires_at = now() + make_interval(secs => :lease)
			 where id = :id and device_id = :device and generation = :gen and state in ('claimed', 'running', 'stopping')
			returning state, lease_expires_at
			""".trimIndent(),
		).param("lease", LEASE_SECONDS).param("id", runId).param("device", body.deviceId.trim()).param("gen", body.generation)
			.query { rs, _ -> rs.getString(1) to rs.getObject(2, OffsetDateTime::class.java) }.optional()
			.orElseThrow { ConflictException("run $runId is not held by this device with generation ${body.generation} any more") }
		jdbc.sql("update devices set last_seen_at = now() where id = :id").param("id", body.deviceId.trim()).update()
		for (ack in body.inputs) applyInputReport(runId, ack.id, ack.state)
		val pending = jdbc.sql("select id, body from run_inputs where run_id = :id and state = 'pending' order by created_at, id")
			.param("id", runId).query { rs, _ -> PendingInput(rs.getString(1), rs.getString(2)) }.list()
		val (state, expires) = row
		return LeaseOut(state, LEASE_SECONDS, expires, if (state == "stopping") "cancel" else null, pending)
	}

	/**
	 * Contract "실행 권리 청구" 6 and "실행이 끊겼을 때" 1: a live run whose lease lapsed more than the
	 * reconnection grace ago ends `lost`, and its work item gets `outcome_unknown` (the connector may
	 * have been changing files). The run's own lease renewal is the liveness signal: the device's
	 * other traffic (its directly opened sessions) says nothing about this connector. Each run is
	 * judged in its own transaction under the same locks as a report, so a late report and the
	 * judgement never interleave. Returns the runs it ended.
	 */
	fun sweepLost(): List<String> {
		val candidates = jdbc.sql(
			"""
			select id from runs
			 where state in ('claimed', 'running', 'stopping')
			   and greatest(lease_expires_at, cast(:started as timestamptz)) < now() - make_interval(secs => :grace)
			 order by lease_expires_at limit 100
			""".trimIndent(),
		).param("grace", lostGraceSeconds).param("started", startedAt).query(String::class.java).list().filterNotNull()
		return candidates.filter { id -> tx.execute { loseIfLapsed(id) } == true }
	}

	private fun loseIfLapsed(runId: String): Boolean {
		val workItemId = jdbc.sql(
			"""
			select r.work_item_id from runs r join work_items w on w.id = r.work_item_id
			 where r.id = :id and r.state in ('claimed', 'running', 'stopping')
			   and greatest(r.lease_expires_at, cast(:started as timestamptz)) < now() - make_interval(secs => :grace)
			   for update of r, w
			""".trimIndent(),
		).param("id", runId).param("grace", lostGraceSeconds).param("started", startedAt).query(String::class.java)
			.optional().orElse(null) ?: return false
		jdbc.sql("update runs set state = 'ended', end_reason = 'lost', ended_at = now(), lease_expires_at = null where id = :id")
			.param("id", runId).update()
		addWait(workItemId, "outcome_unknown")
		finishInputs(runId)
		sessions.audit("system", runId, "run.ended", "run", runId,
			mapOf("work_item_id" to workItemId, "end_reason" to "lost", "outcome_unknown" to true))
		sessions.audit("system", runId, "run.lost", "wrk", workItemId,
			mapOf("run_id" to runId, "reason" to "lease lapsed for more than $lostGraceSeconds seconds"))
		return true
	}

	@Transactional
	fun ingest(runId: String, batch: EventBatch): EventResult {
		val version = batch.protocolVersion
		if (version !in PROTOCOL_VERSIONS) throw BadRequestException("unsupported protocol_version $version")
		// Lock the run and its work item: batches for one run apply in order, and a concurrent claim
		// cannot bump the generation between this read and the late-report decision.
		val target = jdbc.sql(
			"""
			select r.work_item_id, r.generation as run_generation, w.generation as current_generation,
			       r.delegation_id, d.scope ->> 'repo_key' as repo_key, r.account_id
			  from runs r join work_items w on w.id = r.work_item_id
			  left join delegations d on d.id = r.delegation_id
			 where r.id = :runId
			   for update of r, w
			""".trimIndent(),
		).param("runId", runId)
			.query { rs, _ ->
				ReportTarget(rs.getString(1), rs.getObject(2) as Long?, rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6))
			}
			.optional()
			.orElseThrow { NotFoundException("no run $runId") }
		// A report carries the generation its run got at claim ("generation" in "보고 한 건의 모양").
		// A run that was never claimed has none, so nothing can be reported for it.
		val runGeneration = target.runGeneration
		val kinds = if (version == 1) KINDS else KINDS_V2

		var stored = 0
		var duplicates = 0
		var late = 0
		var rejected = 0
		for (event in batch.events.sortedBy { it.seq }) {
			if (event.kind !in kinds || runGeneration == null || event.generation != runGeneration ||
				!valid(event, version, target)
			) {
				rejected++
				continue
			}
			val inserted = jdbc.sql(
				"""
				insert into run_events (event_id, run_id, generation, seq, kind, payload, occurred_at)
				values (:eventId, :runId, :gen, :seq, :kind, cast(:payload as jsonb), :occurredAt)
				on conflict do nothing
				""".trimIndent(),
			).param("eventId", event.eventId).param("runId", runId).param("gen", event.generation)
				.param("seq", event.seq).param("kind", event.kind)
				.param("payload", json.writeValueAsString(event.payload))
				.param("occurredAt", event.occurredAt)
				.update()
			if (inserted == 0) {
				val known = jdbc.sql("select count(*) from run_events where event_id = :eventId")
					.param("eventId", event.eventId).query(Long::class.javaObjectType).single() > 0
				if (known) duplicates++ else rejected++ // same seq under a different event_id
				continue
			}
			stored++
			if (event.generation < target.currentGeneration) {
				// Late report from an older generation: keep the event, never change state.
				late++
				jdbc.sql("update runs set late_reports = true where id = :runId").param("runId", runId).update()
				continue
			}
			jdbc.sql(
				"""
				update runs set last_event_at = now(),
				       last_activity_at = case when :activity then now() else last_activity_at end
				 where id = :runId
				""".trimIndent(),
			).param("activity", event.kind in ACTIVITY_KINDS).param("runId", runId).update()
			when (event.kind) {
				"lifecycle" -> applyLifecycle(runId, target, event.payload)
				"usage" -> usage.onUsage(runId, target.workItemId, target.accountId, event.payload)
				// Only an error the executor's result carried, never the model's words (billing-signals).
				"error" -> if (event.payload["source"] == "result") usage.onResultError(runId, target.workItemId, target.accountId, event.payload)
			}
		}
		return EventResult(stored, duplicates, late, rejected)
	}

	private data class ReportTarget(
		val workItemId: String,
		val runGeneration: Long?,
		val currentGeneration: Long,
		val delegationId: String?,
		/** The repository the run's delegation names; a reported workspace must be in it. */
		val repoKey: String?,
		/** The AI account the run goes on; null for a compat-test run or one handed over before accounts. */
		val accountId: String?,
	)

	/**
	 * A lifecycle event must be `started`, or `ended` with a contract end reason; from
	 * protocol_version 2 an end also says whether the executor started and whether its result
	 * arrived. Never infer success. A Git change list holds only paths from the repository root.
	 * The optional parts added with run control (an end's readiness check and instruction states, a
	 * start's capabilities) must have their shape when present.
	 */
	private fun valid(event: EventIn, version: Int, target: ReportTarget): Boolean {
		val p = event.payload
		return when (event.kind) {
			"lifecycle" -> validWorkspace(p["workspace"], target) && when (p["phase"]) {
				"started" -> validCapabilities(p["capabilities"])
				"ended" -> p["end_reason"] in END_REASONS && (version == 1 || validEnd(p)) &&
					validReadiness(p["readiness"]) && validInputReports(p["inputs"])
				else -> false
			}
			"git_changes" -> validChanges(p)
			else -> true
		}
	}

	private fun validEnd(p: Map<String, Any?>): Boolean {
		val started = p["executor_started"] as? Boolean ?: return false
		val resultSeen = p["result_seen"] as? Boolean ?: return false
		// A submitted run is one whose executor started and sent a successful result.
		return p["end_reason"] != "submitted" || (started && resultSeen)
	}

	/** Absent, or the run's own worktree: repository, base commit and branch, never a path on the device. */
	private fun validWorkspace(raw: Any?, target: ReportTarget): Boolean {
		if (raw == null) return true
		val ws = raw as? Map<*, *> ?: return false
		val repoKey = ws["repo_key"] as? String ?: return false
		if (!SessionService.isRepoKey(repoKey) || (target.repoKey != null && repoKey != target.repoKey)) return false
		val commit = ws["base_commit"] as? String ?: return false
		val branch = ws["branch"] as? String ?: return false
		val baseRef = ws["base_ref"]
		val origin = ws["origin"]
		return COMMIT.matches(commit) && DelegationService.isBranchName(branch) &&
			(baseRef == null || (baseRef is String && (baseRef == "HEAD" || DelegationService.isBranchName(baseRef)))) &&
			(origin == null || origin in WORKSPACE_ORIGINS)
	}

	/** `{"live_input": true, "resume": true}`: what the adapter supports (contract "어댑터가 알려야 하는 능력"). */
	private fun validCapabilities(raw: Any?): Boolean {
		if (raw == null) return true
		val caps = raw as? Map<*, *> ?: return false
		return caps.size <= 20 && caps.all { (k, v) -> k is String && k.length <= 40 && v is Boolean }
	}

	/** `{"ok": false, "checks": [{"name", "ok", "detail"}]}`: what the connector checked before starting the executor. */
	private fun validReadiness(raw: Any?): Boolean {
		if (raw == null) return true
		val r = raw as? Map<*, *> ?: return false
		if (r["ok"] !is Boolean) return false
		val checks = r["checks"] as? List<*> ?: return false
		return checks.size <= 20 && checks.all { c ->
			val m = c as? Map<*, *> ?: return@all false
			val name = m["name"] as? String
			val detail = m["detail"]
			name != null && name.length <= 40 && m["ok"] is Boolean &&
				(detail == null || (detail is String && detail.length <= 300 && !DEVICE_PATH.containsMatchIn(detail)))
		}
	}

	/** `[{"id", "state"}]`: what became of the instructions the connector took for this run. */
	private fun validInputReports(raw: Any?): Boolean {
		if (raw == null) return true
		val list = raw as? List<*> ?: return false
		return list.size <= 100 && list.all { (it as? Map<*, *>)?.let { m -> m["id"] is String && m["state"] in INPUT_REPORTS } == true }
	}

	private fun validChanges(p: Map<String, Any?>): Boolean {
		val files = stringList(p["files"]) ?: return false
		if (files.size > MAX_CHANGED_FILES || files.any { sessions.relativePath(it) != it }) return false
		val set = files.toSet()
		for (key in listOf("base_overlap", "base_branch_overlap")) {
			val sub = p[key] ?: continue
			if (!set.containsAll(stringList(sub) ?: return false)) return false
		}
		// The final list may carry the connector's snapshot commit of the result (contract "검토 대상을 고정한다" 3).
		val snapshot = p["snapshot"]
		return (p["truncated"] == null || p["truncated"] is Boolean) && (snapshot == null || (snapshot is String && COMMIT.matches(snapshot)))
	}

	private fun stringList(v: Any?): List<String>? =
		(v as? List<*>)?.map { it as? String ?: return null }

	private fun applyLifecycle(runId: String, target: ReportTarget, payload: Map<String, Any?>) {
		val workspace = payload["workspace"]?.let { json.writeValueAsString(it) }
		when (payload["phase"]) {
			"started" -> {
				val started = jdbc.sql(
					"""
					update runs set state = 'running', started_at = coalesce(started_at, now()),
					       executor_session_id = coalesce(:sessionId, executor_session_id),
					       workspace = coalesce(workspace, cast(:workspace as jsonb))
					 where id = :runId and state in ('claimed', 'running')
					""".trimIndent(),
				).param("sessionId", payload["session_id"] as? String).param("workspace", workspace)
					.param("runId", runId).update()
				if (started == 1) usage.onStarted(runId, payload)
				if (started == 1 && target.delegationId != null) {
					workItems.followRun(target.workItemId, runId, WorkItemService.RunStep.STARTED)
					questions.markDelivered(runId)
				}
			}

			"ended" -> {
				val reason = payload["end_reason"] as String // checked by valid
				val ended = jdbc.sql(
					"""
					update runs set state = 'ended', end_reason = :reason, ended_at = now(), lease_expires_at = null,
					       workspace = coalesce(workspace, cast(:workspace as jsonb))
					 where id = :runId and state <> 'ended'
					""".trimIndent(),
				).param("reason", reason).param("workspace", workspace).param("runId", runId).update()
				if (ended != 1) return
				sessions.audit("system", runId, "run.ended", "run", runId,
					mapOf("work_item_id" to target.workItemId, "end_reason" to reason, "outcome_unknown" to outcomeUnknown(reason, payload)))
				(payload["inputs"] as? List<*>).orEmpty().forEach { r ->
					val m = r as Map<*, *> // checked by valid
					applyInputReport(runId, m["id"] as String, m["state"] as String)
				}
				finishInputs(runId)
				if (outcomeUnknown(reason, payload)) addWait(target.workItemId, "outcome_unknown")
				if (readinessFailed(payload)) addWait(target.workItemId, "readiness_failed")
				usage.onEnded(runId, target.workItemId, target.accountId, reason, payload)
				if (reason == "submitted" && target.delegationId != null) {
					// Before the work item moves: an open risk request keeps it in result review.
					reviews.fixResult(runId)
					workItems.followRun(target.workItemId, runId, WorkItemService.RunStep.SUBMITTED)
				}
			}
		}
	}

	/** A server-set wait reason (contract "대기 사유"); a closed work item gets none. */
	private fun addWait(workItemId: String, reason: String) {
		jdbc.sql(
			"""
			update work_items
			   set wait_reasons = array_append(wait_reasons, :reason), version = version + 1, updated_at = now()
			 where id = :wid and not (:reason = any(wait_reasons)) and stage not in ('done', 'cancelled')
			""".trimIndent(),
		).param("reason", reason).param("wid", workItemId).update()
	}

	/** Moves one instruction of this run forward: pending → sent → delivered, or to undelivered. Never back. */
	private fun applyInputReport(runId: String, inputId: String, state: String) {
		if (state !in INPUT_REPORTS) return
		jdbc.sql(
			"""
			update run_inputs
			   set state = :state,
			       sent_at = case when :state in ('sent', 'delivered') then coalesce(sent_at, now()) else sent_at end,
			       delivered_at = case when :state = 'delivered' then now() else delivered_at end
			 where id = :id and run_id = :run
			   and (state = 'pending' or (state = 'sent' and :state in ('delivered', 'undelivered')))
			""".trimIndent(),
		).param("state", state).param("id", inputId).param("run", runId).update()
	}

	/** Once a run ended, an instruction its executor did not take is undelivered; a person can resend it by resuming. */
	private fun finishInputs(runId: String) {
		jdbc.sql("update run_inputs set state = 'undelivered' where run_id = :run and state in ('pending', 'sent')")
			.param("run", runId).update()
	}

	/**
	 * Contract v1 "실행 시도 > 상태": once the executor started, an end without its result leaves
	 * the work item `outcome_unknown`, whoever cut it; it may have been changing files. A report of
	 * protocol_version 1 does not say whether the executor started; there only `lost` does.
	 */
	private fun outcomeUnknown(reason: String, payload: Map<String, Any?>): Boolean {
		val executorStarted = payload["executor_started"] as? Boolean ?: return reason == "lost"
		return executorStarted && payload["result_seen"] != true
	}

	/** FN-0406, G38.3: the connector's check before starting the executor failed, so it never started. */
	private fun readinessFailed(payload: Map<String, Any?>): Boolean =
		payload["executor_started"] == false && (payload["readiness"] as? Map<*, *>)?.get("ok") == false
}
