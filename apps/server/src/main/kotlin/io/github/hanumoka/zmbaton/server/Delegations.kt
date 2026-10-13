package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

// Handing a work item to an AI (contract v1 "위임", "실행 권리 청구" 1, "실행할 때 넘기는 것"), and
// the repositories a device's local connector may run in. A person hands a `ready` work item made
// here to Claude Code on one device and one repository; the server keeps the delegation, makes one
// requested run with an instruction built from the work item, and makes the owner's manager the
// executor. Stage 1 has no AI account registration yet, so the run uses whatever login the
// executor already has on that device.

data class DelegateIn(
	val executor: String,
	@JsonProperty("device_id") val deviceId: String,
	@JsonProperty("repo_key") val repoKey: String,
	/** The branch whose tip is the base commit. Absent: the repository's current commit on the device. */
	@JsonProperty("base_branch") val baseBranch: String? = null,
	/** Claude Code tools the run may use without asking. Absent: `Read`, as in the compat test. */
	@JsonProperty("allowed_tools") val allowedTools: List<String>? = null,
	val version: Long,
)

data class WithdrawIn(val version: Long)

data class RepositoryUpsert(
	@JsonProperty("repo_key") val repoKey: String,
	val name: String,
	val remote: String? = null,
)

data class DeviceRepositoryView(
	@JsonProperty("device_id") val deviceId: String,
	@JsonProperty("device_name") val deviceName: String,
	@JsonProperty("repo_key") val repoKey: String,
	val name: String,
	val remote: String?,
	@JsonProperty("updated_at") val updatedAt: OffsetDateTime,
)

