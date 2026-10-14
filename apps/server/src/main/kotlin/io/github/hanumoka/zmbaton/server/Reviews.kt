package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.ActorKind
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.Permissions
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// Picking what people must see (FN-0411, DEC-0102, DEC-0105; contract v1 "사람에게 보낼 것을
// 고른다"). When a delegated run submits its result, [ReviewService.fixResult] pins it to the
// connector's snapshot commit, checks the changed files against the risk path list and sends a
// review request to a person for a risk; for a result without one it draws a sample; the rest
// stays 「사람 미확인」. The person approves or rejects on the first screen; only a person can.
// Core module 1 ("업무 판정: 상태 전이·권한·승인"): a rejection moves the work item back, an open
// risk request holds it in result review.

data class ReviewWorkItem(
	val id: String,
	val title: String,
	@JsonProperty("external_ref") val externalRef: String?,
	val stage: String,
	val version: Long,
	val project: ProjectRef?,
)

data class ReviewRun(
	val id: String,
	@JsonProperty("end_reason") val endReason: String?,
	@JsonProperty("ended_at") val endedAt: OffsetDateTime?,
	/** `repo_key`, `base_commit`, `branch`, `base_ref`: never a path on the device. */
	val workspace: Map<String, Any?>?,
	@JsonProperty("repo_name") val repoName: String?,
	@JsonProperty("device_name") val deviceName: String?,
	/** The result's changed files: the final Git change list and the paths its tools named. */
	val files: List<String>,
	val truncated: Boolean,
	@JsonProperty("result_snapshot_sha") val resultSnapshotSha: String?,
	@JsonProperty("human_review_state") val humanReviewState: String?,
	@JsonProperty("auto_check_state") val autoCheckState: String?,
)

data class ReviewProposalView(val pattern: String, val state: String)

/** What approving and rejecting would do now, in words for the person who decides. */
data class ReviewEffects(val approve: String, val reject: String, @JsonProperty("reject_needs_version") val rejectNeedsVersion: Boolean)

data class ReviewDetail(
	val id: String,
	val reason: String,
	@JsonProperty("raised_by") val raisedBy: String,
	val state: String,
	@JsonProperty("target_kind") val targetKind: String,
	@JsonProperty("target_ref") val targetRef: String,
	val evidence: Map<String, Any?>,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("decided_by") val decidedBy: String?,
	@JsonProperty("decided_at") val decidedAt: OffsetDateTime?,
	val note: String?,
	@JsonProperty("superseded_by") val supersededBy: String?,
	val proposal: ReviewProposalView?,
	@JsonProperty("work_item") val workItem: ReviewWorkItem?,
	val run: ReviewRun?,
	val effects: ReviewEffects?,
	/** A risk path candidate to offer when rejecting a sample. */
	@JsonProperty("suggested_pattern") val suggestedPattern: String?,
)

/** A review request of one run, for the run list. */
data class RunReviewView(
	val id: String,
	val reason: String,
	val state: String,
	@JsonProperty("target_kind") val targetKind: String,
	@JsonProperty("decided_at") val decidedAt: OffsetDateTime?,
	val note: String?,
	@JsonProperty("superseded_by") val supersededBy: String?,
	/** The open first-screen item, to go there from the run. */
	@JsonProperty("inbox_item_id") val inboxItemId: String?,
)

data class ReviewDecisionIn(
	/** `approved` or `rejected`. */
	val decision: String,
	/** The target the person saw (contract "검토 대상을 고정한다" 7). */
	@JsonProperty("target_ref") val targetRef: String,
	/** Why; required to reject. */
	val note: String? = null,
	/** The work item's version the person saw; needed when the rejection sends the result back. */
	@JsonProperty("work_version") val workVersion: Long? = null,
	/** A risk path candidate to put forward with a rejection. Nothing adds it on its own. */
	@JsonProperty("propose_risk_path") val proposeRiskPath: String? = null,
)

data class ReviewDecisionOut(
	val review: ReviewDetail,
	/** `approved`, `released` (approved and the work item passed to done), `returned`, `fix_created` or `recorded`. */
	val effect: String,
	@JsonProperty("fix_work_item_id") val fixWorkItemId: String? = null,
)

