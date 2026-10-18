package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// What a delegated run used, the limits it runs under and the provider's signals (FN-0409, G39.4,
// G39.5; contract v1 "질문과 과금 신호", billing-signals). The steps:
//
//  1. A person hands work over on an account. The account must not be stopped: disabled, stopped by
//     an extra-billing signal, at the provider's limit, at its daily limit, or spending far faster
//     than its daily limit allows ([block]). The same check runs again at the claim ([refuseClaim]).
//  2. The claim gives the connector the run's limits ([limitsForClaim]): turns and the price-list
//     cost go to Claude Code's own `--max-turns` and `--max-budget-usd`, minutes to the connector's
//     timer. The cost limit is the smaller of the run's and what is left of today's.
//  3. The executor's result carries what the run used; the run keeps it ([onUsage]). An account that
//     crossed its daily limit or spent half of it within an hour stops its other live runs.
//  4. A run that ended at one of its limits ([onEnded]), a provider limit or a confirmed extra-billing
//     signal puts a wait reason on its work item and a notice on the first screen. Live runs are
//     stopped the way a person's 「실행 취소」 stops them: state `stopping`, which the connector hears
//     at its next lease renewal.
//
// The cost is Claude Code's price-list estimate (`costBasis: list`), not a bill (billing-signals
// 「비용 환산값」). It serves only as the measure of these limits; it never decides extra billing.

/** What runs of one account used in a time window. */
data class UsageTotals(
	val runs: Long,
	@JsonProperty("cost_usd") val costUsd: Double,
	@JsonProperty("input_tokens") val inputTokens: Long,
	@JsonProperty("output_tokens") val outputTokens: Long,
	@JsonProperty("cache_read_tokens") val cacheReadTokens: Long,
	@JsonProperty("cache_creation_tokens") val cacheCreationTokens: Long,
	val turns: Long,
	@JsonProperty("duration_ms") val durationMs: Long,
)

data class DailyUsage(
	@JsonProperty("account_id") val accountId: String,
	val day: LocalDate,
	val totals: UsageTotals,
)

/** Why an account cannot start a run now: the stop cause, the wait reason it means and words for a person. */
data class AccountBlock(
	val cause: String,
	@JsonProperty("wait_reason") val waitReason: String,
	val reason: String,
	@JsonProperty("resets_at") val resetsAt: OffsetDateTime?,
)

