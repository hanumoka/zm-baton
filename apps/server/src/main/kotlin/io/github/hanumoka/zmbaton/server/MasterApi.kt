package io.github.hanumoka.zmbaton.server

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.OffsetDateTime

// The master's API path (contract v1 "문서 모순 후보" 5, DEC-0095 1). Off unless the server's key
// variable is set: the server reads the key from the environment variable named by
// `zm-baton.master.api.key-env` (default ZM_BATON_MASTER_API_KEY) when it sends a request, and never
// stores, logs, reports or exports it. The steps of one check on this path:
//
//  1. The server claims the check's run itself, as a central executor (no device): the same
//     conditional update and generation as a connector's claim.
//  2. A cheaper model answers the check's instruction. Its candidates are checked against the texts
//     (ContradictionPrompt.verify) before anything else is spent.
//  3. When candidates are left, a stronger model gets the same texts and those candidates and keeps
//     only those that really contradict.
//  4. The run reports like any run (RunService.ingest): start, the tokens and the price-list cost,
//     the final answer, and an end report carrying the answer (`result.text`). The sweep reads it.
//  5. Any failure (no credit, refusal, outage, an answer that is not the JSON) ends the run failed;
//     the sweep then hands the same work item to the owner's local connector (DEC-0095 2).
//
// Raw HTTP (java.net.http) instead of the Anthropic Java SDK: one request shape, a path that is off by
// default, and no new dependency (review standard item 9). docs/hardening-backlog.md has the trade-off.

/** Reads the API key from an environment variable by name. Tests replace it; the value never leaves it. */
fun interface MasterApiKey {
	fun value(name: String): String?
}

@Component
class EnvironmentMasterApiKey : MasterApiKey {
	private val name = Regex("^[A-Z_][A-Z0-9_]{0,99}$")
	override fun value(name: String): String? = if (this.name.matches(name)) System.getenv(name)?.trim()?.ifEmpty { null } else null
}

