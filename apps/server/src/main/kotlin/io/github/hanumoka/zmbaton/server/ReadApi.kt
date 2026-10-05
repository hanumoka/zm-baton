package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.time.OffsetDateTime

// Read side for the Web and desktop compat test (docs/stage1/compat-test-ui.md).
// A run's lifecycle state and end reason are stored (data model "runs") and decided by the
// server, so the screen reads them here and never re-derives them from events. The report
// status on screen (contract v1 "보고 상태") is computed from that state and the events.

data class WorkItemView(
	val id: String,
	val title: String,
	val stage: String,
	@JsonProperty("wait_reasons") val waitReasons: List<String>,
	val generation: Long,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
)

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
class ReadService(private val jdbc: JdbcClient, private val json: ObjectMapper) {

	private val payloadType = object : TypeReference<Map<String, Any?>>() {}

	fun workItems(limit: Int): List<WorkItemView> =
		jdbc.sql(
			"select id, title, stage, wait_reasons, generation, created_at from work_items order by created_at desc limit :limit",
		).param("limit", limit).query { rs, _ -> workItem(rs) }.list()

	/** The newest [limit] runs of a work item, oldest first. */
	fun runs(workItemId: String, limit: Int): List<RunView> {
		exists("work_items", workItemId)
		return jdbc.sql(
			"""
			select * from (
			  select id, work_item_id, state, end_reason, executor, generation, device_id, late_reports,
			         created_at, last_event_at
			    from runs where work_item_id = :wid order by created_at desc, id desc limit :limit
			) newest order by created_at, id
			""".trimIndent(),
		).param("wid", workItemId).param("limit", limit).query { rs, _ ->
			RunView(
				rs.getString("id"), rs.getString("work_item_id"), rs.getString("state"), rs.getString("end_reason"),
				rs.getString("executor"), rs.getObject("generation") as Long?, rs.getString("device_id"),
				rs.getBoolean("late_reports"), rs.getObject("created_at", OffsetDateTime::class.java),
				rs.getObject("last_event_at", OffsetDateTime::class.java),
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

	private fun workItem(rs: ResultSet): WorkItemView {
		@Suppress("UNCHECKED_CAST")
		val reasons = (rs.getArray("wait_reasons").array as Array<String>).toList()
		return WorkItemView(
			rs.getString("id"), rs.getString("title"), rs.getString("stage"), reasons, rs.getLong("generation"),
			rs.getObject("created_at", OffsetDateTime::class.java),
		)
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

	@GetMapping("/work-items")
	fun workItems(@RequestParam(defaultValue = "20") limit: Int): List<WorkItemView> =
		reads.workItems(limit.coerceIn(1, 100))

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
