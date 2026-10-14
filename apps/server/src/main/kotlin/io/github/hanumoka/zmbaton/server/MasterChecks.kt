package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.security.SecureRandom

// The master's contradiction checks (contract v1 "문서 모순 후보", DEC-0095). The steps:
//
//  1. A document version is approved, or a decision made here is accepted (and maybe replaces another),
//     or a zm-docs import changes accepted decisions. The writing service publishes a
//     [ContradictionTrigger]; after its transaction commits, [MasterCheckService.onTrigger] asks for a
//     check in a transaction of its own, so nothing here can undo the approval.
//  2. A check is skipped, with the reason kept, when there is nothing to compare, when today's limit of
//     automatic checks is used up, or when an import changed more decisions than one check bundles.
//  3. Otherwise the check becomes the owner's work item with one run (contract "문서 모순 후보" 5: the
//     master run follows the same claim, generation and report rules as any run):
//     - the API path (MasterApi.kt), only when the server's key variable is set: the server itself
//       claims the run and asks a cheaper model, then a stronger one;
//     - the local path otherwise, and when the API path fails: a run delegated to the owner's device,
//       which the local connector runs with the owner's own Claude Code login, read-only tools and no
//       worktree. The account's limits (#32) and the master's own smaller ones apply.
//  4. [MasterCheckService.sweep] settles checks whose run ended: a submitted run's answer is read and
//     its candidates checked (ContradictionPrompt.verify), each kept one becoming a notice; a failed API
//     run hands the same work item to the local path; anything else fails the check and closes its
//     work item as cancelled.

/** The trigger kinds and the subject a check is about. */
data class CheckSubject(val kind: String, val id: String)

/** Published inside the transaction that approved or accepted something; handled after it commits. */
data class ContradictionTrigger(val trigger: String, val subjects: List<CheckSubject>)

/** The master's own limits (start values, docs/stage1/contradictions.md). */
@Component
class MasterSettings(
	/** Automatic checks a day (skipped ones do not count). Kept small: each uses the owner's subscription or API credit. */
	@Value("\${zm-baton.master.daily-limit:5}") val dailyLimit: Int,
	/** Documents and decisions one check compares the subject with. */
	@Value("\${zm-baton.master.compare-limit:5}") val compareLimit: Int,
	@Value("\${zm-baton.master.subject-chars:12000}") val subjectChars: Int,
	@Value("\${zm-baton.master.compared-chars:6000}") val comparedChars: Int,
	/** One zm-docs import makes one check for at most this many changed decisions, none for more. */
	@Value("\${zm-baton.master.import-limit:3}") val importLimit: Int,
	/** A master run's own limits; the account's limits apply too, whichever is smaller. */
	@Value("\${zm-baton.master.max-turns:5}") val maxTurns: Int,
	@Value("\${zm-baton.master.max-minutes:10}") val maxMinutes: Int,
	@Value("\${zm-baton.master.max-budget-usd:2.00}") val maxBudgetUsd: BigDecimal,
) {
	init {
		require(dailyLimit >= 0) { "zm-baton.master.daily-limit must be 0 (off) or more" }
		require(compareLimit in 1..20) { "zm-baton.master.compare-limit must be 1-20" }
		require(importLimit >= 0) { "zm-baton.master.import-limit must be 0 or more" }
	}

	/** The account's claim limits narrowed to the master's own (RunService.claim of a master run). */
	fun cap(limits: Map<String, Any?>): Map<String, Any?> {
		val out = LinkedHashMap(limits)
		out["max_turns"] = minOf((limits["max_turns"] as? Number)?.toInt() ?: maxTurns, maxTurns)
		out["max_minutes"] = minOf((limits["max_minutes"] as? Number)?.toInt() ?: maxMinutes, maxMinutes)
		val budget = (limits["max_budget_usd"] as? BigDecimal) ?: (limits["max_budget_usd"] as? Number)?.let { BigDecimal(it.toString()) }
		if (budget == null || budget > maxBudgetUsd) {
			out["max_budget_usd"] = maxBudgetUsd
			out["budget_from"] = "run"
		}
		return out
	}
}

