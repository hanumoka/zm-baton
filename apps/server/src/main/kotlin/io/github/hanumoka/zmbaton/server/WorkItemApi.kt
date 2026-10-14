package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Permissions
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
// session token is refused (contract "등록과 세션 토큰" 6), like the first screen. Every request is
// judged by Permissions: what the person may see, and whether they may make the change.

@RestController
@RequestMapping("/api")
class WorkItemController(private val workItems: WorkItemService, private val permissions: Permissions) {

	@GetMapping("/work-items")
	fun list(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(defaultValue = "20") limit: Int,
		@RequestParam(name = "project_id", required = false) projectId: String?,
		@RequestParam(defaultValue = "false") open: Boolean,
		@RequestParam(required = false) q: String?,
	): List<WorkItemView> {
		val me = permissions.person(authorization, "list work items", Act.READ)
		return workItems.list(limit.coerceIn(1, 500), projectId?.trim()?.ifEmpty { null }, open, q, me)
	}

	@PostMapping("/work-items")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: CreateWorkItem,
	): WorkItemCreated {
		val me = permissions.person(authorization, "create a work item", Act.WORK_CREATE)
		return workItems.create(body, me.person!!)
	}

	@GetMapping("/work-items/{id}")
	fun detail(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): WorkItemDetail {
		val me = permissions.person(authorization, "read a work item")
		permissions.see(me, permissions.workItem(id))
		return workItems.detail(id)
	}

	/** Changes only the fields present in the body; `version` is required. Its sharing scope only its owner changes. */
	@PatchMapping("/work-items/{id}")
	fun update(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody patch: Map<String, Any?>,
	): WorkItemDetail {
		val me = permissions.person(authorization, "change a work item")
		val target = permissions.workItem(id)
		permissions.require(me, Act.WORK_CHANGE, target)
		if ("visibility" in patch) permissions.require(me, Act.SHARE, target)
		return workItems.update(id, patch, me.person!!)
	}

	@PostMapping("/work-items/{id}/transitions")
	fun transition(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: TransitionIn,
	): WorkItemDetail {
		val me = permissions.person(authorization, "move a work item")
		permissions.require(me, Act.WORK_CHANGE, permissions.workItem(id))
		return workItems.transition(id, body, me.person!!)
	}

	@PostMapping("/work-items/{id}/wait-reasons")
	fun addWait(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: WaitReasonIn,
	): WorkItemDetail {
		val me = permissions.person(authorization, "set a wait reason")
		permissions.require(me, Act.WORK_CHANGE, permissions.workItem(id))
		return workItems.addWait(id, body, me.person!!)
	}

	@DeleteMapping("/work-items/{id}/wait-reasons/{reason}")
	fun removeWait(
		@PathVariable id: String,
		@PathVariable reason: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam version: Long,
	): WorkItemDetail {
		val me = permissions.person(authorization, "clear a wait reason")
		permissions.require(me, Act.WORK_CHANGE, permissions.workItem(id))
		return workItems.removeWait(id, reason, version, me.person!!)
	}

	@GetMapping("/projects")
	fun projects(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): List<ProjectView> {
		val me = permissions.person(authorization, "list projects", Act.READ)
		return workItems.projects(me)
	}

	@GetMapping("/settings/workflow")
	fun workflow(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): WorkflowSettings {
		val me = permissions.person(authorization, "read the workflow setting", Act.READ)
		return workItems.workflow(me.orgId!!)
	}

	@PutMapping("/settings/workflow")
	fun setWorkflow(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: WorkflowSettings,
	): WorkflowSettings {
		val me = permissions.person(authorization, "change the workflow setting", Act.SETTINGS)
		return workItems.setWorkflow(body, me.person!!)
	}
}