@Service
class DelegationService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
) {
	companion object {
		/** The owner's manager (contract "담당"): stage 1 has one person and so one manager. */
		const val MANAGER = "mgr_owner"
		val EXECUTORS = setOf("claude_code")
		/** Tools a delegation may allow. No Bash: commands need their own rules first. */
		val KNOWN_TOOLS = listOf("Read", "Glob", "Grep", "Edit", "Write")
		val DEFAULT_TOOLS = listOf("Read")
		/** Contract "위임": an offline delegation lasts at most 8 hours (G14.2). */
		const val DELEGATION_HOURS = 8
		private val BRANCH = Regex("^[A-Za-z0-9._/-]{1,200}$")

		/**
		 * A branch name that is safe on a command line and in a path: letters, digits and `._/-`,
		 * not starting with `-` or `/`, no `..` or `//`, not ending in `/`, `.` or `.lock`.
		 */
		fun isBranchName(s: String): Boolean =
			BRANCH.matches(s) && !s.startsWith("-") && !s.startsWith("/") && !s.endsWith("/") && !s.endsWith(".") &&
				!s.endsWith(".lock") && ".." !in s && "//" !in s && "/." !in s && !s.startsWith(".")
	}

	// ---------------------------------------------------------------- device repositories

	/** `zm-baton repo add`: the device says it can run in this repository. Adding again refreshes it. */
	@Transactional
	fun addRepository(deviceId: String, body: RepositoryUpsert): DeviceRepositoryView {
		requireDevice(deviceId)
		val repoKey = body.repoKey.trim()
		if (!SessionService.isRepoKey(repoKey)) throw BadRequestException("repo_key is not a repository key")
		val name = body.name.trim()
		if (name.isEmpty() || name.length > 100 || name.any { it == '/' || it == '\\' || it == ':' }) {
			throw BadRequestException("name must be a folder name of 1-100 characters, not a path")
		}
		jdbc.sql(
			"""
			insert into device_repositories (device_id, repo_key, name, remote)
			values (:device, :key, :name, :remote)
			on conflict (device_id, repo_key) do update
			   set name = excluded.name, remote = excluded.remote, updated_at = now(), removed_at = null
			""".trimIndent(),
		).param("device", deviceId).param("key", repoKey).param("name", name)
			.param("remote", sessions.cleanRemote(body.remote)).update()
		sessions.audit("person", SessionService.OWNER, "device.repository_added", "dev", deviceId, mapOf("repo_key" to repoKey, "name" to name))
		return repositories(deviceId).first { it.repoKey == repoKey }
	}

	/** `zm-baton repo remove`. The row stays for the record; new delegations cannot name it. */
	@Transactional
	fun removeRepository(deviceId: String, repoKey: String): Boolean {
		requireDevice(deviceId)
		val removed = jdbc.sql(
			"update device_repositories set removed_at = now(), updated_at = now() where device_id = :device and repo_key = :key and removed_at is null",
		).param("device", deviceId).param("key", repoKey.trim()).update() == 1
		if (removed) {
			sessions.audit("person", SessionService.OWNER, "device.repository_removed", "dev", deviceId, mapOf("repo_key" to repoKey.trim()))
		}
		return removed
	}

	/** The repositories devices can run in now, for the screen that hands work over. */
	fun repositories(deviceId: String? = null): List<DeviceRepositoryView> {
		val stmt = jdbc.sql(
			"""
			select r.device_id, d.name as device_name, r.repo_key, r.name, r.remote, r.updated_at
			  from device_repositories r join devices d on d.id = r.device_id
			 where r.removed_at is null and d.revoked_at is null${if (deviceId != null) " and r.device_id = :device" else ""}
			 order by r.name, d.name, r.repo_key
			""".trimIndent(),
		)
		if (deviceId != null) stmt.param("device", deviceId)
		return stmt.query { rs, _ ->
			DeviceRepositoryView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getObject(6, OffsetDateTime::class.java))
		}.list()
	}

	private fun requireDevice(deviceId: String) {
		val live = jdbc.sql("select count(*) from devices where id = :id and revoked_at is null").param("id", deviceId.trim())
			.query(Long::class.javaObjectType).single() > 0
		if (!live) throw NotFoundException("no device $deviceId; register it with zm-baton login")
	}

	// ---------------------------------------------------------------- delegations

	/**
	 * Hands the work item to an AI: one delegation, one requested run with its instruction, and the
	 * owner's manager as the executor, in one transaction with the version check.
	 */
	@Transactional
	fun delegate(workItemId: String, body: DelegateIn): WorkItemDetail {
		val executor = body.executor.trim()
		if (executor !in EXECUTORS) throw BadRequestException("executor must be one of $EXECUTORS")
		val deviceId = body.deviceId.trim()
		val repoKey = body.repoKey.trim()
		val baseBranch = body.baseBranch?.trim()?.ifEmpty { null }
		if (baseBranch != null && !isBranchName(baseBranch)) throw BadRequestException("base_branch is not a usable branch name")
		val tools = (body.allowedTools ?: DEFAULT_TOOLS).map { it.trim() }.distinct()
		if (tools.isEmpty() || tools.any { it !in KNOWN_TOOLS }) throw BadRequestException("allowed_tools must be some of $KNOWN_TOOLS")

		val facts = workItems.lockFacts(workItemId, body.version)
		Workflow.delegationRefusal(facts)?.let { throw ConflictException(it) }
		val registered = repositories(deviceId).any { it.repoKey == repoKey }
		if (!registered) {
			throw BadRequestException("the device $deviceId has no repository $repoKey; add it there with zm-baton repo add")
		}

		val dlg = Ids.delegation()
		val run = Ids.run()
		val scope = linkedMapOf("executor" to executor, "repo_key" to repoKey, "base_branch" to baseBranch, "allowed_tools" to tools)
		val callPath = listOf(mapOf("kind" to "person", "id" to SessionService.OWNER), mapOf("kind" to "manager", "id" to MANAGER))
		jdbc.sql(
			"""
			insert into delegations (id, work_item_id, granted_by_user_id, grantee_kind, grantee_id, device_id, scope, call_path, expires_at)
			values (:id, :wid, :owner, 'manager', :manager, :device, cast(:scope as jsonb), cast(:path as jsonb),
			        now() + make_interval(hours => :hours))
			""".trimIndent(),
		).param("id", dlg).param("wid", workItemId).param("owner", SessionService.OWNER).param("manager", MANAGER)
			.param("device", deviceId).param("scope", json.writeValueAsString(scope))
			.param("path", json.writeValueAsString(callPath)).param("hours", DELEGATION_HOURS).update()
		try {
			jdbc.sql(
				"""
				insert into runs (id, work_item_id, state, executor, delegation_id, instruction)
				values (:id, :wid, 'requested', :executor, :dlg, :instruction)
				""".trimIndent(),
			).param("id", run).param("wid", workItemId).param("executor", executor).param("dlg", dlg)
				.param("instruction", instruction(workItemId)).update()
		} catch (e: DuplicateKeyException) {
			throw ConflictException("살아 있는 실행 시도가 있다. 끝나거나 거둔 뒤에 다시 맡긴다")
		}
		jdbc.sql(
			"update work_items set executor_kind = 'manager', executor_id = :manager, version = version + 1, updated_at = now() where id = :id",
		).param("manager", MANAGER).param("id", workItemId).update()
		workItems.record(
			workItemId, "work_item.delegated",
			mapOf("delegation_id" to dlg, "run_id" to run, "executor" to executor, "device_id" to deviceId,
				"repo_key" to repoKey, "base_branch" to baseBranch, "allowed_tools" to tools),
		)
		return workItems.detail(workItemId)
	}

	/**
	 * Takes back a delegation whose run no connector has claimed yet: the run ends `cancelled`
	 * before any executor started (contract "상태": nothing changed, no `outcome_unknown`), the
	 * delegation is revoked and the work item has no executor again. A claimed run cannot be
	 * taken back here; stopping a running executor comes later.
	 */
	@Transactional
	fun withdraw(runId: String, version: Long): WorkItemDetail {
		// The run first, then the work item: the same lock order as a report (RunService.ingest).
		val run = jdbc.sql("select work_item_id, delegation_id from runs where id = :id for update")
			.param("id", runId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional()
			.orElseThrow { NotFoundException("no run $runId") }
		val (workItemId, delegationId) = run
		if (delegationId == null) throw ConflictException("실행 시험으로 만든 실행 시도는 거둘 수 없다")
		workItems.lockFacts(workItemId, version)
		val ended = jdbc.sql(
			"""
			update runs set state = 'ended', end_reason = 'cancelled', ended_at = now(), lease_expires_at = null
			 where id = :id and state = 'requested'
			""".trimIndent(),
		).param("id", runId).update()
		if (ended == 0) throw ConflictException("이미 연결 프로그램이 가져간 실행이다. 실행 중에 멈추는 기능은 아직 없다")
		jdbc.sql("update delegations set revoked_at = now() where id = :id and revoked_at is null").param("id", delegationId).update()
		jdbc.sql(
			"update work_items set executor_kind = null, executor_id = null, version = version + 1, updated_at = now() where id = :id",
		).param("id", workItemId).update()
		workItems.record(workItemId, "work_item.delegation_withdrawn", mapOf("delegation_id" to delegationId, "run_id" to runId))
		return workItems.detail(workItemId)
	}

	/**
	 * The instruction for the executor (contract "위임" 무엇을: the work item). What the work item
	 * has, in the order a person reads it, then how to work in a run's own worktree.
	 */
	private fun instruction(workItemId: String): String =
		jdbc.sql(
			"""
			select w.id, w.title, w.external_ref, w.acceptance, w.next_action, w.source_ref, p.name
			  from work_items w left join projects p on p.id = w.project_id where w.id = :id
			""".trimIndent(),
		).param("id", workItemId).query { rs, _ ->
			Instructions.build(
				Instructions.Item(
					rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
					rs.getString(6), rs.getString(7),
				),
			)
		}.single()
}

