package io.github.hanumoka.zmbaton.server

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

/**
 * Claim and report handling from contract v1 ("실행 권리 청구", "세대 번호로 늦은 보고 거르기",
 * "보고 전달"). Lease renewal, device liveness and request_id dedup are out of the compat test.
 */
@Service
class RunService(private val jdbc: JdbcClient, private val json: ObjectMapper) {

	companion object {
		const val LEASE_SECONDS = 45
		val KINDS = setOf("thought", "action", "action_result", "answer", "question", "error", "usage", "lifecycle")
		val END_REASONS = setOf("submitted", "failed", "cancelled", "replaced", "lost")
	}

	@Transactional
	fun createWorkItem(title: String, responsibleUserId: String): WorkItemCreated {
		if (responsibleUserId.isBlank()) throw BadRequestException("responsible_user_id is required")
		val id = Ids.workItem()
		// The compat test starts work in `ready`; stage transitions are a later stage-1 task.
		jdbc.sql(
			"insert into work_items (id, title, stage, responsible_user_id) values (:id, :title, 'ready', :owner)",
		).param("id", id).param("title", title).param("owner", responsibleUserId).update()
		return WorkItemCreated(id, "ready")
	}

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

	fun pendingRuns(limit: Int): List<PendingRun> =
		jdbc.sql("select id, work_item_id, executor from runs where state = 'requested' order by created_at limit :limit")
			.param("limit", limit)
			.query { rs, _ -> PendingRun(rs.getString(1), rs.getString(2), rs.getString(3)) }
			.list()

	/** One conditional update; locked rows are skipped, so concurrent claims get exactly one winner. */
	@Transactional
	fun claim(runId: String, deviceId: String): Claimed {
		if (deviceId.isBlank()) throw BadRequestException("device_id is required")
		val workItemId = jdbc.sql(
			"""
			update runs
			   set state = 'claimed', device_id = :device, lease_expires_at = now() + make_interval(secs => :lease)
			 where id = (select id from runs where id = :runId and state = 'requested' for update skip locked)
			returning work_item_id
			""".trimIndent(),
		).param("device", deviceId).param("lease", LEASE_SECONDS).param("runId", runId)
			.query(String::class.java).optional()
			.orElseThrow { ConflictException("run $runId is not claimable") }
		val generation = jdbc.sql(
			"update work_items set generation = generation + 1, updated_at = now() where id = :wid returning generation",
		).param("wid", workItemId).query(Long::class.javaObjectType).single()
		jdbc.sql("update runs set generation = :gen where id = :runId")
			.param("gen", generation).param("runId", runId).update()
		return Claimed(runId, workItemId, generation, LEASE_SECONDS)
	}

	@Transactional
	fun ingest(runId: String, batch: EventBatch): EventResult {
		if (batch.protocolVersion != 1) throw BadRequestException("unsupported protocol_version ${batch.protocolVersion}")
		// Lock the run so batches for one run apply in order; read the work item's current generation.
		val current = jdbc.sql(
			"select w.generation from runs r join work_items w on w.id = r.work_item_id where r.id = :runId for update of r",
		).param("runId", runId).query(Long::class.javaObjectType).optional()
			.orElseThrow { NotFoundException("no run $runId") }

		var stored = 0
		var duplicates = 0
		var late = 0
		var rejected = 0
		for (event in batch.events.sortedBy { it.seq }) {
			if (event.kind !in KINDS || event.generation > current) {
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
			if (event.generation < current) {
				// Late report from an older generation: keep the event, never change state.
				late++
				jdbc.sql("update runs set late_reports = true where id = :runId").param("runId", runId).update()
				continue
			}
			jdbc.sql("update runs set last_event_at = now() where id = :runId").param("runId", runId).update()
			if (event.kind == "lifecycle") applyLifecycle(runId, event.payload)
		}
		return EventResult(stored, duplicates, late, rejected)
	}

	private fun applyLifecycle(runId: String, payload: Map<String, Any?>) {
		when (payload["phase"]) {
			"started" -> jdbc.sql(
				"""
				update runs set state = 'running', started_at = coalesce(started_at, now()),
				       executor_session_id = coalesce(:sessionId, executor_session_id)
				 where id = :runId and state in ('claimed', 'running')
				""".trimIndent(),
			).param("sessionId", payload["session_id"] as? String).param("runId", runId).update()

			"ended" -> {
				val reason = (payload["end_reason"] as? String)?.takeIf { it in END_REASONS } ?: "submitted"
				jdbc.sql(
					"""
					update runs set state = 'ended', end_reason = :reason, ended_at = now(), lease_expires_at = null
					 where id = :runId and state <> 'ended'
					""".trimIndent(),
				).param("reason", reason).param("runId", runId).update()
			}
		}
	}
}
