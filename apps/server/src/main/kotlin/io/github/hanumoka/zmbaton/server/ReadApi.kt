package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Read side for the Web and desktop compat test (docs/stage1/compat-test-ui.md). The work item
// list moved to WorkItemController with the second stage-1 slice.
// A run's lifecycle state and end reason are stored (data model "runs") and decided by the
// server, so the screen reads them here and never re-derives them from events. A delegated run
// also carries its delegation, its worktree and its newest Git change list. The report
// status on screen (contract v1 "보고 상태") is computed from that state and the events.

data class RunView(
	val id: String,
	@JsonProperty("work_item_id") val workItemId: String,
	val state: String,
	@JsonProperty("end_reason") val endReason: String?,
	val executor: String,
	val generation: Long?,
	@JsonProperty("device_id") val deviceId: String?,
	@JsonProperty("late_reports") val lateReports: Boolean,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("last_event_at") val lastEventAt: OffsetDateTime?,
	// Added with the third stage-1 slice (handing work to an AI).
	@JsonProperty("claimed_at") val claimedAt: OffsetDateTime? = null,
	@JsonProperty("started_at") val startedAt: OffsetDateTime? = null,
	@JsonProperty("ended_at") val endedAt: OffsetDateTime? = null,
	/** The last executor activity event; lifecycle and Git change lists do not count. */
	@JsonProperty("last_activity_at") val lastActivityAt: OffsetDateTime? = null,
	/** Null for a compat-test run. */
	val delegation: RunDelegationView? = null,
	/** The run's own worktree: `repo_key`, `base_commit`, `branch`, `base_ref`. Null until the run reports it. */
	val workspace: Map<String, Any?>? = null,
	/** The newest `git_changes` report: `files`, `base_overlap`, and at the end `base_branch_overlap`. */
	@JsonProperty("git_changes") val gitChanges: Map<String, Any?>? = null,
	val instruction: String? = null,
	// Added with run control (stopping, resuming and steering a run).
	@JsonProperty("lease_expires_at") val leaseExpiresAt: OffsetDateTime? = null,
	/** A live run whose lease lapsed: its connector has not renewed it. It ends `lost` at [lostAt]. */
	@JsonProperty("lease_lapsed") val leaseLapsed: Boolean = false,
	@JsonProperty("lost_at") val lostAt: OffsetDateTime? = null,
	@JsonProperty("continue_reason") val continueReason: String? = null,
	@JsonProperty("resumed_from_run_id") val resumedFromRunId: String? = null,
	/** What the connector checked before starting the executor, from the end report: `ok` and `checks`. */
	val readiness: Map<String, Any?>? = null,
	/** What the adapter said it supports when the executor started, e.g. `live_input`. */
	val capabilities: Map<String, Any?>? = null,
	/** Instructions a person sent to the running executor, oldest first. */
	val inputs: List<RunInputView> = emptyList(),
	/** What a person may do with the run now; null once it ended. */
	val actions: RunActions? = null,
)

data class RunDelegationView(
	val id: String,
	@JsonProperty("device_id") val deviceId: String?,
	@JsonProperty("device_name") val deviceName: String?,
	@JsonProperty("repo_key") val repoKey: String?,
	@JsonProperty("repo_name") val repoName: String?,
	@JsonProperty("base_branch") val baseBranch: String?,
	@JsonProperty("allowed_tools") val allowedTools: List<String>,
	@JsonProperty("expires_at") val expiresAt: OffsetDateTime,
	@JsonProperty("revoked_at") val revokedAt: OffsetDateTime?,
)

data class EventView(
	@JsonProperty("event_id") val eventId: String,
	val generation: Long,
	val seq: Long,
	val kind: String,
	val payload: Map<String, Any?>,
	@JsonProperty("occurred_at") val occurredAt: OffsetDateTime?,
	@JsonProperty("received_at") val receivedAt: OffsetDateTime,
)