@Service
class MasterCheckService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val sessions: SessionService,
	private val accounts: AccountService,
	private val usage: UsageService,
	private val material: ContradictionMaterial,
	private val notices: ContradictionNotices,
	private val settings: MasterSettings,
	private val api: MasterApiSettings,
	transactions: PlatformTransactionManager,
	private val access: PermissionFacts,
) {
	/**
	 * Whom a check works for: the subjects' organisation, its owner (whose subscription the master uses,
	 * DEC-0095) as the responsible person, and the owner's manager carrying the work out (FN-0402).
	 */
	private data class For(val orgId: String, val owner: String, val manager: String?)

	companion object {
		/** The actor of what the server does for the master (audit `actor_kind` `system`). */
		const val ACTOR = "master"
		const val PURPOSE = "master_check"
		/** Read-only tools: the master run has nothing to read, and may write nothing (no Edit, no Write). */
		val TOOLS = listOf("Read", "Glob", "Grep")
		const val API_EXECUTOR = "anthropic_api"
		val TRIGGERS = setOf("document_version_approved", "decision_accepted", "decision_superseded", "zm_docs_import")
		const val ACCEPTANCE =
			"자료 사이의 모순 후보를 정해진 JSON 모양으로 답한다. 서버가 인용한 문장이 자료에 그대로 있는지 확인한 뒤 남은 후보만 첫 화면에 알린다"
		private const val LOCK = "zm-baton.master-checks"
	}

	private val log = LoggerFactory.getLogger(MasterCheckService::class.java)
	private val txNew = TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
	private val materialType = object : TypeReference<List<MaterialItem>>() {}
	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val random = SecureRandom()

	/**
	 * After the approval or acceptance committed (step 1). Never throws: a check that cannot start is
	 * logged, and the approval stands.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	fun onTrigger(e: ContradictionTrigger) {
		try {
			txNew.execute { request(e) }
		} catch (ex: Exception) {
			log.warn("could not start a contradiction check for {}", e.trigger, ex)
		}
	}

	/** Asks for one check (steps 2 and 3) and returns its id. Runs inside the caller's transaction. */
	fun request(e: ContradictionTrigger): String {
		require(e.trigger in TRIGGERS) { "unknown trigger ${e.trigger}" }
		// One request at a time, so two approvals at once cannot both take the last check of the day.
		jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", LOCK).query().listOfRows()
		val rows = e.subjects.distinct().mapNotNull { material.subject(it.kind, it.id) }
		val subjects = rows.map { s ->
			linkedMapOf("kind" to s.kind, "id" to s.id, "document_id" to s.documentId, "number" to s.number, "ref" to s.ref, "label" to s.label)
		}
		val project = rows.flatMap { it.projectIds }.firstOrNull()
		// With no subject found, the server's first organisation keeps the skipped check.
		val org = rows.firstNotNullOfOrNull { it.orgId }
			?: jdbc.sql("select id from organizations order by created_at, id limit 1").query(String::class.java).single()
		val forWhom = forOrg(org)
		fun skip(reason: String) = insert(forWhom, e.trigger, subjects, project, "skipped", null, emptyList(), reason)
		if (rows.isEmpty()) return skip("바뀐 문서나 결정을 찾지 못했다")
		if (e.trigger == "zm_docs_import" && rows.size > settings.importLimit) {
			return skip("가져오기 한 번에 채택된 결정 ${rows.size}건이 바뀌었다. 한 번에 묶어 비교하는 ${settings.importLimit}건을 넘어 만들지 않았다")
		}
		val withText = rows.filter { !it.text.isNullOrBlank() }
		if (withText.isEmpty()) return skip("Git 문서의 판은 본문이 이 서버에 없어 비교할 수 없다")
		if (settings.dailyLimit == 0) return skip("자동 모순 찾기를 껐다(zm-baton.master.daily-limit=0)")
		val used = usedToday()
		if (used >= settings.dailyLimit) return skip("오늘 자동 모순 찾기 ${settings.dailyLimit}회를 다 썼다. 내일 0시부터 다시 센다")
		val items = withText.mapIndexed { i, s -> material.subjectItem("S${i + 1}", s, settings.subjectChars) } +
			material.compare(withText, settings.compareLimit, settings.comparedChars)
		if (items.none { it.key.startsWith("C") }) {
			return skip("비교할 문서·결정이 없다. 같은 프로젝트에 승인된 문서나 채택된 결정이 없거나 겹치는 낱말이 없다")
		}
		val prompt = ContradictionPrompt.build(items, nonce())
		val title = "모순 후보 찾기: ${withText.first().label}" + if (withText.size > 1) " 외 ${withText.size - 1}건" else ""
		val apiAccount = if (api.enabled()) api.usableAccount() else null
		val useApi = api.enabled() && apiAccount?.second == null
		val id = insert(forWhom, e.trigger, subjects, project, "requested", if (useApi) "api" else "local", items, null)
		if (useApi) {
			val work = createWorkItem(forWhom, id, title, project)
			val run = delegateRun(forWhom, work, id, null, apiAccount?.first, API_EXECUTOR, prompt, null)
			jdbc.sql("update master_checks set work_item_id = :w, run_id = :r where id = :id").param("w", work).param("r", run).param("id", id).update()
		} else {
			val why = startLocal(forWhom, id, prompt, title, project, null, null)
			if (why != null) {
				jdbc.sql("update master_checks set state = 'skipped', reason = :why, finished_at = now() where id = :id").param("why", why).param("id", id).update()
				sessions.audit("system", ACTOR, "master_check.skipped", "mck", id, mapOf("reason" to why))
				return id
			}
		}
		sessions.audit("system", ACTOR, "master_check.requested", "mck", id,
			mapOf("trigger" to e.trigger, "path" to if (useApi) "api" else "local", "subjects" to subjects.map { it["id"] },
				"compared" to items.filter { it.key.startsWith("C") }.map { it.id }, "api_account_note" to apiAccount?.second))
		return id
	}

	private fun forOrg(org: String): For = access.ownerOf(org).let { owner -> For(org, owner, access.managerOf(owner, org)) }

	/** Checks that ran or will run today, in the day zone of the usage limits. */
	fun usedToday(): Int =
		jdbc.sql("select count(*) from master_checks where state <> 'skipped' and created_at >= :start")
			.param("start", usage.dayStart(usage.now())).query(Int::class.javaObjectType).single()

	private fun insert(
		forWhom: For, trigger: String, subjects: List<Map<String, Any?>>, project: String?, state: String, path: String?, items: List<MaterialItem>,
		reason: String?,
	): String {
		val id = Ids.masterCheck()
		jdbc.sql(
			"""
			insert into master_checks (id, org_id, project_id, trigger, subjects, material, path, state, reason, finished_at)
			values (:id, :org, :project, :trigger, cast(:subjects as jsonb), cast(:material as jsonb), :path, :state, :reason,
			        case when :state = 'skipped' then now() end)
			""".trimIndent(),
		).param("id", id).param("org", forWhom.orgId).param("project", project).param("trigger", trigger)
			.param("subjects", json.writeValueAsString(subjects)).param("material", json.writeValueAsString(items))
			.param("path", path).param("state", state).param("reason", reason).update()
		if (state == "skipped") {
			sessions.audit("system", ACTOR, "master_check.skipped", "mck", id, mapOf("trigger" to trigger, "reason" to reason,
				"subjects" to subjects.map { it["id"] }))
		}
		return id
	}

	// ---------------------------------------------------------------- the local path

	/**
	 * Hands the check to the owner's device: a new work item, or [workItem] when the API path failed,
	 * with one delegation and one requested run. Returns why it could not, or null. The device is the
	 * one that most recently claimed a delegated run (where `zm-baton agent` runs), else the one seen
	 * last. The account is the one a person's handoff would pick (AccountService.forDelegation): an
	 * account that is stopped now gives its reason.
	 */
	private fun startLocal(forWhom: For, checkId: String, prompt: String, title: String, project: String?, workItem: String?, replaces: String?): String? {
		val device = pickDevice(forWhom.owner) ?: return "연결 프로그램을 돌릴 기기가 없다. zm-baton login으로 이 PC를 등록하고 zm-baton agent를 띄운다"
		val account = try {
			accounts.forDelegation(null, device, forWhom.owner)
		} catch (e: ConflictException) {
			return "마스터 실행을 맡길 AI 계정이 없다: ${e.message}"
		} catch (e: BadRequestException) {
			return "마스터 실행을 맡길 AI 계정이 없다: ${e.message}"
		}
		val work = workItem ?: createWorkItem(forWhom, checkId, title, project)
		val run = delegateRun(forWhom, work, checkId, device, account.id, "claude_code", prompt, replaces)
		jdbc.sql(
			"""
			update master_checks set path = 'local', work_item_id = :w, run_id = :r, api_run_id = coalesce(api_run_id, :api)
			 where id = :id
			""".trimIndent(),
		).param("w", work).param("r", run).param("api", replaces).param("id", checkId).update()
		return null
	}

	private fun pickDevice(owner: String): String? =
		jdbc.sql(
			"""
			select d.id from devices d
			 where d.user_id = :owner and d.revoked_at is null
			 order by (select max(r.claimed_at) from runs r where r.device_id = d.id and r.delegation_id is not null) desc nulls last,
			          d.last_seen_at desc nulls last, d.created_at desc
			 limit 1
			""".trimIndent(),
		).param("owner", owner).query(String::class.java).optional().orElse(null)

	/** The owner's work item for one check: ready, the owner responsible, the owner's manager carrying it out. */
	private fun createWorkItem(forWhom: For, checkId: String, title: String, project: String?): String {
		val id = Ids.workItem()
		jdbc.sql(
			"""
			insert into work_items (id, org_id, title, stage, responsible_user_id, executor_kind, executor_id, project_id, acceptance, opened_on)
			values (:id, :org, :title, 'ready', :owner, 'manager', :manager, :project, :acceptance, current_date)
			""".trimIndent(),
		).param("id", id).param("org", forWhom.orgId).param("title", title.take(200)).param("owner", forWhom.owner)
			.param("manager", forWhom.manager ?: Ids.managerOf(forWhom.owner))
			.param("project", project).param("acceptance", ACCEPTANCE).update()
		sessions.audit("system", ACTOR, "work_item.created", "wrk", id, mapOf("stage" to "ready", "title" to title.take(200), "check_id" to checkId))
		return id
	}

	/**
	 * One delegation and one requested run for the master. A device gets the read-only tools; the API
	 * path has no device and no tools. [replaces] is the failed API run a local run takes over from
	 * (contract "실행 권리 청구" 1-2, `replacement`).
	 */
	private fun delegateRun(
		forWhom: For, work: String, checkId: String, device: String?, account: String?, executor: String, prompt: String, replaces: String?,
	): String {
		val manager = forWhom.manager ?: Ids.managerOf(forWhom.owner)
		val dlg = Ids.delegation()
		val run = Ids.run()
		val scope = linkedMapOf<String, Any?>("executor" to executor, "purpose" to PURPOSE, "check_id" to checkId,
			"allowed_tools" to if (device == null) emptyList() else TOOLS)
		val callPath = listOf(mapOf("kind" to "system", "id" to ACTOR), mapOf("kind" to "manager", "id" to manager))
		jdbc.sql(
			"""
			insert into delegations (id, work_item_id, granted_by_user_id, grantee_kind, grantee_id, device_id, account_id, scope, call_path, expires_at)
			values (:id, :wid, :owner, 'manager', :manager, :device, :account, cast(:scope as jsonb), cast(:path as jsonb),
			        now() + make_interval(hours => :hours))
			""".trimIndent(),
		).param("id", dlg).param("wid", work).param("owner", forWhom.owner).param("manager", manager)
			.param("device", device).param("account", account).param("scope", json.writeValueAsString(scope))
			.param("path", json.writeValueAsString(callPath)).param("hours", DelegationService.DELEGATION_HOURS).update()
		jdbc.sql(
			"""
			insert into runs (id, work_item_id, state, executor, delegation_id, instruction, resumed_from_run_id, continue_reason, account_id)
			values (:id, :wid, 'requested', :executor, :dlg, :instruction, :from, :reason, :account)
			""".trimIndent(),
		).param("id", run).param("wid", work).param("executor", executor).param("dlg", dlg).param("instruction", prompt)
			.param("from", replaces).param("reason", replaces?.let { "replacement" }).param("account", account).update()
		sessions.audit("system", ACTOR, "work_item.delegated", "wrk", work,
			mapOf("delegation_id" to dlg, "run_id" to run, "executor" to executor, "device_id" to device, "account_id" to account,
				"purpose" to PURPOSE, "check_id" to checkId, "allowed_tools" to scope["allowed_tools"], "resumed_from_run_id" to replaces))
		return run
	}

	private fun nonce(): String = ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }

	// ---------------------------------------------------------------- settling (step 4)

	/** Settles the checks whose run moved on; each in its own transaction. Returns how many finished. */
	fun sweep(): Int {
		val ids = jdbc.sql("select id from master_checks where state in ('requested', 'running') order by created_at limit 50")
			.query(String::class.java).list().filterNotNull()
		return ids.count { id -> txNew.execute { settle(id) } == true }
	}

	private data class Open(val id: String, val state: String, val path: String?, val workItem: String?, val run: String?, val apiRun: String?)

	private data class RunNow(val state: String, val endReason: String?, val expired: Boolean, val delegation: String?, val instruction: String?)

	private fun settle(checkId: String): Boolean {
		val check = jdbc.sql(
			"""
			select id, state, path, work_item_id, run_id, api_run_id from master_checks
			 where id = :id and state in ('requested', 'running') for update skip locked
			""".trimIndent(),
		).param("id", checkId).query { rs, _ -> Open(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)) }
			.optional().orElse(null) ?: return false
		val runId = check.run ?: return false
		val run = jdbc.sql(
			"""
			select r.state, r.end_reason, coalesce(d.expires_at < now() or d.revoked_at is not null, false), r.delegation_id, r.instruction
			  from runs r left join delegations d on d.id = r.delegation_id where r.id = :id
			""".trimIndent(),
		).param("id", runId).query { rs, _ -> RunNow(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4), rs.getString(5)) }.single()
		return when {
			run.state == "requested" && run.expired -> {
				val ended = jdbc.sql("update runs set state = 'ended', end_reason = 'cancelled', ended_at = now() where id = :id and state = 'requested'")
					.param("id", runId).update()
				if (ended == 1) {
					jdbc.sql("update delegations set revoked_at = now() where id = :id and revoked_at is null").param("id", run.delegation).update()
					sessions.audit("system", ACTOR, "run.ended", "run", runId, mapOf("end_reason" to "cancelled", "reason" to "not claimed in time"))
				}
				fail(check, "연결 프로그램이 ${DelegationService.DELEGATION_HOURS}시간 안에 가져가지 않았다. 그 PC에서 zm-baton agent를 띄워 둔다")
				true
			}
			run.state == "requested" -> false
			run.state != "ended" -> {
				if (check.state == "requested") jdbc.sql("update master_checks set state = 'running' where id = :id").param("id", checkId).update()
				false
			}
			run.endReason == "submitted" -> {
				finish(check, runId)
				true
			}
			check.path == "api" && check.apiRun == null -> {
				// DEC-0095 2: the API stopped (credit used up, refusal, outage); the owner's task goes to the local path.
				val org = jdbc.sql("select org_id from master_checks where id = :id").param("id", checkId).query(String::class.java).single()
				val why = startLocal(forOrg(org), checkId, run.instruction.orEmpty(), "", null, check.workItem, runId)
				if (why == null) {
					jdbc.sql("update master_checks set state = 'requested' where id = :id").param("id", checkId).update()
					sessions.audit("system", ACTOR, "master_check.fallback", "mck", checkId, mapOf("api_run_id" to runId, "end_reason" to run.endReason))
				} else {
					fail(check, "API 경로가 실패했고 로컬 경로로도 넘기지 못했다: $why")
				}
				true
			}
			else -> {
				fail(check, "마스터 실행이 끝났지만 결과가 없다(${endWords(run.endReason)})")
				true
			}
		}
	}

	/**
	 * A submitted run: reads the answer from its end report (`result.text`), keeps the candidates whose
	 * quotes are in the texts it was given, and raises a notice for each. An answer that is not the
	 * agreed JSON fails the check.
	 */
	private fun finish(check: Open, runId: String) {
		val ended = jdbc.sql(
			"""
			select payload::text from run_events where run_id = :id and kind = 'lifecycle' and payload ->> 'phase' = 'ended'
			 order by seq desc limit 1
			""".trimIndent(),
		).param("id", runId).query(String::class.java).optional().orElse(null)?.let { json.readValue(it, mapType) }
		val result = ended?.get("result") as? Map<*, *>
		val text = result?.get("text") as? String
		if (text.isNullOrBlank()) return fail(check, "마스터 실행의 끝 보고에 답이 없다. 연결 프로그램이 이 기능보다 오래된 판일 수 있다")
		val raws = ContradictionPrompt.parse(text, json) ?: return fail(check, "마스터의 답을 정해진 JSON 모양으로 읽지 못했다")
		val items = jdbc.sql("select material::text from master_checks where id = :id").param("id", check.id)
			.query(String::class.java).single().let { json.readValue(it, materialType) }
		val verified = ContradictionPrompt.verify(items, raws)
		val ids = verified.kept.map { notices.raise(check.id, runId, check.workItem, check.path ?: "local", it) }
		val outcome = linkedMapOf(
			"proposed" to raws.size, "kept" to verified.kept.size, "screened" to (ended["screened"] as? Number)?.toInt(),
			"truncated" to (result["truncated"] == true), "dropped" to verified.dropped.map { mapOf("reason" to it.reason, "left" to it.left, "right" to it.right) },
			"notice_ids" to ids.distinct(),
		)
		jdbc.sql("update master_checks set state = 'done', outcome = cast(:o as jsonb), finished_at = now() where id = :id")
			.param("o", json.writeValueAsString(outcome)).param("id", check.id).update()
		sessions.audit("system", ACTOR, "master_check.done", "mck", check.id,
			mapOf("run_id" to runId, "proposed" to raws.size, "kept" to verified.kept.size, "dropped" to verified.dropped.size, "notice_ids" to ids.distinct()))
	}

	private fun fail(check: Open, reason: String) {
		jdbc.sql("update master_checks set state = 'failed', reason = :r, finished_at = now() where id = :id").param("r", reason).param("id", check.id).update()
		sessions.audit("system", ACTOR, "master_check.failed", "mck", check.id, mapOf("reason" to reason, "run_id" to check.run))
		check.workItem?.let { closeWork(it, check.run, reason) }
	}

	/** A failed check's work item is closed as cancelled, so nobody hands the master's task on by mistake. */
	private fun closeWork(workItem: String, runId: String?, reason: String) {
		val from = jdbc.sql("select stage from work_items where id = :id for update").param("id", workItem)
			.query(String::class.java).optional().orElse(null) ?: return
		if (from in Workflow.CLOSED) return
		jdbc.sql(
			"""
			update work_items set stage = 'cancelled', wait_reasons = '{}', wait_note = null, closed_at = now(),
			       version = version + 1, updated_at = now()
			 where id = :id
			""".trimIndent(),
		).param("id", workItem).update()
		sessions.audit("system", runId ?: ACTOR, "work_item.stage_changed", "wrk", workItem,
			mapOf("from" to from, "to" to "cancelled", "path" to listOf("cancelled"), "run_id" to runId, "note" to reason))
	}

	private fun endWords(reason: String?) = when (reason) {
		"failed" -> "오류"
		"cancelled" -> "취소"
		"lost" -> "연결 끊김"
		"replaced" -> "다른 실행으로 넘김"
		else -> reason ?: "알 수 없음"
	}
}

/** The sweep and the API path run inside the server, like the session sweeper. */
@Component
class MasterJob(private val checks: MasterCheckService, private val api: MasterApiRunner) {
	@org.springframework.scheduling.annotation.Scheduled(
		fixedDelayString = "\${zm-baton.master.sweep-ms:5000}", initialDelayString = "\${zm-baton.master.sweep-ms:5000}",
	)
	fun tick() {
		api.runPending()
		checks.sweep()
	}
}
