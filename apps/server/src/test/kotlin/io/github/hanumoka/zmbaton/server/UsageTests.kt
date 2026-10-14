package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AI accounts on delegated runs (FN-0501), what runs used and their limits (FN-0409), and the
 * provider's limit and extra-billing signals (contract v1 "질문과 과금 신호"). Each test registers
 * its own account, so their usage never adds up across tests. Against a real PostgreSQL 17.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(properties = ["zm-baton.run-sweep-ms=3600000"])
class UsageTests @Autowired constructor(
	private val accounts: AccountService,
	private val usage: UsageService,
	private val delegations: DelegationService,
	private val runs: RunService,
	private val reads: ReadService,
	private val workItems: WorkItemService,
	private val sessions: SessionService,
	private val jdbc: JdbcClient,
) {
	private val commit = "c".repeat(40)

	private data class Setup(val device: String, val repoKey: String)

	private fun setup(): Setup {
		val device = sessions.registerDevice(RegisterDevice("usage-pc", "windows"), person = SessionService.OWNER).deviceId
		val key = "git:" + UUID.randomUUID().toString().replace("-", "") + "00000000"
		delegations.addRepository(device, RepositoryUpsert(key, "demo"), actor = SessionService.OWNER)
		return Setup(device, key)
	}

	private fun account(vararg fields: Pair<String, Any?>): String =
		accounts.register(mapOf("name" to "시험 계정 ${UUID.randomUUID().toString().take(6)}", "auth_kind" to "personal_subscription") + fields, actor = SessionService.OWNER)
			.account.id

	private fun work(title: String = "사용량 작업") =
		workItems.create(CreateWorkItem(title, "usr_owner", acceptance = "a.txt", initialStage = "ready", executorKind = "human"), actor = SessionService.OWNER).id

	private fun version(workId: String) = workItems.detail(workId).item.version

	private fun delegate(s: Setup, workId: String, accountId: String?) =
		delegations.delegate(workId, DelegateIn("claude_code", s.device, s.repoKey, "main", listOf("Read"), version(workId), accountId = accountId), actor = SessionService.OWNER)

	private fun newestRun(workId: String): String =
		jdbc.sql("select id from runs where work_item_id = :id order by created_at desc, id desc limit 1").param("id", workId)
			.query(String::class.java).single()

	private fun event(gen: Long, seq: Long, kind: String, payload: Map<String, Any?>) =
		EventIn("evt_" + UUID.randomUUID(), gen, seq, kind, payload)

	private fun ingest(runId: String, vararg events: EventIn) = runs.ingest(runId, EventBatch(2, events.toList()))

	private fun started(gen: Long, s: Setup, runId: String, auth: String = "subscription_login") =
		event(gen, 1, "lifecycle", mapOf("phase" to "started", "session_id" to UUID.randomUUID().toString(), "executor" to "claude_code",
			"pid" to 7, "auth_method" to auth,
			"workspace" to mapOf("repo_key" to s.repoKey, "base_commit" to commit, "branch" to "zm-baton/$runId", "base_ref" to "main")))

	/** A result's usage as the connector sends it from Claude Code 2.1.296 (totals in model_usage). */
	private fun result(gen: Long, seq: Long, cost: Double, subtype: String = "success", turns: Int = 3) =
		event(gen, seq, "usage", mapOf("source" to "result", "subtype" to subtype, "total_cost_usd" to cost, "num_turns" to turns,
			"duration_ms" to 2160, "usage" to mapOf("input_tokens" to 0, "output_tokens" to 0),
			"model_usage" to mapOf("claude-opus-5-5" to mapOf("inputTokens" to 2, "outputTokens" to 140, "cacheReadInputTokens" to 1556,
				"cacheCreationInputTokens" to 1739, "costUSD" to cost))))

	private fun ended(gen: Long, seq: Long, reason: String, started: Boolean, result: Boolean, extra: Map<String, Any?> = emptyMap()) =
		event(gen, seq, "lifecycle", mapOf("phase" to "ended", "end_reason" to reason, "executor_started" to started, "result_seen" to result) + extra)

	private fun rateLimit(gen: Long, seq: Long, info: Map<String, Any?>, windows: Map<String, Any?> = emptyMap()) =
		event(gen, seq, "usage", mapOf("source" to "rate_limit_event", "rate_limit" to info, "windows" to windows))

	/** A started run on [accountId]: delegated, claimed and reported started. */
	private fun running(s: Setup, workId: String, accountId: String?): Triple<String, Long, Claimed> {
		delegate(s, workId, accountId)
		val runId = newestRun(workId)
		val claim = runs.claim(runId, s.device)
		ingest(runId, started(claim.generation, s, runId))
		return Triple(runId, claim.generation, claim)
	}

	private fun waits(workId: String) = workItems.detail(workId).item.waitReasons

	private fun run(workId: String, runId: String) = reads.runs(workId, 20).single { it.id == runId }

	private fun notices(runOrAccount: String): List<Pair<String, String>> =
		jdbc.sql(
			"""
			select i.summary, i.notify from inbox_items i join notices n on n.id = i.source_id
			 where n.kind = 'usage_alert' and i.bucket = 5 and i.state = 'open'
			   and (n.evidence ->> 'run_id' = :k or n.evidence ->> 'account_id' = :k)
			""".trimIndent(),
		).param("k", runOrAccount).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()

	// ---------------------------------------------------------------- accounts on runs

	@Test
	fun `a run handed over without an account goes on the seeded login and its claim carries the method and limits`() {
		val s = setup()
		val workId = work()
		delegate(s, workId, null)
		val runId = newestRun(workId)
		val claim = runs.claim(runId, s.device)
		assertEquals("acc_owner_login", claim.account!!.id)
		assertEquals("subscription_login", claim.account.authMethod)
		assertEquals("device_login", claim.account.authSource)
		assertEquals(200, claim.limits!!.maxTurns)
		assertEquals(60, claim.limits.maxMinutes)
		assertTrue(claim.limits.maxBudgetUsd!! <= BigDecimal("30.00") && claim.limits.maxBudgetUsd > BigDecimal.ZERO)
		val view = run(workId, runId)
		assertEquals("acc_owner_login", view.account!!.id)
		assertEquals("personal_subscription", view.account.authKind)
		assertEquals(200, view.limits!!["max_turns"])
		val audited = jdbc.sql("select details ->> 'account_id' from audit_log where action = 'work_item.delegated' and target_id = :id")
			.param("id", workId).query(String::class.java).single()
		assertEquals("acc_owner_login", audited)
	}

	@Test
	fun `the chosen account goes on the delegation and the run, and the start report records the sign-in method`() {
		val s = setup()
		val acc = account("run_turn_limit" to 7, "run_cost_limit_usd" to 2.5, "run_minute_limit" to 15, "daily_cost_limit_usd" to null)
		val workId = work()
		val (runId, _, claim) = running(s, workId, acc)
		assertEquals(acc, claim.account!!.id)
		assertEquals(7, claim.limits!!.maxTurns)
		assertEquals(BigDecimal("2.50"), claim.limits.maxBudgetUsd)
		assertEquals("run", claim.limits.budgetFrom)
		assertEquals(15, claim.limits.maxMinutes)
		val dlgAccount = jdbc.sql("select d.account_id from runs r join delegations d on d.id = r.delegation_id where r.id = :id")
			.param("id", runId).query(String::class.java).single()
		assertEquals(acc, dlgAccount)
		assertEquals("subscription_login", run(workId, runId).authMethod)
	}

	@Test
	fun `a disabled account takes no work and the absent default never picks an API key`() {
		val s = setup()
		val acc = account()
		val disabled = accounts.disable(acc, accounts.get(acc).version, actor = SessionService.OWNER)
		assertEquals("disabled", disabled.account.status)
		assertEquals("waiting_account", disabled.account.block!!.waitReason)
		val e = assertFailsWith<ConflictException> { delegate(s, work(), acc) }
		assertContains(e.message!!, "비활성화한 AI 계정")
		accounts.enable(acc, accounts.get(acc).version, actor = SessionService.OWNER)
		delegate(s, work(), acc)

		// The default for a client that names no account is a subscription login, never a key.
		val key = accounts.register(mapOf("name" to "키 계정", "auth_kind" to "api_key"), actor = SessionService.OWNER).account
		assertEquals("api_key", key.authMethod)
		assertEquals("env:ANTHROPIC_API_KEY", key.authSource)
		val w = work()
		delegate(s, w, null)
		assertEquals("acc_owner_login", run(w, newestRun(w)).account!!.id)
	}

	@Test
	fun `a continuing run on another account gets neither the session nor the worktree`() {
		val s = setup()
		val first = account()
		val second = account()
		val workId = work()
		val (runId, gen, _) = running(s, workId, first)
		ingest(runId, ended(gen, 2, "cancelled", true, false))
		delegate(s, workId, second)
		val next = runs.claim(newestRun(workId), s.device)
		val resume = next.resume!!
		assertTrue(resume.sameDevice)
		assertFalse(resume.sameAccount)
		assertNull(resume.sessionId)
		assertNull(resume.workspace)
		assertEquals(second, next.account!!.id)
	}

	// ---------------------------------------------------------------- usage

	@Test
	fun `a result's usage is kept on the run and summed per account and day`() {
		val s = setup()
		val acc = account()
		val workId = work()
		val (runId, gen, _) = running(s, workId, acc)
		ingest(runId, result(gen, 2, 1.25), ended(gen, 3, "submitted", true, true))
		val view = run(workId, runId)
		assertEquals(1.25, (view.usage!!["cost_usd"] as Number).toDouble())
		assertEquals(140, (view.usage["output_tokens"] as Number).toInt())
		assertEquals(1556, (view.usage["cache_read_tokens"] as Number).toInt())
		assertEquals(3, (view.usage["num_turns"] as Number).toInt())
		assertEquals(listOf("claude-opus-5-5"), view.usage["models"])
		// No rate_limit_event came: where it is charged cannot be told (billing-signals 「알 수 없음」).
		assertEquals("unknown", view.billingLocation)
		assertEquals("unavailable", view.billingDetection)

		val today = accounts.get(acc).today
		assertEquals(1, today.runs)
		assertEquals(1.25, today.costUsd)
		assertEquals(3, today.turns)
		assertEquals(1739, today.cacheCreationTokens)
		val daily = usage.daily(acc, 7)
		assertEquals(1, daily.size)
		assertEquals(1.25, daily.single().totals.costUsd)
		val detail = accounts.detail(acc)
		assertEquals(runId, detail.runs.single().id)
		assertEquals(1.25, detail.runs.single().costUsd)
		val signals = jdbc.sql("select kind from usage_signals where run_id = :id").param("id", runId).query(String::class.java).list()
		assertEquals(listOf("tokens"), signals)
	}

	@Test
	fun `a run that ended at its turn limit gets the wait reason and an immediate notice`() {
		val s = setup()
		val acc = account("run_turn_limit" to 5)
		val workId = work("턴 상한 작업")
		val (runId, gen, claim) = running(s, workId, acc)
		assertEquals(5, claim.limits!!.maxTurns)
		ingest(
			runId, result(gen, 2, 0.4, subtype = "error_max_turns", turns = 5),
			event(gen, 3, "error", mapOf("source" to "result", "subtype" to "error_max_turns", "text" to "Reached maximum number of turns (5)")),
			ended(gen, 4, "failed", true, true, mapOf("limit_hit" to "max_turns")),
		)
		assertEquals("run_turn_limit", run(workId, runId).stoppedBy)
		assertEquals(listOf("waiting_limit_reset"), waits(workId))
		val n = notices(runId).single()
		assertContains(n.first, "턴 5")
		assertContains(n.first, "턴 상한 작업")
		assertEquals("immediate", n.second)
		// Handing it on again (the person decided) takes the wait off.
		delegate(s, workId, acc)
		assertFalse("waiting_limit_reset" in waits(workId))
	}

	@Test
	fun `a budget that was what was left of today ends as the daily limit`() {
		val s = setup()
		val acc = account("run_cost_limit_usd" to 30, "daily_cost_limit_usd" to 1)
		val workId = work()
		val (runId, gen, claim) = running(s, workId, acc)
		assertEquals(BigDecimal("1.00"), claim.limits!!.maxBudgetUsd)
		assertEquals("daily", claim.limits.budgetFrom)
		ingest(runId, result(gen, 2, 1.02, subtype = "error_max_budget_usd"), ended(gen, 3, "failed", true, true, mapOf("limit_hit" to "max_budget_usd")))
		assertEquals("daily_cost_limit", run(workId, runId).stoppedBy)
		assertEquals(listOf("waiting_limit_reset"), waits(workId))
		assertEquals("daily_cost_limit", accounts.get(acc).block!!.cause)
	}

	@Test
	fun `the daily limit stops the account's other live runs and refuses the next handoff and claim`() {
		val s = setup()
		val acc = account("run_cost_limit_usd" to 1.5, "daily_cost_limit_usd" to 2)
		val workA = work("A")
		val workB = work("B")
		val workD = work("D")
		val (runA, genA, claimA) = running(s, workA, acc)
		assertEquals(BigDecimal("1.50"), claimA.limits!!.maxBudgetUsd)
		val (runB, genB, _) = running(s, workB, acc)
		delegate(s, workD, acc) // handed over while the account was still under its limit
		val runD = newestRun(workD)

		ingest(runA, result(genA, 2, 2.1), ended(genA, 3, "submitted", true, true))
		// B was live on the same account: asked to stop, the way 「실행 취소」 asks.
		val b = run(workB, runB)
		assertEquals("stopping", b.state)
		assertEquals("daily_cost_limit", b.stoppedBy)
		assertEquals("cancel", runs.renew(runB, LeaseIn(s.device, genB)).stop)
		assertEquals(listOf("waiting_limit_reset"), waits(workB))
		assertTrue(notices(runB).single().first.contains("하루 상한"))
		// A finished: its work item has no wait. The account itself is stopped for today.
		assertFalse("waiting_limit_reset" in waits(workA))
		val block = accounts.get(acc).block!!
		assertEquals("daily_cost_limit", block.cause)
		assertNotNull(block.resetsAt)

		val e = assertFailsWith<ConflictException> { delegate(s, work(), acc) }
		assertContains(e.message!!, "하루 상한")
		// The run handed over before the limit is not started at its claim, and that ending stays.
		val refused = assertFailsWith<ConflictException> { runs.claim(runD, s.device) }
		assertContains(refused.message!!, "시작하지 않았다")
		val d = run(workD, runD)
		assertEquals("ended", d.state)
		assertEquals("cancelled", d.endReason)
		assertEquals("daily_cost_limit", d.stoppedBy)
		assertNotNull(d.delegation!!.revokedAt)
		assertEquals(listOf("waiting_limit_reset"), waits(workD))
		assertEquals("ready", workItems.detail(workD).item.stage)
		assertTrue(notices(acc).any { it.second == "immediate" })
	}

	@Test
	fun `half of the daily limit within an hour is abnormal spending and stops the account`() {
		val s = setup()
		val acc = account("daily_cost_limit_usd" to 10)
		val workId = work()
		val (runId, gen, _) = running(s, workId, acc)
		ingest(runId, result(gen, 2, 6.0), ended(gen, 3, "submitted", true, true))
		val block = accounts.get(acc).block!!
		assertEquals("usage_burst", block.cause)
		val e = assertFailsWith<ConflictException> { delegate(s, work(), acc) }
		assertContains(e.message!!, "비정상 소비")
		// Spent more than an hour ago, the same amount is no longer a burst.
		jdbc.sql("update runs set usage_at = now() - interval '61 minutes' where id = :id").param("id", runId).update()
		assertNull(accounts.get(acc).block)
	}

	// ---------------------------------------------------------------- provider signals

	@Test
	fun `a confirmed extra-billing signal stops the run and the account until a person lets it run again`() {
		val s = setup()
		val acc = account()
		val workId = work()
		val (runId, gen, _) = running(s, workId, acc)
		ingest(runId, rateLimit(gen, 2, mapOf("status" to "allowed", "isUsingOverage" to true, "rateLimitType" to "overage")))
		val view = run(workId, runId)
		assertEquals("stopping", view.state)
		assertEquals("extra_billing", view.stoppedBy)
		assertEquals("extra_usage", view.billingLocation)
		assertEquals("detected", view.billingDetection)
		assertEquals("cancel", runs.renew(runId, LeaseIn(s.device, gen)).stop)
		assertEquals(listOf("billing_stopped"), waits(workId))
		assertEquals("immediate", notices(runId).single().second)
		val detail = accounts.detail(acc)
		assertEquals("extra_billing", detail.account.block!!.cause)
		assertTrue(detail.actions.single { it.name == "resume_billing" }.allowed)
		val e = assertFailsWith<ConflictException> { delegate(s, work(), acc) }
		assertContains(e.message!!, "추가 과금")

		ingest(runId, ended(gen, 3, "cancelled", true, false))
		assertEquals(setOf("billing_stopped", "outcome_unknown"), waits(workId).toSet())
		accounts.resumeBilling(acc, accounts.get(acc).version, actor = SessionService.OWNER)
		assertNull(accounts.get(acc).block)
		delegate(s, workId, acc)
		assertTrue(waits(workId).isEmpty())
	}

	@Test
	fun `the provider refusing more stops the account until its reset`() {
		val s = setup()
		val acc = account()
		val workId = work()
		val (runId, gen, _) = running(s, workId, acc)
		val resets = Instant.now().plusSeconds(3600).epochSecond
		ingest(runId, rateLimit(gen, 2, mapOf("status" to "rejected", "overageStatus" to "rejected", "rateLimitType" to "five_hour"),
			mapOf("five_hour" to mapOf("utilization" to 1.0, "resetsAt" to resets))))
		assertEquals("stopping", run(workId, runId).state)
		assertEquals(listOf("waiting_limit_reset"), waits(workId))
		val block = accounts.get(acc).block!!
		assertEquals("provider_limit", block.cause)
		assertEquals(resets, block.resetsAt!!.toEpochSecond())
		assertContains(assertFailsWith<ConflictException> { delegate(s, work(), acc) }.message!!, "공급자의 사용 한도")
		jdbc.sql("update ai_accounts set limit_resets_at = now() - interval '1 minute' where id = :id").param("id", acc).update()
		assertNull(accounts.get(acc).block)
	}

	@Test
	fun `a normal rate-limit event records the subscription limit, and a nearly full window a digest notice`() {
		val s = setup()
		val acc = account()
		val workId = work()
		val (runId, gen, _) = running(s, workId, acc)
		ingest(runId, rateLimit(gen, 2, mapOf("status" to "allowed", "overageStatus" to "rejected", "isUsingOverage" to false),
			mapOf("five_hour" to mapOf("utilization" to 0.93), "seven_day" to mapOf("utilization" to 0.32))))
		val view = run(workId, runId)
		assertEquals("running", view.state)
		assertEquals("subscription_limit", view.billingLocation)
		assertEquals("not_detected", view.billingDetection)
		val n = notices(acc).single()
		assertContains(n.first, "5시간 93%")
		assertEquals("digest", n.second)
		assertTrue(waits(workId).isEmpty())
	}

	@Test
	fun `an extra-billing phrase counts only where an error is carried`() {
		val s = setup()
		val acc = account()
		val workId = work()
		val (runId, gen, _) = running(s, workId, acc)
		ingest(runId, event(gen, 2, "answer", mapOf("text" to "문서에 따르면 draw from your extra usage 라는 오류가 있다")))
		assertEquals("running", run(workId, runId).state)
		ingest(runId, event(gen, 3, "error", mapOf("source" to "result", "text" to "API Error: 400 Third-party apps now draw from your extra usage, not your plan limits")))
		assertEquals("extra_billing", run(workId, runId).stoppedBy)
		assertEquals(listOf("billing_stopped"), waits(workId))
	}

	@Test
	fun `the sign-in the executor really used is recorded and a different one is flagged`() {
		val s = setup()
		val acc = account()
		val workId = work()
		val (runId, gen, _) = running(s, workId, acc)
		ingest(runId, result(gen, 2, 0.1), ended(gen, 3, "submitted", true, true,
			mapOf("auth" to mapOf("method" to "api_key", "api_key_source" to "ANTHROPIC_API_KEY", "config_isolated" to false))))
		val view = run(workId, runId)
		assertEquals("api_key", view.authMethod)
		assertEquals("api", view.billingLocation)
		assertContains(notices(runId).single().first, "API 키")
	}
}