@Service
class UsageService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val sessions: SessionService,
	/** The zone whose midnight starts a new day for the daily limit. */
	@Value("\${zm-baton.usage.day-zone:Asia/Seoul}") dayZone: String,
) {
	companion object {
		/** The abnormal-spending rule (G39.4): half of the daily limit within this many minutes. */
		const val BURST_MINUTES = 60L
		val BURST_SHARE: BigDecimal = BigDecimal("0.5")
		/** Claude Code refuses a budget of 0, so the smallest one passed is a cent. */
		val MIN_BUDGET: BigDecimal = BigDecimal("0.01")
		/** A provider limit without a reset time is assumed to last this long (the five-hour window). */
		const val DEFAULT_LIMIT_HOURS = 5L
		/** A five-hour or seven-day window at or above this share is near its limit (billing-signals 경고 2). */
		const val NEAR_LIMIT = 0.9
		val AUTH_METHODS = setOf("subscription_login", "api_key", "cloud_account")
		/** What the connector says a run ran into, and the stop cause it means. */
		val LIMIT_HITS = mapOf("max_turns" to "run_turn_limit", "max_minutes" to "run_minute_limit", "max_budget_usd" to "run_cost_limit")
		/** Claude Code result subtypes that name a limit (Claude Code 2.1.296, measured). */
		val RESULT_LIMITS = mapOf("error_max_turns" to "max_turns", "error_max_budget_usd" to "max_budget_usd")
		/** Phrases that confirm extra billing, looked for only where an error is carried (billing-signals 확정 신호 3·4). */
		val EXTRA_USAGE_PHRASES = listOf("draw from your extra usage", "out of extra usage")
		val LIVE = listOf("claimed", "running")

		/** The method an account's executor signs in with (contract "실행할 때 넘기는 것" 인증). */
		fun authMethod(authKind: String): String = if (authKind == "api_key") "api_key" else "subscription_login"

		private val CAUSE_WORDS = mapOf(
			"run_turn_limit" to "실행 상한(턴)", "run_cost_limit" to "실행 상한(가격표 환산 비용)", "run_minute_limit" to "실행 상한(시간)",
			"daily_cost_limit" to "하루 상한", "usage_burst" to "비정상 소비(짧은 시간에 하루 상한의 절반)",
			"provider_limit" to "공급자 사용 한도", "extra_billing" to "추가 과금 신호",
		)

		fun causeWords(cause: String): String = CAUSE_WORDS[cause] ?: cause
	}

	private val zone: ZoneId = ZoneId.of(dayZone)
	private val mapType = object : TypeReference<Map<String, Any?>>() {}
	private val clock = DateTimeFormatter.ofPattern("M월 d일 HH:mm")

	/** The columns usage needs of an account. */
	data class AccountRow(
		val id: String,
		val name: String,
		val authKind: String,
		val authSource: String,
		val status: String,
		val deviceId: String?,
		val ownerUserId: String?,
		val runTurnLimit: Int?,
		val runCostLimitUsd: BigDecimal?,
		val runMinuteLimit: Int?,
		val dailyCostLimitUsd: BigDecimal?,
		val limitResetsAt: OffsetDateTime?,
		val billingStoppedAt: OffsetDateTime?,
	)

	fun account(id: String, forUpdate: Boolean = false): AccountRow? =
		jdbc.sql(
			"""
			select id, name, auth_kind, auth_source, status, device_id, owner_user_id, run_turn_limit, run_cost_limit_usd,
			       run_minute_limit, daily_cost_limit_usd, limit_resets_at, billing_stopped_at
			  from ai_accounts where id = :id
			""".trimIndent() + if (forUpdate) " for update" else "",
		).param("id", id).query { rs, _ ->
			AccountRow(
				rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
				rs.getString(7), rs.getObject(8) as Int?, rs.getBigDecimal(9), rs.getObject(10) as Int?, rs.getBigDecimal(11),
				rs.getObject(12, OffsetDateTime::class.java), rs.getObject(13, OffsetDateTime::class.java),
			)
		}.optional().orElse(null)

	// ---------------------------------------------------------------- totals

	fun now(): OffsetDateTime = jdbc.sql("select now()").query(OffsetDateTime::class.java).single()

	/** The start of today in the day zone, as a time. */
	fun dayStart(now: OffsetDateTime): OffsetDateTime = now.atZoneSameInstant(zone).toLocalDate().atStartOfDay(zone).toOffsetDateTime()

	/** What the account's runs used from [from], by the time each reported its result. */
	fun totals(accountId: String, from: OffsetDateTime, to: OffsetDateTime? = null): UsageTotals =
		jdbc.sql(
			"""
			select $TOTALS from runs
			 where account_id = :acc and usage_at >= :from and (cast(:to as timestamptz) is null or usage_at < :to)
			""".trimIndent(),
		).param("acc", accountId).param("from", from).param("to", to).query { rs, _ -> totalsOf(rs, 1) }.single()

	/** What each account used per day for the last [days] days in the day zone, newest first; days with no run are left out. */
	fun daily(accountId: String?, days: Int, now: OffsetDateTime = now()): List<DailyUsage> {
		val from = dayStart(now).minusDays(days.toLong() - 1)
		val stmt = jdbc.sql(
			"""
			select account_id, (usage_at at time zone :zone)::date as day, $TOTALS from runs
			 where account_id is not null and usage_at >= :from${if (accountId != null) " and account_id = :acc" else ""}
			 group by account_id, day order by day desc, account_id
			""".trimIndent(),
		).param("zone", zone.id).param("from", from)
		if (accountId != null) stmt.param("acc", accountId)
		return stmt.query { rs, _ -> DailyUsage(rs.getString(1), rs.getObject(2, LocalDate::class.java), totalsOf(rs, 3)) }.list()
	}

	private val TOTALS = """
		count(*), coalesce(sum((usage ->> 'cost_usd')::numeric), 0),
		coalesce(sum((usage ->> 'input_tokens')::numeric), 0), coalesce(sum((usage ->> 'output_tokens')::numeric), 0),
		coalesce(sum((usage ->> 'cache_read_tokens')::numeric), 0), coalesce(sum((usage ->> 'cache_creation_tokens')::numeric), 0),
		coalesce(sum((usage ->> 'num_turns')::numeric), 0), coalesce(sum((usage ->> 'duration_ms')::numeric), 0)
	""".trimIndent()

	private fun totalsOf(rs: java.sql.ResultSet, at: Int) = UsageTotals(
		rs.getLong(at), rs.getBigDecimal(at + 1).setScale(4, RoundingMode.HALF_UP).toDouble(), rs.getBigDecimal(at + 2).toLong(),
		rs.getBigDecimal(at + 3).toLong(), rs.getBigDecimal(at + 4).toLong(), rs.getBigDecimal(at + 5).toLong(),
		rs.getBigDecimal(at + 6).toLong(), rs.getBigDecimal(at + 7).toLong(),
	)

	private fun spent(accountId: String, from: OffsetDateTime): BigDecimal =
		jdbc.sql("select coalesce(sum((usage ->> 'cost_usd')::numeric), 0) from runs where account_id = :acc and usage_at >= :from")
			.param("acc", accountId).param("from", from).query(BigDecimal::class.java).single()

	// ---------------------------------------------------------------- blocks and limits

	/** Why this account cannot start a run now, or null when it can (step 1). */
	fun block(acc: AccountRow, now: OffsetDateTime = now()): AccountBlock? {
		if (acc.status == "disabled") return AccountBlock("disabled", "waiting_account", "비활성화한 AI 계정이다. AI 계정 화면에서 다시 쓰기로 바꾸거나 다른 계정을 고른다", null)
		if (acc.billingStoppedAt != null) {
			return AccountBlock(
				"extra_billing", "billing_stopped",
				"추가 과금 신호를 받아 멈춘 AI 계정이다(${at(acc.billingStoppedAt)}). 공급자 쪽을 확인한 뒤 AI 계정 화면에서 「추가 과금 멈춤 풀기」를 누른다", null,
			)
		}
		if (acc.status == "limit_reached" && (acc.limitResetsAt == null || acc.limitResetsAt.isAfter(now))) {
			return AccountBlock(
				"provider_limit", "waiting_limit_reset",
				"공급자의 사용 한도에 닿은 AI 계정이다.${acc.limitResetsAt?.let { " ${at(it)}에 초기화된다" } ?: ""}", acc.limitResetsAt,
			)
		}
		val cap = acc.dailyCostLimitUsd ?: return null
		val start = dayStart(now)
		val today = spent(acc.id, start)
		if (today >= cap) {
			val next = start.plusDays(1)
			return AccountBlock(
				"daily_cost_limit", "waiting_limit_reset",
				"오늘 사용량(가격표 환산 ${usd(today)})이 하루 상한 ${usd(cap)}에 닿았다. ${at(next)}에 다시 센다. 상한을 올리거나 다른 계정을 고른다", next,
			)
		}
		val recent = spent(acc.id, now.minusMinutes(BURST_MINUTES))
		if (recent >= cap.multiply(BURST_SHARE)) {
			val until = now.plusMinutes(BURST_MINUTES)
			return AccountBlock(
				"usage_burst", "waiting_limit_reset",
				"최근 ${BURST_MINUTES}분 사용량(가격표 환산 ${usd(recent)})이 하루 상한 ${usd(cap)}의 절반을 넘어 비정상 소비로 보고 멈췄다. 늦어도 ${at(until)}부터 다시 맡길 수 있다", until,
			)
		}
		return null
	}

	/**
	 * The limits of a run claimed now (step 2): `max_turns`, `max_budget_usd`, `max_minutes` and
	 * `budget_from` (`run` or `daily`: which limit the budget is). Absent keys have no limit.
	 */
	fun limitsForClaim(acc: AccountRow, now: OffsetDateTime = now()): Map<String, Any?> {
		val out = linkedMapOf<String, Any?>()
		acc.runTurnLimit?.let { out["max_turns"] = it }
		val left = acc.dailyCostLimitUsd?.let { (it - spent(acc.id, dayStart(now))).max(MIN_BUDGET) }
		val run = acc.runCostLimitUsd
		val budget = listOfNotNull(run, left).minOrNull()
		if (budget != null) {
			out["max_budget_usd"] = budget.setScale(2, RoundingMode.DOWN).max(MIN_BUDGET)
			out["budget_from"] = if (run != null && budget.compareTo(run) == 0) "run" else "daily"
		}
		acc.runMinuteLimit?.let { out["max_minutes"] = it }
		return out
	}

	/**
	 * At the claim (step 1 again): a run whose account is stopped now is not started. It ends
	 * `cancelled` before any executor (contract "상태": 띄우기 전, 서버의 중단), its delegation is
	 * revoked, its work item gets the block's wait reason and the first screen a notice. Returns the
	 * reason, or null to let the claim go on. Runs in its own transaction, so the ending stays when
	 * the claim is then refused. Only the device the delegation names can cause it.
	 */
	fun refuseClaim(runId: String, deviceId: String): String? {
		val row = jdbc.sql(
			"""
			select r.work_item_id, r.delegation_id, r.account_id from runs r join delegations d on d.id = r.delegation_id
			 where r.id = :id and r.state = 'requested' and d.device_id = :device and d.revoked_at is null and d.expires_at > now()
			   for update of r
			""".trimIndent(),
		).param("id", runId).param("device", deviceId).query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getString(3)) }
			.optional().orElse(null) ?: return null
		val (workItemId, delegationId, accountId) = row
		val acc = accountId?.let { account(it) } ?: return null
		val block = block(acc) ?: return null
		jdbc.sql("select id from work_items where id = :id for update").param("id", workItemId).query(String::class.java).single()
		jdbc.sql("update runs set state = 'ended', end_reason = 'cancelled', ended_at = now(), stopped_by = :cause where id = :id")
			.param("cause", block.cause.takeIf { it != "disabled" }).param("id", runId).update()
		jdbc.sql("update delegations set revoked_at = now() where id = :id and revoked_at is null").param("id", delegationId).update()
		addWait(workItemId, block.waitReason)
		sessions.audit("system", SessionService.SYSTEM_ACTOR, "run.refused_by_account", "run", runId,
			mapOf("work_item_id" to workItemId, "account_id" to acc.id, "cause" to block.cause, "reason" to block.reason))
		if (block.cause != "disabled") {
			raise(
				"usage_alert|account|${block.cause}|${acc.id}|${dayStart(now()).toLocalDate()}",
				evidence(block.cause, acc, runId, workItemId, mapOf("reason" to block.reason, "resets_at" to block.resetsAt?.toString())),
				"${acc.name}: ${block.reason}", immediate = true,
			)
		}
		return "이 실행을 시작하지 않았다. ${block.reason}"
	}

	// ---------------------------------------------------------------- reports (inside RunService.ingest)

	/** A start report: the sign-in method the connector arranged (contract "실행할 때 넘기는 것" 인증 2). */
	fun onStarted(runId: String, payload: Map<String, Any?>) {
		val method = payload["auth_method"] as? String ?: return
		if (method !in AUTH_METHODS) return
		jdbc.sql("update runs set auth_method = :m where id = :id").param("m", method).param("id", runId).update()
	}

	/** A usage report (step 3): the result's totals, or a limit signal. */
	fun onUsage(runId: String, workItemId: String, accountId: String?, payload: Map<String, Any?>) {
		when (payload["source"]) {
			"result" -> onResultUsage(runId, accountId, payload)
			"rate_limit_event" -> onRateLimit(runId, workItemId, accountId, payload)
		}
	}

	private fun onResultUsage(runId: String, accountId: String?, payload: Map<String, Any?>) {
		val usage = runUsage(payload)
		jdbc.sql("update runs set usage = cast(:u as jsonb), usage_at = now() where id = :id")
			.param("u", json.writeValueAsString(usage)).param("id", runId).update()
		signal(runId, accountId, "tokens", usage)
		val acc = accountId?.let { account(it) } ?: return
		val block = block(acc) ?: return
		if (block.cause != "daily_cost_limit" && block.cause != "usage_burst") return
		// No new run starts on the account until the block ends; live ones stop.
		stopAccountRuns(acc, block, exceptRunId = runId)
		raise(
			"usage_alert|account|${block.cause}|${acc.id}|${dayStart(now()).toLocalDate()}",
			evidence(block.cause, acc, runId, jdbc.sql("select work_item_id from runs where id = :id").param("id", runId).query(String::class.java).single(),
				mapOf("reason" to block.reason, "resets_at" to block.resetsAt?.toString())),
			"${acc.name}: ${block.reason}", immediate = false,
		)
	}

	/**
	 * What a result says the run used. Claude Code 2.1.296 puts the run's totals in `modelUsage` (one
	 * entry per model); the top-level `usage` can be all zeros (measured), so it is only the fallback.
	 */
	fun runUsage(payload: Map<String, Any?>): Map<String, Any?> {
		val models = payload["model_usage"] as? Map<*, *>
		fun sum(modelKey: String, usageKey: String): Long {
			val fromModels = models?.values?.sumOf { ((it as? Map<*, *>)?.get(modelKey) as? Number)?.toLong() ?: 0L }
			if (fromModels != null && models.isNotEmpty()) return fromModels
			return ((payload["usage"] as? Map<*, *>)?.get(usageKey) as? Number)?.toLong() ?: 0L
		}
		val cost = (payload["total_cost_usd"] as? Number)?.toDouble()
			?: models?.values?.sumOf { ((it as? Map<*, *>)?.get("costUSD") as? Number)?.toDouble() ?: 0.0 } ?: 0.0
		return linkedMapOf(
			"cost_usd" to BigDecimal.valueOf(cost.coerceAtLeast(0.0)).setScale(6, RoundingMode.HALF_UP),
			"input_tokens" to sum("inputTokens", "input_tokens"),
			"output_tokens" to sum("outputTokens", "output_tokens"),
			"cache_read_tokens" to sum("cacheReadInputTokens", "cache_read_input_tokens"),
			"cache_creation_tokens" to sum("cacheCreationInputTokens", "cache_creation_input_tokens"),
			"num_turns" to ((payload["num_turns"] as? Number)?.toLong() ?: 0L),
			"duration_ms" to ((payload["duration_ms"] as? Number)?.toLong() ?: 0L),
			"duration_api_ms" to ((payload["duration_api_ms"] as? Number)?.toLong() ?: 0L),
			"models" to models?.keys?.mapNotNull { (it as? String)?.take(100) }.orEmpty(),
			"result_subtype" to (payload["subtype"] as? String)?.take(60),
		)
	}

	/**
	 * Claude Code's `rate_limit_event` (billing-signals 「감지 규칙은 세 단계로 나눈다」). A confirmed
	 * extra-billing signal stops the account's runs; the provider refusing more stops them until its
	 * reset; anything else is recorded, with a digest notice when a window is nearly full.
	 */
	private fun onRateLimit(runId: String, workItemId: String, accountId: String?, payload: Map<String, Any?>) {
		val info = payload["rate_limit"] as? Map<*, *> ?: emptyMap<String, Any?>()
		val confirmed = info["isUsingOverage"] == true || info["overageInUse"] == true || info["errorCode"] == "credits_required" ||
			(info["rateLimitType"] == "overage" && info["status"] in setOf("allowed", "allowed_warning"))
		signal(runId, accountId, if (confirmed) "extra_usage_detected" else "limit_ratio", payload)
		val acc = accountId?.let { account(it) }
		when {
			confirmed -> extraBilling(runId, workItemId, acc, payload)
			info["status"] == "rejected" -> providerLimit(runId, workItemId, acc, payload, resetsAt(info, payload))
			else -> {
				jdbc.sql(
					"""
					update runs set billing_location = case when billing_location = 'extra_usage' then billing_location else :loc end,
					       billing_detection = coalesce(billing_detection, 'not_detected'), billing_evidence = cast(:e as jsonb)
					 where id = :id
					""".trimIndent(),
				).param("loc", if (acc?.authKind == "api_key") "api" else "subscription_limit")
					.param("e", json.writeValueAsString(payload)).param("id", runId).update()
				val near = nearLimit(info, payload)
				if (acc != null && near != null) {
					raise(
						"usage_alert|near|${acc.id}|${dayStart(now()).toLocalDate()}", evidence("near_limit", acc, runId, workItemId, mapOf("window" to near)),
						"${acc.name}: 공급자 사용 한도에 가까워졌다($near). 그 계정의 실행은 계속된다", immediate = false,
					)
				}
			}
		}
	}

	/** An error the executor's result carried (never the model's own words): extra-billing phrases confirm extra billing. */
	fun onResultError(runId: String, workItemId: String, accountId: String?, payload: Map<String, Any?>) {
		val text = (payload["text"] as? String)?.lowercase() ?: return
		val phrase = EXTRA_USAGE_PHRASES.firstOrNull { it in text } ?: return
		signal(runId, accountId, "extra_usage_detected", mapOf("source" to "result_error", "phrase" to phrase))
		extraBilling(runId, workItemId, accountId?.let { account(it) }, mapOf("source" to "result_error", "phrase" to phrase))
	}

	/** An end report (step 4): a run that stopped at one of its own limits, and the sign-in the executor really used. */
	fun onEnded(runId: String, workItemId: String, accountId: String?, reason: String, payload: Map<String, Any?>) {
		val acc = accountId?.let { account(it) }
		val run = jdbc.sql("select limits::text, usage ->> 'result_subtype', auth_method, billing_location from runs where id = :id")
			.param("id", runId).query { rs, _ -> listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.single()
		val limits = run[0]?.let { json.readValue(it, mapType) }.orEmpty()
		val hit = (payload["limit_hit"] as? String)?.takeIf { it in LIMIT_HITS } ?: RESULT_LIMITS[run[1]]
		if (hit != null && reason != "submitted") {
			val cause = if (hit == "max_budget_usd" && limits["budget_from"] == "daily") "daily_cost_limit" else LIMIT_HITS.getValue(hit)
			jdbc.sql("update runs set stopped_by = coalesce(stopped_by, :c) where id = :id").param("c", cause).param("id", runId).update()
			addWait(workItemId, "waiting_limit_reset")
			val limit = when (hit) {
				"max_turns" -> "턴 ${limits["max_turns"] ?: "?"}"
				"max_minutes" -> "${limits["max_minutes"] ?: "?"}분"
				else -> "가격표 환산 ${limits["max_budget_usd"]?.let { usd(BigDecimal(it.toString())) } ?: "?"}"
			}
			raise(
				"usage_alert|run|$cause|$runId", evidence(cause, acc, runId, workItemId, mapOf("limit" to limits, "hit" to hit)),
				"${causeWords(cause)}($limit)에 닿아 실행을 멈췄다: ${title(workItemId)}", immediate = true,
			)
		}
		val auth = payload["auth"] as? Map<*, *>
		val observed = (auth?.get("method") as? String)?.takeIf { it in AUTH_METHODS }
		if (observed != null && observed != run[2]) {
			jdbc.sql("update runs set auth_method = :m where id = :id").param("m", observed).param("id", runId).update()
		}
		if (acc != null && observed != null && observed != authMethod(acc.authKind)) {
			raise(
				"usage_alert|auth|$runId", evidence("auth_mismatch", acc, runId, workItemId, mapOf("expected" to authMethod(acc.authKind), "observed" to observed)),
				"${acc.name}: 고른 인증 방식(${methodWords(authMethod(acc.authKind))})이 아니라 ${methodWords(observed)}(으)로 돌았다", immediate = true,
			)
		}
		if (run[3] == null) {
			val api = acc?.authKind == "api_key" || observed == "api_key"
			jdbc.sql("update runs set billing_location = :loc, billing_detection = coalesce(billing_detection, :det) where id = :id")
				.param("loc", if (api) "api" else "unknown").param("det", if (api) "not_detected" else "unavailable").param("id", runId).update()
		}
	}

	// ---------------------------------------------------------------- stops

	private fun extraBilling(runId: String, workItemId: String, acc: AccountRow?, evidence: Map<String, Any?>) {
		jdbc.sql(
			"""
			update runs set billing_location = 'extra_usage', billing_detection = 'detected', billing_evidence = cast(:e as jsonb),
			       stopped_by = coalesce(stopped_by, 'extra_billing')
			 where id = :id
			""".trimIndent(),
		).param("e", json.writeValueAsString(evidence)).param("id", runId).update()
		stopRun(runId, "extra_billing")
		addWait(workItemId, "billing_stopped")
		if (acc == null) return
		val first = jdbc.sql(
			"update ai_accounts set billing_stopped_at = now(), version = version + 1, updated_at = now() where id = :id and billing_stopped_at is null",
		).param("id", acc.id).update() == 1
		if (first) sessions.audit("system", SessionService.SYSTEM_ACTOR, "account.billing_stopped", "acc", acc.id, mapOf("run_id" to runId))
		val block = AccountBlock("extra_billing", "billing_stopped", "추가 과금 신호를 받았다", null)
		stopAccountRuns(acc, block, exceptRunId = runId)
		raise(
			"usage_alert|extra_billing|${acc.id}|$runId", evidence("extra_billing", acc, runId, workItemId, mapOf("signal" to evidence)),
			"${acc.name}: 공급자가 이 사용을 구독 한도가 아닌 추가 과금으로 처리한다는 신호를 받아 실행을 멈췄다. 계속하면 추가 결제가 생길 수 있다", immediate = true,
		)
	}

	private fun providerLimit(runId: String, workItemId: String, acc: AccountRow?, payload: Map<String, Any?>, resets: OffsetDateTime?) {
		jdbc.sql("update runs set stopped_by = coalesce(stopped_by, 'provider_limit'), billing_evidence = cast(:e as jsonb) where id = :id")
			.param("e", json.writeValueAsString(payload)).param("id", runId).update()
		stopRun(runId, "provider_limit")
		addWait(workItemId, "waiting_limit_reset")
		if (acc == null) return
		val until = resets ?: now().plusHours(DEFAULT_LIMIT_HOURS)
		jdbc.sql("update ai_accounts set status = 'limit_reached', limit_resets_at = :t, version = version + 1, updated_at = now() where id = :id and status <> 'disabled'")
			.param("t", until).param("id", acc.id).update()
		sessions.audit("system", SessionService.SYSTEM_ACTOR, "account.limit_reached", "acc", acc.id, mapOf("run_id" to runId, "resets_at" to until.toString()))
		val block = AccountBlock("provider_limit", "waiting_limit_reset", "공급자의 사용 한도에 닿았다", until)
		stopAccountRuns(acc, block, exceptRunId = runId)
		raise(
			"usage_alert|provider_limit|${acc.id}|${until.toInstant()}", evidence("provider_limit", acc, runId, workItemId, mapOf("resets_at" to until.toString())),
			"${acc.name}: 공급자의 사용 한도에 닿아 실행을 멈췄다. ${at(until)}에 초기화된다", immediate = true,
		)
	}

	/** The account's other live runs stop the way a person's 「실행 취소」 stops them; their work items get the block's wait reason. */
	private fun stopAccountRuns(acc: AccountRow, block: AccountBlock, exceptRunId: String) {
		val live = jdbc.sql("select id, work_item_id from runs where account_id = :acc and state in ('claimed', 'running') and id <> :run order by id")
			.param("acc", acc.id).param("run", exceptRunId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()
		for ((id, workItemId) in live) {
			jdbc.sql("update runs set stopped_by = coalesce(stopped_by, :c) where id = :id").param("c", block.cause).param("id", id).update()
			if (!stopRun(id, block.cause)) continue
			addWait(workItemId, block.waitReason)
			raise(
				"usage_alert|run|${block.cause}|$id", evidence(block.cause, acc, id, workItemId, mapOf("reason" to block.reason)),
				"${acc.name}: ${causeWords(block.cause)} 때문에 실행을 멈췄다: ${title(workItemId)}", immediate = true,
			)
		}
	}

	/**
	 * Asks the connector to stop a live run, as RunControlService.cancel does for a person: state
	 * `stopping` (the lease renewal answers `stop: cancel`) and the delegation revoked. The end report
	 * then decides `outcome_unknown` as usual.
	 */
	private fun stopRun(runId: String, cause: String): Boolean {
		val stopped = jdbc.sql("update runs set state = 'stopping' where id = :id and state in ('claimed', 'running')").param("id", runId).update() == 1
		if (!stopped) return false
		jdbc.sql("update delegations set revoked_at = now() where id = (select delegation_id from runs where id = :id) and revoked_at is null")
			.param("id", runId).update()
		sessions.audit("system", SessionService.SYSTEM_ACTOR, "run.stop_requested", "run", runId, mapOf("cause" to cause))
		return true
	}

	// ---------------------------------------------------------------- notices and helpers

	/**
	 * A usage notice on the first screen: bucket 5 「표본 검토와 나머지 알림」, immediate when work stopped
	 * (DEC-0092 5: it waits for my decision to go on), digest for a warning. The same evidence again
	 * counts up and opens a judged notice again, because each stop is new work for the person. It goes
	 * to the person responsible for the stopped work item.
	 */
	fun raise(dedupeKey: String, evidence: Map<String, Any?>, summary: String, immediate: Boolean) {
		val evidenceJson = json.writeValueAsString(evidence)
		val person = jdbc.sql("select responsible_user_id from work_items where id = :id").param("id", evidence["work_item_id"] as String?)
			.query(String::class.java).optional().orElse(null)
			?: jdbc.sql("select owner_user_id from organizations order by created_at, id limit 1").query(String::class.java).single()
		val id = jdbc.sql(
			"""
			insert into notices (id, kind, evidence, dedupe_key, recipients)
			values (:id, 'usage_alert', cast(:e as jsonb), :key, array[:person])
			on conflict (dedupe_key) do update
			   set repeat_count = notices.repeat_count + 1, last_seen_at = now(), evidence = excluded.evidence,
			       recipients = array(select distinct recipient from unnest(notices.recipients || excluded.recipients) as recipient order by recipient),
			       status = 'open', status_by = null, status_at = null, status_note = null
			returning id
			""".trimIndent(),
		).param("id", Ids.notice()).param("e", evidenceJson).param("key", dedupeKey).param("person", person)
			.query(String::class.java).single()
		sessions.audit("system", SessionService.SYSTEM_ACTOR, "notice.raised", "ntc", id, mapOf("kind" to "usage_alert", "cause" to evidence["cause"]))
		jdbc.sql(
			"""
			insert into inbox_items (id, user_id, bucket, kind, source_kind, source_id, summary, detail, notify)
			values (:id, :user, 5, 'notice', 'ntc', :notice, :summary, cast(:detail as jsonb), :notify)
			on conflict (source_id, user_id) where state = 'open' and kind = 'notice' do nothing
			""".trimIndent(),
		).param("id", Ids.inbox()).param("user", person).param("notice", id).param("summary", summary.take(300))
			.param("detail", evidenceJson).param("notify", if (immediate) "immediate" else "digest").update()
	}

	private fun evidence(cause: String, acc: AccountRow?, runId: String, workItemId: String, more: Map<String, Any?>): Map<String, Any?> =
		linkedMapOf<String, Any?>(
			"notice_kind" to "usage_alert", "cause" to cause, "account_id" to acc?.id, "account_name" to acc?.name,
			"run_id" to runId, "work_item_id" to workItemId,
		) + more

	private fun signal(runId: String, accountId: String?, kind: String, payload: Map<String, Any?>) {
		jdbc.sql("insert into usage_signals (id, run_id, account_id, kind, payload) values (:id, :run, :acc, :kind, cast(:p as jsonb))")
			.param("id", Ids.usageSignal()).param("run", runId).param("acc", accountId).param("kind", kind)
			.param("p", json.writeValueAsString(payload)).update()
	}

	/** A server-set wait reason, like RunService's; a closed work item gets none. */
	private fun addWait(workItemId: String, reason: String) {
		jdbc.sql(
			"""
			update work_items
			   set wait_reasons = array_append(wait_reasons, :reason), version = version + 1, updated_at = now()
			 where id = :wid and not (:reason = any(wait_reasons)) and stage not in ('done', 'cancelled')
			""".trimIndent(),
		).param("reason", reason).param("wid", workItemId).update()
	}

	private fun title(workItemId: String): String =
		jdbc.sql("select title from work_items where id = :id").param("id", workItemId).query(String::class.java).optional().orElse(workItemId).take(120)

	/** `resetsAt` of the event (epoch seconds), or of its windows' earliest full one. */
	private fun resetsAt(info: Map<*, *>, payload: Map<String, Any?>): OffsetDateTime? {
		val direct = (info["resetsAt"] as? Number)?.toLong()
		val fromWindows = (payload["windows"] as? Map<*, *>)?.values?.mapNotNull { w ->
			val m = w as? Map<*, *> ?: return@mapNotNull null
			if (((m["utilization"] as? Number)?.toDouble() ?: 0.0) >= 1.0) (m["resetsAt"] as? Number)?.toLong() else null
		}?.minOrNull()
		val seconds = direct ?: fromWindows ?: return null
		return OffsetDateTime.ofInstant(Instant.ofEpochSecond(seconds), zone)
	}

	/** 「5시간 92%」 when a window is at or above [NEAR_LIMIT], or the event says `allowed_warning`. */
	private fun nearLimit(info: Map<*, *>, payload: Map<String, Any?>): String? {
		val windows = (payload["windows"] as? Map<*, *>).orEmpty()
		for ((name, w) in windows) {
			val u = ((w as? Map<*, *>)?.get("utilization") as? Number)?.toDouble() ?: continue
			if (u >= NEAR_LIMIT) return "${windowWords(name.toString())} ${(u * 100).toInt()}%"
		}
		return if (info["status"] == "allowed_warning") "경고" else null
	}

	private fun windowWords(name: String) = when (name) {
		"five_hour" -> "5시간"
		"seven_day" -> "7일"
		else -> name
	}

	private fun methodWords(method: String) = when (method) {
		"subscription_login" -> "구독 로그인"
		"api_key" -> "API 키"
		"cloud_account" -> "클라우드 계정"
		else -> method
	}

	private fun at(t: OffsetDateTime): String = clock.format(t.atZoneSameInstant(zone))

	private fun usd(v: BigDecimal): String = "$" + v.setScale(2, RoundingMode.HALF_UP).toPlainString()
}