@Service
class ReadService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	@Value("\${zm-baton.run-lost-grace-seconds:300}") private val lostGraceSeconds: Long,
) {

	private val payloadType = object : TypeReference<Map<String, Any?>>() {}

	/** The newest [limit] runs of a work item, oldest first. */
	fun runs(workItemId: String, limit: Int): List<RunView> {
		exists("work_items", workItemId)
		val inputs = jdbc.sql(
			"""
			select i.id, i.run_id, i.body, i.state, i.created_at, i.sent_at, i.delivered_at
			  from run_inputs i join runs r on r.id = i.run_id where r.work_item_id = :wid order by i.created_at, i.id
			""".trimIndent(),
		).param("wid", workItemId).query { rs, _ -> rs.getString("run_id") to RunControlService.inputView(rs) }.list()
			.groupBy({ it.first }, { it.second })
		return jdbc.sql(
			"""
			select * from (
			  select r.id, r.work_item_id, r.state, r.end_reason, r.executor, r.generation, r.device_id, r.late_reports,
			         r.created_at, r.last_event_at, r.claimed_at, r.started_at, r.ended_at, r.last_activity_at,
			         r.workspace::text as workspace, r.instruction,
			         (select e.payload::text from run_events e where e.run_id = r.id and e.kind = 'git_changes'
			           order by e.seq desc limit 1) as git_changes,
			         r.lease_expires_at, (r.state in ('claimed', 'running', 'stopping') and r.lease_expires_at < now()) as lease_lapsed,
			         r.lease_expires_at + make_interval(secs => :grace) as lost_at, r.continue_reason, r.resumed_from_run_id,
			         (select (e.payload -> 'readiness')::text from run_events e
			           where e.run_id = r.id and e.kind = 'lifecycle' and e.payload ->> 'phase' = 'ended' order by e.seq desc limit 1) as readiness,
			         (select (e.payload -> 'capabilities')::text from run_events e
			           where e.run_id = r.id and e.kind = 'lifecycle' and e.payload ->> 'phase' = 'started' order by e.seq desc limit 1) as capabilities,
			         d.id as dlg_id, d.device_id as dlg_device, dv.name as dlg_device_name, d.scope::text as dlg_scope,
			         dr.name as dlg_repo_name, d.expires_at as dlg_expires, d.revoked_at as dlg_revoked
			    from runs r
			    left join delegations d on d.id = r.delegation_id
			    left join devices dv on dv.id = d.device_id
			    left join device_repositories dr on dr.device_id = d.device_id and dr.repo_key = d.scope ->> 'repo_key'
			   where r.work_item_id = :wid order by r.created_at desc, r.id desc limit :limit
			) newest order by created_at, id
			""".trimIndent(),
		).param("wid", workItemId).param("limit", limit).param("grace", lostGraceSeconds).query { rs, _ ->
			val delegation = rs.getString("dlg_id")?.let { id ->
				val scope = json.readValue(rs.getString("dlg_scope"), payloadType)
				RunDelegationView(
					id, rs.getString("dlg_device"), rs.getString("dlg_device_name"), scope["repo_key"] as String?,
					rs.getString("dlg_repo_name"), scope["base_branch"] as String?,
					(scope["allowed_tools"] as? List<*>).orEmpty().map { it.toString() },
					rs.getObject("dlg_expires", OffsetDateTime::class.java), rs.getObject("dlg_revoked", OffsetDateTime::class.java),
				)
			}
			val state = rs.getString("state")
			val capabilities = rs.getString("capabilities")?.let { json.readValue(it, payloadType) }
			val live = state in RunService.LIVE_STATES
			RunView(
				rs.getString("id"), rs.getString("work_item_id"), state, rs.getString("end_reason"),
				rs.getString("executor"), rs.getObject("generation") as Long?, rs.getString("device_id"),
				rs.getBoolean("late_reports"), rs.getObject("created_at", OffsetDateTime::class.java),
				rs.getObject("last_event_at", OffsetDateTime::class.java),
				rs.getObject("claimed_at", OffsetDateTime::class.java), rs.getObject("started_at", OffsetDateTime::class.java),
				rs.getObject("ended_at", OffsetDateTime::class.java), rs.getObject("last_activity_at", OffsetDateTime::class.java),
				delegation, rs.getString("workspace")?.let { json.readValue(it, payloadType) },
				rs.getString("git_changes")?.let { json.readValue(it, payloadType) }, rs.getString("instruction"),
				rs.getObject("lease_expires_at", OffsetDateTime::class.java), rs.getBoolean("lease_lapsed"),
				if (live) rs.getObject("lost_at", OffsetDateTime::class.java) else null,
				rs.getString("continue_reason"), rs.getString("resumed_from_run_id"),
				rs.getString("readiness")?.let { json.readValue(it, payloadType) }, capabilities,
				inputs[rs.getString("id")].orEmpty(),
				RunActionRules.of(state, delegation != null, capabilities?.get("live_input") == true),
			)
		}.list()
	}

	/** Events in seq order after [afterSeq], so a screen can poll for only what is new. */
	fun events(runId: String, afterSeq: Long, limit: Int): List<EventView> {
		exists("runs", runId)
		return jdbc.sql(
			"""
			select event_id, generation, seq, kind, payload::text as payload, occurred_at, received_at
			  from run_events where run_id = :runId and seq > :after order by seq limit :limit
			""".trimIndent(),
		).param("runId", runId).param("after", afterSeq).param("limit", limit).query { rs, _ ->
			EventView(
				rs.getString("event_id"), rs.getLong("generation"), rs.getLong("seq"), rs.getString("kind"),
				json.readValue(rs.getString("payload"), payloadType),
				rs.getObject("occurred_at", OffsetDateTime::class.java),
				rs.getObject("received_at", OffsetDateTime::class.java),
			)
		}.list()
	}

	// The table name is one of two constants above, never input.
	private fun exists(table: String, id: String) {
		val found = jdbc.sql("select count(*) from $table where id = :id").param("id", id)
			.query(Long::class.javaObjectType).single() > 0
		if (!found) throw NotFoundException("no ${table.removeSuffix("s").replace('_', ' ')} $id")
	}
}

@RestController
@RequestMapping("/api")
class ReadController(private val reads: ReadService) {

	@GetMapping("/work-items/{id}/runs")
	fun runs(@PathVariable id: String, @RequestParam(defaultValue = "50") limit: Int): List<RunView> =
		reads.runs(id, limit.coerceIn(1, 100))

	@GetMapping("/runs/{id}/events")
	fun events(
		@PathVariable id: String,
		@RequestParam(name = "after_seq", defaultValue = "0") afterSeq: Long,
		@RequestParam(defaultValue = "200") limit: Int,
	): List<EventView> = reads.events(id, afterSeq, limit.coerceIn(1, 500))
}