@Service
class ReviewService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val workItems: WorkItemService,
	private val settings: ReviewSettingsService,
	private val approvals: ApprovalService,
	private val sessions: SessionService,
	transactions: PlatformTransactionManager,
) {
	companion object {
		const val RULE = "review.v1"
		const val NOTE_MAX = 1000
		const val EVIDENCE_FILES_MAX = 200
		/** Reasons that hold a result in review: everything but a sample (contract "반려하면" 4). */
		const val HOLDING = "reason <> 'sample'"
		private val COMMIT = Regex("^[0-9a-f]{40}([0-9a-f]{24})?$")
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val tx = TransactionTemplate(transactions)

	// ---------------------------------------------------------------- pinning and picking

	private data class RunFacts(
		val workItemId: String,
		val title: String,
		val projectId: String?,
		val responsible: String,
		val waitReasons: List<String>,
		val repoKey: String?,
		val orgId: String,
	)

	private data class NewRequest(val reason: String, val raisedBy: String, val evidence: Map<String, Any?>)

	/**
	 * Pins a submitted result and decides who must see it, in the run's report transaction (the
	 * caller holds the run and work item locks, RunService.ingest), before the work item moves:
	 * an open risk request keeps it in result review even where that stage is skipped.
	 *
	 * 1. The target is the connector's snapshot commit; without one (an older connector, or the
	 *    snapshot failed) it is `unpinned`: the run id and its reported paths, hashed (contract
	 *    "검토 대상을 고정한다" 1, 5).
	 * 2. The files are the final Git change list and the paths of the run's file tools. Risk path
	 *    patterns that catch one raise `risk_path`; a work item stopped for extra billing raises
	 *    `extra_billing` (contract "위험 목록을 판정한다").
	 * 3. A pinned result with no request may be drawn as a sample, up to the recipient's daily cap.
	 * 4. Open requests of the work item's earlier runs are replaced by the new ones (contract
	 *    "검토 대상을 고정한다" 8): their target is no longer the newest.
	 */
	fun fixResult(runId: String) {
		val run = jdbc.sql(
			"""
			select w.id, w.title, w.project_id, w.responsible_user_id, w.wait_reasons, d.scope ->> 'repo_key', w.org_id
			  from runs r join work_items w on w.id = r.work_item_id left join delegations d on d.id = r.delegation_id
			 where r.id = :id
			""".trimIndent(),
		).param("id", runId).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			RunFacts(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
				(rs.getArray(5).array as Array<String>).toList(), rs.getString(6), rs.getString(7))
		}.single()
		val changes = newestChanges(runId)
		val snapshot = (changes?.get("snapshot") as? String)?.takeIf { COMMIT.matches(it) }
		val files = (strings(changes?.get("files")) + toolPaths(runId)).distinct().sorted()
		val targetKind = if (snapshot != null) "run_snapshot" else "unpinned"
		val targetRef = snapshot ?: sha256(runId + "\n" + files.joinToString("\n"))

		val rules = settings.effective(run.projectId, run.repoKey, run.orgId)
		val matches = RiskPaths.match(rules.patterns, files)
		val requests = mutableListOf<NewRequest>()
		if (matches.isNotEmpty()) {
			requests += NewRequest("risk_path", "rule", mapOf(
				"rule" to RULE, "list" to rules.source,
				"matches" to matches.map { mapOf("pattern" to it.pattern, "files" to it.files.take(EVIDENCE_FILES_MAX)) },
			))
		}
		if ("billing_stopped" in run.waitReasons) {
			requests += NewRequest("extra_billing", "rule", mapOf("rule" to RULE, "wait_reason" to "billing_stopped"))
		}
		val sample = linkedMapOf<String, Any?>("rule" to Sampler.RULE, "seed" to "sha256(${Sampler.seed(runId)})")
		when {
			requests.isNotEmpty() -> sample["skipped"] = "risk"
			targetKind == "unpinned" -> sample["skipped"] = "unpinned"
			else -> {
				val rate = settings.sampleRate(run.orgId)
				val cap = settings.sampleDailyCap(run.orgId)
				val draw = Sampler.draw(runId)
				sample += mapOf("draw" to draw, "rate" to rate, "daily_cap" to cap)
				if (Sampler.picked(draw, rate)) {
					val today = samplesToday(run.responsible)
					sample["taken_today"] = today
					if (today < cap) requests += NewRequest("sample", "sampler", sample) else sample["capped"] = true
				}
				sample["picked"] = requests.isNotEmpty()
			}
		}

		val created = requests.map { insertRequest(runId, run, targetKind, targetRef, it, files) }
		supersedeEarlier(run.workItemId, runId, created)
		val basis = linkedMapOf(
			"rule" to RULE, "target_kind" to targetKind, "target_ref" to targetRef, "list" to rules.source,
			"patterns" to rules.patterns.size, "files" to files.size,
			"matches" to matches.map { mapOf("pattern" to it.pattern, "files" to it.files.take(20)) },
			"reasons" to requests.map { it.reason }, "sample" to sample,
		)
		jdbc.sql(
			"""
			update runs set result_snapshot_sha = :snapshot, auto_check_state = 'not_run', review_basis = cast(:basis as jsonb)
			 where id = :id
			""".trimIndent(),
		).param("snapshot", snapshot).param("basis", json.writeValueAsString(basis)).param("id", runId).update()
		refreshState(runId)
		sessions.audit("system", runId, "review.judged", "run", runId, basis)
	}

	/** The newest Git change list of the run; the final one carries the snapshot. */
	private fun newestChanges(runId: String): Map<String, Any?>? =
		jdbc.sql("select payload::text from run_events where run_id = :id and kind = 'git_changes' order by seq desc limit 1")
			.param("id", runId).query(String::class.java).optional().orElse(null)?.let { json.readValue(it, mapType) }

	/**
	 * Paths the run's file tools named (contract: 「활동 사건의 파일 경로」). The connector cuts the
	 * worktree out of them, so they are relative; on Windows they may use `\`.
	 */
	private fun toolPaths(runId: String): List<String> =
		jdbc.sql(
			"""
			select coalesce(payload -> 'input' ->> 'file_path', payload -> 'input' ->> 'path') from run_events
			 where run_id = :id and kind = 'action'
			   and coalesce(payload -> 'input' ->> 'file_path', payload -> 'input' ->> 'path') is not null
			""".trimIndent(),
		).param("id", runId).query(String::class.java).list()
			.mapNotNull { raw -> raw?.replace('\\', '/')?.removePrefix("./")?.let { sessions.relativePath(it) } }

	private fun strings(v: Any?): List<String> = (v as? List<*>).orEmpty().mapNotNull { it as? String }

	/** Samples sent to [user] since midnight of the server's day. */
	private fun samplesToday(user: String): Long =
		jdbc.sql("select count(*) from review_items where reason = 'sample' and recipient_user_id = :user and created_at >= :since")
			.param("user", user).param("since", LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime())
			.query(Long::class.javaObjectType).single()

	private fun insertRequest(runId: String, run: RunFacts, targetKind: String, targetRef: String, req: NewRequest, files: List<String>): Pair<String, String> {
		val id = Ids.review()
		jdbc.sql(
			"""
			insert into review_items (id, org_id, project_id, work_item_id, run_id, recipient_user_id, target_kind, target_ref,
			                          source_key, reason, raised_by, evidence)
			values (:id, :org, :project, :wid, :run, :user, :tkind, :tref, :run, :reason, :by, cast(:evidence as jsonb))
			""".trimIndent(),
		).param("id", id).param("org", run.orgId).param("project", run.projectId).param("wid", run.workItemId)
			.param("run", runId).param("user", run.responsible).param("tkind", targetKind).param("tref", targetRef)
			.param("reason", req.reason).param("by", req.raisedBy).param("evidence", json.writeValueAsString(req.evidence)).update()
		@Suppress("UNCHECKED_CAST")
		val caught = (req.evidence["matches"] as? List<Map<String, Any?>>).orEmpty().flatMap { strings(it["files"]) }.distinct()
		val sample = req.reason == "sample"
		val summary = "${run.title}: " + when (req.reason) {
			"risk_path" -> "위험 경로 ${caught.first()}" + (if (caught.size > 1) " 외 ${caught.size - 1}개" else "")
			"extra_billing" -> "추가 과금으로 멈춘 실행의 결과"
			else -> "표본, 바뀐 파일 ${files.size}개"
		}
		val detail = mapOf(
			"work_item_id" to run.workItemId, "run_id" to runId, "reason" to req.reason, "title" to run.title,
			"files" to (if (caught.isNotEmpty()) caught else files).take(10), "target_kind" to targetKind,
		)
		// A risk on a result the person must accept before merging is raised at once; a sample waits
		// for the digest (contract "알림과 집중 모드" 1, 2).
		jdbc.sql(
			"""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, detail, notify)
			values (:id, :user, :bucket, :kind, 'rvw', :review, :summary, cast(:detail as jsonb), :notify)
			""".trimIndent(),
		).param("id", Ids.inbox()).param("user", run.responsible).param("bucket", if (sample) 5 else 4)
			.param("kind", if (sample) "sample_review" else "risk_review").param("review", id).param("summary", summary.take(300))
			.param("detail", json.writeValueAsString(detail)).param("notify", if (sample) "digest" else "immediate").update()
		val raised = mapOf("review_id" to id, "reason" to req.reason, "run_id" to runId, "work_item_id" to run.workItemId,
			"target_kind" to targetKind, "target_ref" to targetRef)
		sessions.audit("system", runId, "review.raised", "rvw", id, raised)
		// The work item's history says who must look at its result (the run is the actor, as for its moves).
		sessions.audit("system", runId, "review.raised", "wrk", run.workItemId, raised)
		return req.reason to id
	}

	/** Earlier runs' open requests of the work item end `superseded`, each pointing at the new request of its reason when there is one. */
	private fun supersedeEarlier(workItemId: String, runId: String, created: List<Pair<String, String>>) {
		val open = jdbc.sql(
			"select id, reason, run_id from review_items where work_item_id = :wid and state = 'open' and run_id <> :run order by created_at for update",
		).param("wid", workItemId).param("run", runId).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.list()
		for ((id, reason, oldRun) in open) {
			val by = created.firstOrNull { it.first == reason }?.second ?: created.firstOrNull()?.second
			jdbc.sql("update review_items set state = 'superseded', superseded_by = :by where id = :id").param("by", by).param("id", id).update()
			closeInbox(id)
			refreshState(oldRun)
			sessions.audit("system", runId, "review.superseded", "rvw", id, mapOf("by" to by, "run_id" to runId))
		}
	}

	private fun closeInbox(reviewId: String) {
		jdbc.sql("update inbox_items set state = 'closed', closed_at = now() where source_kind = 'rvw' and source_id = :id and state = 'open'")
			.param("id", reviewId).update()
	}

	/** Recomputes a submitted result's human review state from its requests; replaced requests do not count. */
	private fun refreshState(runId: String) {
		val rows = jdbc.sql("select reason, state from review_items where run_id = :id and state <> 'superseded'")
			.param("id", runId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
		val state = ReviewRules.humanState(rows.filter { it.second == "open" }.map { it.first }, rows.count { it.second != "open" })
		jdbc.sql("update runs set human_review_state = :state where id = :id and end_reason = 'submitted'")
			.param("state", state).param("id", runId).update()
	}

	// ---------------------------------------------------------------- deciding

	/**
	 * A person approves or rejects a request on the target they saw. Anyone but a person is refused
	 * and the refusal audited (data model constraint 6: AI never closes a request a rule raised); the
	 * judge (PermissionRules, Act REVIEW_DECIDE) says so and in what words.
	 * The run, the work item and the request are locked in a report's order (run, work item), so a
	 * report and a verdict never interleave. A rejection does what the contract's "반려하면 작업이
	 * 어떻게 되나" says ([ReviewRules.rejectEffect]); an approval that frees a result held in review
	 * where the organisation skips result review lets the work item pass to done.
	 */
	fun decide(reviewId: String, body: ReviewDecisionIn, actorKind: String, actorId: String): ReviewDecisionOut {
		if (actorKind != "person") {
			val kind = when (actorKind) {
				"session" -> ActorKind.SESSION
				"run" -> ActorKind.RUN
				"manager" -> ActorKind.MANAGER
				else -> ActorKind.SYSTEM
			}
			val verdict = PermissionRules.check(Actor(kind, actorId, null, null, null, false, emptySet()), Act.REVIEW_DECIDE)
			// Outside a transaction on purpose: the refusal's audit row must survive the refusal. The audit
			// log knows sessions and the server; any other AI actor is written as the server, with its kind.
			val auditKind = if (actorKind == "session") "session" else "system"
			sessions.audit(auditKind, actorId, "review.close_refused", "rvw", reviewId, mapOf("decision" to body.decision, "actor_kind" to actorKind))
			throw ForbiddenException(verdict.reason ?: Act.REVIEW_DECIDE.notPerson!!)
		}
		return tx.execute { decideByPerson(reviewId, body, actorId) }!!
	}

	private fun decideByPerson(reviewId: String, body: ReviewDecisionIn, actorId: String): ReviewDecisionOut {
		val verdict = body.decision.trim()
		if (verdict !in setOf("approved", "rejected")) throw BadRequestException("decision must be approved or rejected")
		val note = body.note?.trim()?.ifEmpty { null }
		if (note != null && note.length > NOTE_MAX) throw BadRequestException("note must be at most $NOTE_MAX characters")
		if (verdict == "rejected" && note == null) throw BadRequestException("반려에는 이유를 note에 쓴다")
		val proposal = body.proposeRiskPath?.trim()?.ifEmpty { null }?.also { p ->
			if (verdict != "rejected") throw BadRequestException("위험 경로 후보는 반려할 때 올린다")
			RiskPaths.problem(p)?.let { throw BadRequestException("위험 경로 후보 $p: $it") }
		}
		val (runId, workItemId) = jdbc.sql("select run_id, work_item_id from review_items where id = :id").param("id", reviewId)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElseThrow { NotFoundException("no review $reviewId") }
		runId?.let { jdbc.sql("select id from runs where id = :id for update").param("id", it).query(String::class.java).list() }
		workItemId?.let { jdbc.sql("select id from work_items where id = :id for update").param("id", it).query(String::class.java).list() }
		val row = jdbc.sql("select state, target_kind, target_ref, reason from review_items where id = :id for update").param("id", reviewId)
			.query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.single()
		val (state, targetKind, targetRef, reason) = row
		if (state != "open") throw ConflictException(when (state) {
			"superseded" -> "새 실행 결과로 대체된 요청이다. 새 요청을 본다"
			else -> "이미 ${if (state == "approved") "승인" else "반려"}한 요청이다"
		})
		if (body.targetRef.trim() != targetRef) throw ConflictException("화면에서 본 대상과 지금 대상이 다르다. 다시 읽고 판정한다")

		val effect = if (verdict == "rejected") rejectEffect(workItemId, runId, reason) else null
		if (effect == ReviewRules.RejectEffect.RETURN) {
			val version = body.workVersion ?: throw BadRequestException("결과를 돌려보내는 반려에는 작업의 work_version이 필요하다")
			workItems.transition(workItemId!!, TransitionIn("in_progress", version, note), actorId)
		}
		jdbc.sql(
			"""
			update review_items set state = :state, decided_by = :by, decided_at = now(), note = :note,
			       propose_risk_path = :proposal, proposal_state = case when cast(:proposal as text) is null then null else 'open' end
			 where id = :id
			""".trimIndent(),
		).param("state", verdict).param("by", actorId).param("note", note).param("proposal", proposal).param("id", reviewId).update()
		closeInbox(reviewId)
		val approval = approvals.record("review_item", reviewId, 1, actorId, verdict, note, "$targetKind:$targetRef")
		runId?.let { refreshState(it) }

		var fixId: String? = null
		val outcome = when (effect) {
			null -> if (workItemId != null && workItems.releaseHeldResult(workItemId, actorId)) "released" else "approved"
			ReviewRules.RejectEffect.RETURN -> "returned"
			ReviewRules.RejectEffect.FIX_ITEM -> {
				fixId = createFix(workItemId!!, note!!, reviewId, actorId)
				"fix_created"
			}
			ReviewRules.RejectEffect.RECORD -> "recorded"
		}
		val details = mapOf("review_id" to reviewId, "reason" to reason, "target_kind" to targetKind, "target_ref" to targetRef,
			"approval" to approval, "note" to note, "effect" to outcome, "fix_work_item_id" to fixId, "propose_risk_path" to proposal)
		sessions.audit("person", actorId, "review.$verdict", "rvw", reviewId, details)
		workItemId?.let { workItems.record(it, "review.$verdict", details, actorId) }
		return ReviewDecisionOut(detail(reviewId), outcome, fixId)
	}

	private fun rejectEffect(workItemId: String?, runId: String?, reason: String): ReviewRules.RejectEffect {
		if (workItemId == null || runId == null) return ReviewRules.RejectEffect.RECORD
		val stage = jdbc.sql("select stage from work_items where id = :id").param("id", workItemId).query(String::class.java).single()
		return ReviewRules.rejectEffect(stage, newestRun(workItemId) == runId, reason)
	}

	private fun newestRun(workItemId: String): String? =
		jdbc.sql("select id from runs where work_item_id = :id order by created_at desc, id desc limit 1").param("id", workItemId)
			.query(String::class.java).optional().orElse(null)

	/** A work item that fixes a done one whose sample was rejected; the reason is its acceptance draft (contract "반려하면" 3). */
	private fun createFix(workItemId: String, note: String, reviewId: String, actorId: String): String {
		val (title, projectId, responsible) = jdbc.sql("select title, project_id, responsible_user_id from work_items where id = :id")
			.param("id", workItemId).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }.single()
		val created = workItems.create(
			CreateWorkItem(("고침: $title").take(WorkItemService.TITLE_MAX), responsible, projectId,
				acceptance = note.take(WorkItemService.TEXT_MAX), initialStage = "draft", executorKind = "human"),
			actorId,
		)
		jdbc.sql("insert into work_item_links (from_id, to_id, kind, source) values (:from, :to, 'fixes', 'system')")
			.param("from", created.id).param("to", workItemId).update()
		workItems.record(created.id, "work_item.fixes", mapOf("work_item_id" to workItemId, "review_id" to reviewId), actorId)
		return created.id
	}

	// ---------------------------------------------------------------- reads

	fun detail(reviewId: String): ReviewDetail {
		data class Row(val d: ReviewDetail, val workItemId: String?, val runId: String?)
		val row = jdbc.sql(
			"""
			select id, reason, raised_by, state, target_kind, target_ref, evidence::text, created_at, decided_by, decided_at, note,
			       superseded_by, propose_risk_path, proposal_state, work_item_id, run_id
			  from review_items where id = :id
			""".trimIndent(),
		).param("id", reviewId).query { rs, _ ->
			Row(
				ReviewDetail(
					rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
					json.readValue(rs.getString(7), mapType), rs.getObject(8, OffsetDateTime::class.java), rs.getString(9),
					rs.getObject(10, OffsetDateTime::class.java), rs.getString(11), rs.getString(12),
					rs.getString(13)?.let { ReviewProposalView(it, rs.getString(14)) }, null, null, null, null,
				),
				rs.getString(15), rs.getString(16),
			)
		}.optional().orElseThrow { NotFoundException("no review $reviewId") }
		val work = row.workItemId?.let { workItem(it) }
		val run = row.runId?.let { runView(it) }
		val effects = if (row.d.state == "open") effects(row.d, work, row.runId) else null
		val suggested = if (row.d.reason == "sample" && row.d.state == "open") RiskPaths.suggest(run?.files.orEmpty()) else null
		return row.d.copy(workItem = work, run = run, effects = effects, suggestedPattern = suggested)
	}

	private fun workItem(id: String): ReviewWorkItem =
		jdbc.sql(
			"""
			select w.id, w.title, w.external_ref, w.stage, w.version, p.id, p.external_ref, p.name
			  from work_items w left join projects p on p.id = w.project_id where w.id = :id
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			ReviewWorkItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
				rs.getString(6)?.let { ProjectRef(it, rs.getString(7), rs.getString(8)) })
		}.single()

	private fun runView(id: String): ReviewRun {
		val changes = newestChanges(id)
		val files = (strings(changes?.get("files")) + toolPaths(id)).distinct().sorted()
		return jdbc.sql(
			"""
			select r.id, r.end_reason, r.ended_at, r.workspace::text, dr.name, dv.name, r.result_snapshot_sha, r.human_review_state,
			       r.auto_check_state
			  from runs r left join delegations d on d.id = r.delegation_id left join devices dv on dv.id = d.device_id
			  left join device_repositories dr on dr.device_id = d.device_id and dr.repo_key = d.scope ->> 'repo_key'
			 where r.id = :id
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			ReviewRun(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime::class.java),
				rs.getString(4)?.let { json.readValue(it, mapType) }, rs.getString(5), rs.getString(6), files,
				changes?.get("truncated") == true, rs.getString(7), rs.getString(8), rs.getString(9))
		}.single()
	}

	private fun effects(d: ReviewDetail, work: ReviewWorkItem?, runId: String?): ReviewEffects {
		val reject = when (rejectEffect(work?.id, runId, d.reason)) {
			ReviewRules.RejectEffect.RETURN -> "작업을 수행으로 돌린다. 이유가 다음 실행의 지시에 들어간다"
			ReviewRules.RejectEffect.FIX_ITEM -> "완료된 작업이라 고침 작업을 새로 만든다. 이유가 그 수락 조건이 된다"
			ReviewRules.RejectEffect.RECORD -> "작업 단계는 그대로 두고 반려만 기록한다"
		}
		val held = work?.stage == "result_review" && d.reason != "sample"
		val approve = if (held && work != null && workItems.skipsResultReview(work.id)) {
			"다른 열린 위험 검토가 없으면 작업이 완료로 넘어간다(결과 검토를 쓰지 않는 조직). 합치기는 사람이 Git으로 한다"
		} else {
			"승인을 기록한다. 합치기는 사람이 Git으로 한다"
		}
		val needsVersion = rejectEffect(work?.id, runId, d.reason) == ReviewRules.RejectEffect.RETURN
		return ReviewEffects(approve, reject, needsVersion)
	}

	/** The requests of each run of a work item, for its run list. */
	fun forRuns(workItemId: String): Map<String, List<RunReviewView>> =
		jdbc.sql(
			"""
			select r.run_id, r.id, r.reason, r.state, r.target_kind, r.decided_at, r.note, r.superseded_by,
			       (select i.id from inbox_items i where i.source_kind = 'rvw' and i.source_id = r.id and i.state = 'open' limit 1)
			  from review_items r where r.work_item_id = :wid and r.run_id is not null order by r.created_at, r.id
			""".trimIndent(),
		).param("wid", workItemId).query { rs, _ ->
			rs.getString(1) to RunReviewView(rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getObject(6, OffsetDateTime::class.java), rs.getString(7), rs.getString(8), rs.getString(9))
		}.list().groupBy({ it.first }, { it.second })

	/**
	 * Reviews open longer than their organisation's hours are raised to bucket 1 (contract "항목과 순서"
	 * 2). Each organisation by its own setting; a review belongs to its recipient's organisation.
	 */
	@Transactional
	fun promoteWaiting(): Int =
		jdbc.sql("select id from organizations order by created_at, id").query(String::class.java).list().sumOf { org ->
			jdbc.sql(
				"""
				update inbox_items set promoted_at = now()
				 where state = 'open' and kind in ('risk_review', 'sample_review') and promoted_at is null
				   and waiting_since < now() - make_interval(hours => :hours)
				   and user_id in (select user_id from memberships where org_id = :org)
				""".trimIndent(),
			).param("hours", settings.promoteAfterHours(org!!)).param("org", org).update()
		}

	private fun sha256(s: String): String =
		MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

@RestController
@RequestMapping("/api")
class ReviewController(private val reviews: ReviewService, private val permissions: Permissions) {

	@GetMapping("/reviews/{id}")
	fun detail(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): ReviewDetail {
		val me = permissions.person(authorization, "read a review request")
		permissions.see(me, permissions.review(id))
		return reviews.detail(id)
	}

	/** A session token is refused and audited before anything is read: AI never closes a review request. */
	@PostMapping("/reviews/{id}/decision")
	fun decide(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: ReviewDecisionIn,
	): ReviewDecisionOut {
		val me = permissions.person(authorization, "approve or reject a review request")
		permissions.require(me, Act.REVIEW_DECIDE, permissions.review(id))
		return reviews.decide(id, body, "person", me.person!!)
	}
}

/** Raises reviews that waited too long. Runs inside the server, like the session and run sweeps. */
@Component
class ReviewSweeper(private val reviews: ReviewService) {
	@Scheduled(fixedDelayString = "\${zm-baton.review-sweep-ms:60000}", initialDelayString = "\${zm-baton.review-sweep-ms:60000}")
	fun sweep() {
		reviews.promoteWaiting()
	}
}