/** Builds the run instruction from a work item. Pure, so its wording is tested on its own. */
object Instructions {
	data class Item(
		val id: String,
		val title: String,
		val externalRef: String?,
		val acceptance: String?,
		val nextAction: String?,
		val sourceRef: String?,
		val project: String?,
	)

	fun build(item: Item): String = buildString {
		appendLine("zm-baton이 작업 하나를 맡긴다.")
		appendLine()
		appendLine("작업: ${item.title}")
		appendLine("작업 번호: ${item.id}${item.externalRef?.let { " ($it)" } ?: ""}")
		item.project?.let { appendLine("프로젝트: $it") }
		appendLine("수락 조건:")
		appendLine(item.acceptance ?: "적혀 있지 않다. 제목과 다음 행동으로 판단한다.")
		item.nextAction?.let { appendLine("다음 행동: $it") }
		item.sourceRef?.let { appendLine("참조 문서: $it (저장소 뿌리 기준 경로)") }
		appendLine()
		appendLine("일하는 방법:")
		appendLine("1. 지금 폴더는 이 실행만을 위해 만든 Git 작업 트리다. 이 폴더 안의 파일만 읽고 고친다.")
		appendLine("2. 브랜치를 합치거나 push하지 않는다. 사람이 결과를 검토한 뒤 Git으로 직접 합친다.")
		append("3. 끝나면 무엇을 바꿨는지, 수락 조건마다 어떻게 확인했는지, 확인하지 못한 것을 짧게 답한다.")
	}
}

@RestController
@RequestMapping("/api")
class DelegationController(private val delegations: DelegationService, private val people: PersonService) {

	@PostMapping("/work-items/{id}/delegations")
	@ResponseStatus(HttpStatus.CREATED)
	fun delegate(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: DelegateIn,
	): WorkItemDetail {
		people.refuseSessionToken(authorization, "hand a work item to an AI")
		return delegations.delegate(id, body)
	}

	@PostMapping("/runs/{id}/withdraw")
	fun withdraw(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: WithdrawIn,
	): WorkItemDetail {
		people.refuseSessionToken(authorization, "take back a delegation")
		return delegations.withdraw(id, body.version)
	}

	@GetMapping("/repositories")
	fun repositories(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): List<DeviceRepositoryView> {
		people.refuseSessionToken(authorization, "list repositories")
		return delegations.repositories()
	}

	@PostMapping("/devices/{id}/repositories")
	fun addRepository(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: RepositoryUpsert,
	): DeviceRepositoryView {
		people.refuseSessionToken(authorization, "add a repository to a device")
		return delegations.addRepository(id, body)
	}

	@DeleteMapping("/devices/{id}/repositories")
	fun removeRepository(
		@PathVariable id: String,
		@RequestParam("repo_key") repoKey: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): Map<String, Boolean> {
		people.refuseSessionToken(authorization, "remove a repository from a device")
		return mapOf("removed" to delegations.removeRepository(id, repoKey))
	}
}