@Component
class MasterApiSettings(
	private val key: MasterApiKey,
	private val usage: UsageService,
	@Value("\${zm-baton.master.api.key-env:ZM_BATON_MASTER_API_KEY}") val keyEnv: String,
	@Value("\${zm-baton.master.api.base-url:https://api.anthropic.com}") val baseUrl: String,
	@Value("\${zm-baton.master.api.screen-model:claude-haiku-5-5}") val screenModel: String,
	@Value("\${zm-baton.master.api.confirm-model:claude-opus-5-5}") val confirmModel: String,
	@Value("\${zm-baton.master.api.max-tokens:8000}") val maxTokens: Int,
	@Value("\${zm-baton.master.api.timeout-seconds:120}") val timeoutSeconds: Long,
	/** Optional: an AI account whose daily limit the API path's cost counts against (#32). */
	@Value("\${zm-baton.master.api.account-id:}") val accountId: String,
	/** List prices per million tokens, `model=input/output`, for the price-list cost of a run. */
	@Value("\${zm-baton.master.api.prices:claude-haiku-5-5=0.10/0.50,claude-sonnet-5-5=2.00/10.00,claude-opus-5-5=4.00/20.00}") prices: String,
	/** `default` lets the API route a refusal of the stronger model to another one (server-side fallback); empty turns it off. */
	@Value("\${zm-baton.master.api.confirm-fallbacks:default}") val confirmFallbacks: String,
) {
	private val priceTable: Map<String, Pair<BigDecimal, BigDecimal>> = prices.split(',').mapNotNull { entry ->
		val (model, pair) = entry.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
		val (input, output) = pair.split('/', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
		model.trim() to (BigDecimal(input.trim()) to BigDecimal(output.trim()))
	}.toMap()

	/** On only while the key variable holds a value. */
	fun enabled(): Boolean = key.value(keyEnv) != null

	internal fun key(): String? = key.value(keyEnv)

	/** The account to charge and, when it cannot take work now, why. Both null when none is set. */
	fun usableAccount(): Pair<String?, String?> {
		val id = accountId.trim().ifEmpty { null } ?: return null to null
		val acc = usage.account(id) ?: return id to "API 경로에 정한 AI 계정 $id 을(를) 찾지 못했다"
		return id to usage.block(acc)?.reason
	}

	/** The price-list cost of [input] and [output] tokens of [model], or null when its price is not set. */
	fun cost(model: String, input: Long, output: Long): BigDecimal? {
		val (pin, pout) = priceTable[model] ?: return null
		val million = BigDecimal(1_000_000)
		return (pin.multiply(BigDecimal(input)) + pout.multiply(BigDecimal(output))).divide(million, 6, RoundingMode.HALF_UP)
	}
}

/** One answer of the Messages API. */
data class ApiReply(val model: String, val text: String, val inputTokens: Long, val outputTokens: Long, val cacheRead: Long, val cacheWrite: Long)

/** A failed request: the HTTP status (0 when none came) and the API's error type. Never the key or the body. */
class MasterApiException(val status: Int, val type: String?, message: String) : RuntimeException(message)

@Service
class MasterApiClient(private val settings: MasterApiSettings, private val json: ObjectMapper) {
	private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	/**
	 * `POST /v1/messages` with the answer held to [ContradictionPrompt.SCHEMA] (structured outputs).
	 * [effort] trades thoroughness for tokens; [fallbacks] adds the server-side refusal fallback.
	 */
	fun message(model: String, prompt: String, effort: String, fallbacks: Boolean): ApiReply {
		val key = settings.key() ?: throw MasterApiException(0, "no_key", "API 키 환경 변수 ${settings.keyEnv}가 비었다")
		val body = linkedMapOf<String, Any?>(
			"model" to model,
			"max_tokens" to settings.maxTokens,
			"messages" to listOf(mapOf("role" to "user", "content" to prompt)),
			"output_config" to mapOf("effort" to effort, "format" to mapOf("type" to "json_schema", "schema" to ContradictionPrompt.SCHEMA)),
		)
		val builder = HttpRequest.newBuilder(URI.create(settings.baseUrl.trimEnd('/') + "/v1/messages"))
			.timeout(Duration.ofSeconds(settings.timeoutSeconds))
			.header("content-type", "application/json")
			.header("anthropic-version", "2023-06-01")
			.header("x-api-key", key)
		if (fallbacks) {
			body["fallbacks"] = settings.confirmFallbacks
			builder.header("anthropic-beta", "server-side-fallback-2026-07-01")
		}
		val response = try {
			http.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString())
		} catch (e: IOException) {
			throw MasterApiException(0, "network", "API에 닿지 못했다(${e.javaClass.simpleName})")
		} catch (e: InterruptedException) {
			Thread.currentThread().interrupt()
			throw MasterApiException(0, "interrupted", "API 요청이 중단됐다")
		}
		val parsed = try {
			json.readValue(response.body(), mapType)
		} catch (e: Exception) {
			null
		}
		if (response.statusCode() != 200) {
			val type = ((parsed?.get("error") as? Map<*, *>)?.get("type") as? String)?.take(60)
			throw MasterApiException(response.statusCode(), type, "API가 HTTP ${response.statusCode()}${type?.let { " $it" } ?: ""}로 거절했다")
		}
		if (parsed == null) throw MasterApiException(200, "bad_response", "API의 답을 JSON으로 읽지 못했다")
		when (val stop = parsed["stop_reason"] as? String) {
			"refusal" -> throw MasterApiException(200, "refusal", "모델이 이 요청을 거절했다")
			"max_tokens" -> throw MasterApiException(200, "max_tokens", "답이 max_tokens(${settings.maxTokens})에서 잘렸다")
			else -> if (stop == null) throw MasterApiException(200, "bad_response", "API의 답에 stop_reason이 없다")
		}
		val text = (parsed["content"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.takeIf { b -> b["type"] == "text" }?.get("text") as? String }
			.joinToString("")
		val u = parsed["usage"] as? Map<*, *> ?: emptyMap<String, Any?>()
		fun n(k: String) = (u[k] as? Number)?.toLong() ?: 0L
		return ApiReply((parsed["model"] as? String) ?: model, text, n("input_tokens"), n("output_tokens"), n("cache_read_input_tokens"),
			n("cache_creation_input_tokens"))
	}
}

@Service
class MasterApiRunner(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val runs: RunService,
	private val sessions: SessionService,
	private val usage: UsageService,
	private val settings: MasterApiSettings,
	private val client: MasterApiClient,
	private val masterAccess: MasterAccess,
	transactions: PlatformTransactionManager,
) {
	companion object {
		const val ACTOR = "master-api"
		const val LEASE_SECONDS = RunService.LEASE_SECONDS
	}

	private val log = LoggerFactory.getLogger(MasterApiRunner::class.java)
	private val tx = TransactionTemplate(transactions)
	private val materialType = object : TypeReference<List<MaterialItem>>() {}

	/** Runs the API path's waiting runs one at a time. Returns how many it ran. */
	fun runPending(): Int {
		val waiting = jdbc.sql(
			"""
			select c.run_id from master_checks c join runs r on r.id = c.run_id
			 where c.path = 'api' and c.state = 'requested' and r.state = 'requested' and r.executor = :executor
			 order by c.created_at limit 5
			""".trimIndent(),
		).param("executor", MasterCheckService.API_EXECUTOR).query(String::class.java).list().filterNotNull()
		return waiting.count { runOne(it) }
	}

	/** Step 1 to 5 for one run; false when another worker had it or it could not start. */
	fun runOne(runId: String): Boolean {
		val claimed = tx.execute { claim(runId) } ?: return false
		var seq = 0L
		fun send(kind: String, payload: Map<String, Any?>) {
			runs.ingest(runId, EventBatch(2, listOf(EventIn(Ids.event(), claimed.generation, ++seq, kind, payload, OffsetDateTime.now()))))
		}
		val started = System.currentTimeMillis()
		val replies = mutableListOf<ApiReply>()
		send("lifecycle", mapOf("phase" to "started", "executor" to MasterCheckService.API_EXECUTOR, "auth_method" to "api_key",
			"models" to listOf(settings.screenModel, settings.confirmModel)))
		try {
			if (!settings.enabled()) throw MasterApiException(0, "no_key", "API 키 환경 변수 ${settings.keyEnv}가 비어 API 경로를 쓸 수 없다")
			val items = jdbc.sql("select material::text from master_checks where run_id = :id").param("id", runId)
				.query(String::class.java).single().let { json.readValue(it, materialType) }
			renew(runId)
			val screen = client.message(settings.screenModel, claimed.instruction, "low", false).also { replies += it }
			val raws = ContradictionPrompt.parse(screen.text, json) ?: throw MasterApiException(200, "bad_json", "저가 모델의 답이 정해진 JSON이 아니다")
			val screened = ContradictionPrompt.verify(items, raws).kept
			val final = if (screened.isEmpty()) {
				"""{"candidates":[]}"""
			} else {
				renew(runId)
				val prompt = claimed.instruction + "\n\n" + ContradictionPrompt.confirmSuffix(screened)
				client.message(settings.confirmModel, prompt, "medium", settings.confirmFallbacks.isNotBlank()).also { replies += it }.text
			}
			send("usage", usagePayload(replies, started))
			send("answer", mapOf("source" to "result", "text" to final.take(4000), "truncated" to (final.length > 4000)))
			send("lifecycle", mapOf("phase" to "ended", "end_reason" to "submitted", "executor_started" to true, "result_seen" to true,
				"result" to mapOf("text" to final, "truncated" to false), "screened" to raws.size, "auth" to mapOf("method" to "api_key")))
		} catch (e: MasterApiException) {
			log.warn("master API path failed for {}: {}", runId, e.message)
			if (replies.isNotEmpty()) send("usage", usagePayload(replies, started))
			send("error", mapOf("source" to "agent", "text" to "API 경로 실패: ${e.message}", "status" to e.status, "type" to e.type))
			send("lifecycle", mapOf("phase" to "ended", "end_reason" to "failed", "executor_started" to true, "result_seen" to true,
				"auth" to mapOf("method" to "api_key")))
		}
		return true
	}

	private data class Claimed(val generation: Long, val instruction: String)

	/**
	 * The central claim: the run's one conditional update, its work item's generation raised, like
	 * RunService.claim without a device. A run whose account is stopped now ends before any request.
	 */
	private fun claim(runId: String): Claimed? {
		if (masterAccess.refuseClaim(runId)) return null
		val account = jdbc.sql("select account_id from runs where id = :id and state = 'requested'").param("id", runId)
			.query(String::class.java).optional().orElse(null)
		val block = account?.let { usage.account(it) }?.let { usage.block(it) }
		if (block != null) {
			val ended = jdbc.sql("update runs set state = 'ended', end_reason = 'cancelled', ended_at = now(), stopped_by = :c where id = :id and state = 'requested'")
				.param("c", block.cause.takeIf { it != "disabled" }).param("id", runId).update()
			if (ended == 1) sessions.audit("system", ACTOR, "run.refused_by_account", "run", runId, mapOf("account_id" to account, "cause" to block.cause))
			return null
		}
		val won = jdbc.sql(
			"""
			update runs set state = 'claimed', claimed_at = now(), lease_expires_at = now() + make_interval(secs => :lease)
			 where id = (select id from runs where id = :id and state = 'requested' and executor = :executor for update skip locked)
			returning work_item_id, instruction
			""".trimIndent(),
		).param("lease", LEASE_SECONDS).param("id", runId).param("executor", MasterCheckService.API_EXECUTOR)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null) ?: return null
		val generation = jdbc.sql("update work_items set generation = generation + 1, updated_at = now() where id = :id returning generation")
			.param("id", won.first).query(Long::class.javaObjectType).single()
		jdbc.sql("update runs set generation = :g where id = :id").param("g", generation).param("id", runId).update()
		jdbc.sql("update master_checks set state = 'running' where run_id = :id and state = 'requested'").param("id", runId).update()
		sessions.audit("system", ACTOR, "run.claimed", "run", runId, mapOf("work_item_id" to won.first, "generation" to generation, "central" to true))
		return Claimed(generation, won.second.orEmpty())
	}

	private fun renew(runId: String) {
		jdbc.sql("update runs set lease_expires_at = now() + make_interval(secs => :lease) where id = :id and state in ('claimed', 'running')")
			.param("lease", LEASE_SECONDS).param("id", runId).update()
	}

	/** The result's usage in the shape UsageService.runUsage reads: totals per model and the price-list cost. */
	private fun usagePayload(replies: List<ApiReply>, startedMillis: Long): Map<String, Any?> {
		val models = linkedMapOf<String, Any?>()
		var total = BigDecimal.ZERO
		for (r in replies) {
			val cost = settings.cost(r.model, r.inputTokens + r.cacheRead + r.cacheWrite, r.outputTokens)
			if (cost != null) total += cost
			@Suppress("UNCHECKED_CAST")
			val m = (models[r.model] as? MutableMap<String, Any?>) ?: linkedMapOf<String, Any?>("inputTokens" to 0L, "outputTokens" to 0L,
				"cacheReadInputTokens" to 0L, "cacheCreationInputTokens" to 0L, "costUSD" to 0.0).also { models[r.model] = it }
			m["inputTokens"] = (m["inputTokens"] as Long) + r.inputTokens
			m["outputTokens"] = (m["outputTokens"] as Long) + r.outputTokens
			m["cacheReadInputTokens"] = (m["cacheReadInputTokens"] as Long) + r.cacheRead
			m["cacheCreationInputTokens"] = (m["cacheCreationInputTokens"] as Long) + r.cacheWrite
			m["costUSD"] = (m["costUSD"] as Double) + (cost?.toDouble() ?: 0.0)
		}
		return linkedMapOf("source" to "result", "subtype" to "success", "model_usage" to models, "total_cost_usd" to total.toDouble(),
			"num_turns" to replies.size, "duration_ms" to (System.currentTimeMillis() - startedMillis))
	}
}
