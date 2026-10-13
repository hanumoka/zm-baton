package io.github.hanumoka.zmbaton.server

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

// Work items, projects and the workflow setting over HTTP. These are person actions, so a
// session token is refused (contract "등록과 세션 토큰" 6), like the first screen.

@RestController
@RequestMapping("/api")
class WorkItemController(private val workItems: WorkItemService, private val people: PersonService) {

	@GetMapping("/work-items")
	fun list(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(defaultValue = "20") limit: Int,
		@RequestParam(name = "project_id", required = false) projectId: String?,
		@RequestParam(defaultValue = "false") open: Boolean,
		@RequestParam(required = false) q: String?,
	): List<WorkItemView> {
		people.refuseSessionToken(authorization, "list work items")
		return workItems.list(limit.coerceIn(1, 500), projectId?.trim()?.ifEmpty { null }, open, q)
	}

	@PostMapping("/work-items")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: CreateWorkItem,
	): WorkItemCreated {
		people.refuseSessionToken(authorization, "create a work item")
		return workItems.create(body)
	}

	@GetMapping("/work-items/{id}")
	fun detail(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): WorkItemDetail {
		people.refuseSessionToken(authorization, "read a work item")
		return workItems.detail(id)
	}

	/** Changes only the fields present in the body; `version` is required. */
	@PatchMapping("/work-items/{id}")
	fun update(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody patch: Map<String, Any?>,
	): WorkItemDetail {
		people.refuseSessionToken(authorization, "change a work item")
		return workItems.update(id, patch)
	}

	@PostMapping("/work-items/{id}/transitions")
	fun transition(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: TransitionIn,
	): WorkItemDetail {
		people.refuseSessionToken(authorization, "move a work item")
		return workItems.transition(id, body)
	}

	@PostMapping("/work-items/{id}/wait-reasons")
	fun addWait(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: WaitReasonIn,
	): WorkItemDetail {
		people.refuseSessionToken(authorization, "set a wait reason")
		return workItems.addWait(id, body)
	}

	@DeleteMapping("/work-items/{id}/wait-reasons/{reason}")
	fun removeWait(
		@PathVariable id: String,
		@PathVariable reason: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam version: Long,
	): WorkItemDetail {
		people.refuseSessionToken(authorization, "clear a wait reason")
		return workItems.removeWait(id, reason, version)
	}

	@GetMapping("/projects")
	fun projects(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): List<ProjectView> {
		people.refuseSessionToken(authorization, "list projects")
		return workItems.projects()
	}

	@GetMapping("/settings/workflow")
	fun workflow(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): WorkflowSettings {
		people.refuseSessionToken(authorization, "read the workflow setting")
		return workItems.workflow()
	}

	@PutMapping("/settings/workflow")
	fun setWorkflow(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: WorkflowSettings,
	): WorkflowSettings {
		people.refuseSessionToken(authorization, "change the workflow setting")
		return workItems.setWorkflow(body)
	}
}
