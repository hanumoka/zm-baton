package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

// Wire format follows contract v1 ("보고 한 건의 모양"): snake_case field names.

data class CreateWorkItem(
	val title: String,
	@JsonProperty("responsible_user_id") val responsibleUserId: String,
)

data class WorkItemCreated(val id: String, val stage: String)

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
)

data class ClaimRequest(@JsonProperty("device_id") val deviceId: String)

data class Claimed(
	@JsonProperty("run_id") val runId: String,
	@JsonProperty("work_item_id") val workItemId: String,
	val generation: Long,
	@JsonProperty("lease_seconds") val leaseSeconds: Int,
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
class ApiController(private val service: RunService) {

	@PostMapping("/work-items")
	@ResponseStatus(HttpStatus.CREATED)
	fun createWorkItem(@RequestBody body: CreateWorkItem): WorkItemCreated =
		service.createWorkItem(body.title, body.responsibleUserId)

	@PostMapping("/work-items/{id}/runs")
	@ResponseStatus(HttpStatus.CREATED)
	fun createRun(@PathVariable id: String, @RequestBody body: CreateRun): RunCreated =
		service.createRun(id, body.executor)

	/** Work poll (contract v1 "전송 경로" 2): runs waiting to be claimed. */
	@GetMapping("/runs")
	fun pendingRuns(
		@RequestParam(defaultValue = "requested") state: String,
		@RequestParam(defaultValue = "20") limit: Int,
	): List<PendingRun> {
		if (state != "requested") throw BadRequestException("only state=requested can be polled")
		return service.pendingRuns(limit.coerceIn(1, 100))
	}

	@PostMapping("/runs/{id}/claim")
	fun claim(@PathVariable id: String, @RequestBody body: ClaimRequest): Claimed =
		service.claim(id, body.deviceId)

	@PostMapping("/runs/{id}/events")
	fun events(@PathVariable id: String, @RequestBody body: EventBatch): EventResult =
		service.ingest(id, body)
}
