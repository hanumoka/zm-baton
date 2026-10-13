package io.github.hanumoka.zmbaton.server

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Claim and report handling from contract v1 ("실행 권리 청구", "세대 번호로 늦은 보고 거르기",
 * "보고 전달"). A delegated run (DelegationService) is claimable only by the device it names, and
 * its reports move its work item (WorkItemService.followRun). Lease renewal, device liveness and
 * request_id dedup are not built yet.
 */
@Service
class RunService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
) {

	companion object {
		const val LEASE_SECONDS = 45
		/** Executor activity (contract "공통 활동 종류"). Only these count for 응답 없음 (`last_activity_at`). */
		val ACTIVITY_KINDS = setOf("thought", "action", "action_result", "answer", "question", "error", "usage")
		/** protocol_version 1, which the compat-test connector still sends. */
		val KINDS = ACTIVITY_KINDS + "lifecycle"
		/** protocol_version 2 adds the Git change list ("보고 한 건의 모양"). */
		val KINDS_V2 = KINDS + "git_changes"
		val PROTOCOL_VERSIONS = setOf(1, 2)
		val END_REASONS = setOf("submitted", "failed", "cancelled", "replaced", "lost")
		const val MAX_CHANGED_FILES = 2000
		private val COMMIT = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	/** The compat test's work item: `ready`, no executor. */
	fun createWorkItem(title: String, responsibleUserId: String): WorkItemCreated =
		workItems.create(CreateWorkItem(title, responsibleUserId))

	/** The compat test's run: no delegation, any connector may claim it. */
	@Transactional
	fun createRun(workItemId: String, executor: String): RunCreated {
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
			select r.id, r.work_item_id, r.executor, r.delegation_id, d.device_id, d.scope ->> 'repo_key'
			  from runs r left join delegations d on d.id = r.delegation_id
			 where r.state = 'requested'$forDevice
			 order by r.created_at limit :limit
			""".trimIndent(),
		).param("limit", limit)
		if (deviceId != null) stmt.param("device", deviceId)
		return stmt.query { rs, _ ->
			PendingRun(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6))
		}.list()
	}

	/**
	 * One conditional update; locked rows are skipped, so concurrent claims get exactly one winner.
	 * A delegated run goes only to the device its delegation names, while the delegation is neither
	 * revoked nor expired (contract "위임").
	 */
	@Transactional
	fun claim(runId: String, deviceId: String): Claimed {
		if (deviceId.isBlank()) throw BadRequestException("device_id is required")
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
			returning work_item_id, delegation_id, instruction
			""".trimIndent(),
		).param("device", deviceId).param("lease", LEASE_SECONDS).param("runId", runId)
			.query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.optional()
			.orElseThrow { ConflictException("run $runId is not claimable") }
		val (workItemId, delegationId, instruction) = won
		val generation = jdbc.sql(
			"update work_items set generation = generation + 1, updated_at = now() where id = :wid returning generation",
		).param("wid", workItemId).query(Long::class.javaObjectType).single()
		jdbc.sql("update runs set generation = :gen where id = :runId")
			.param("gen", generation).param("runId", runId).update()
		val delegation = delegationId?.let { id ->
			val scope = jdbc.sql("select scope::text from delegations where id = :id").param("id", id)
				.query(String::class.java).single().let { json.readValue(it, mapType) }
			ClaimedDelegation(
				id, scope["executor"] as String, scope["repo_key"] as String, scope["base_branch"] as String?,
				(scope["allowed_tools"] as List<*>).map { it as String },
			)
		}
		return Claimed(runId, workItemId, generation, LEASE_SECONDS, delegation, instruction)
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
			       r.delegation_id, d.scope ->> 'repo_key' as repo_key
			  from runs r join work_items w on w.id = r.work_item_id
			  left join delegations d on d.id = r.delegation_id
			 where r.id = :runId
			   for update of r, w
			""".trimIndent(),
		).param("runId", runId)
			.query { rs, _ ->
				ReportTarget(rs.getString(1), rs.getObject(2) as Long?, rs.getLong(3), rs.getString(4), rs.getString(5))
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
			if (event.kind == "lifecycle") applyLifecycle(runId, target, event.payload)
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
	)

	/**
	 * A lifecycle event must be `started`, or `ended` with a contract end reason; from
	 * protocol_version 2 an end also says whether the executor started and whether its result
	 * arrived. Never infer success. A Git change list holds only paths from the repository root.
	 */
	private fun valid(event: EventIn, version: Int, target: ReportTarget): Boolean {
		val p = event.payload
		return when (event.kind) {
			"lifecycle" -> validWorkspace(p["workspace"], target) && when (p["phase"]) {
				"started" -> true
				"ended" -> p["end_reason"] in END_REASONS && (version == 1 || validEnd(p))
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
		return COMMIT.matches(commit) && DelegationService.isBranchName(branch) &&
			(baseRef == null || (baseRef is String && (baseRef == "HEAD" || DelegationService.isBranchName(baseRef))))
	}

	private fun validChanges(p: Map<String, Any?>): Boolean {
		val files = stringList(p["files"]) ?: return false
		if (files.size > MAX_CHANGED_FILES || files.any { sessions.relativePath(it) != it }) return false
		val set = files.toSet()
		for (key in listOf("base_overlap", "base_branch_overlap")) {
			val sub = p[key] ?: continue
			if (!set.containsAll(stringList(sub) ?: return false)) return false
		}
		return p["truncated"] == null || p["truncated"] is Boolean
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
				if (started == 1 && target.delegationId != null) {
					workItems.followRun(target.workItemId, runId, WorkItemService.RunStep.STARTED)
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
				if (outcomeUnknown(reason, payload)) {
					jdbc.sql(
						"""
						update work_items
						   set wait_reasons = array_append(wait_reasons, 'outcome_unknown'), version = version + 1, updated_at = now()
						 where id = :wid and not ('outcome_unknown' = any(wait_reasons)) and stage not in ('done', 'cancelled')
						""".trimIndent(),
					).param("wid", target.workItemId).update()
				}
				if (reason == "submitted" && target.delegationId != null) {
					workItems.followRun(target.workItemId, runId, WorkItemService.RunStep.SUBMITTED)
				}
			}
		}
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
}
