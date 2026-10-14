package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.OffsetDateTime

// AI accounts (FN-0501, G16.1; contract v1 "식별자" `acc_`, "위임" 무엇으로). An account is a
// reference, never a login: whose account it is, what kind (an official subscription login or an
// API key), its purpose, affiliation and data terms, and where the device keeps what the executor
// signs in with — the official CLI's own login, or the name of an environment variable. No secret
// value is accepted, stored, reported or exported; a value that looks like a key is refused. The
// account also carries its usage limits (FN-0409, G39.4). Accounts are disabled, never deleted.
// Automatic switching between accounts (FN-0502) and the vault (core module 5) are stage 2.

data class AccountLimits(
	@JsonProperty("run_turn_limit") val runTurnLimit: Int?,
	@JsonProperty("run_cost_limit_usd") val runCostLimitUsd: BigDecimal?,
	@JsonProperty("run_minute_limit") val runMinuteLimit: Int?,
	@JsonProperty("daily_cost_limit_usd") val dailyCostLimitUsd: BigDecimal?,
)

data class AccountView(
	val id: String,
	val name: String,
	val provider: String,
	@JsonProperty("auth_kind") val authKind: String,
	/** The sign-in method its runs use: `subscription_login` or `api_key`. */
	@JsonProperty("auth_method") val authMethod: String,
	val affiliation: String,
	val purpose: String?,
	@JsonProperty("data_terms") val dataTerms: String?,
	/** `device_login` or `env:ANTHROPIC_API_KEY`: where the device keeps it, never a value. */
	@JsonProperty("auth_source") val authSource: String,
	@JsonProperty("device_id") val deviceId: String?,
	@JsonProperty("device_name") val deviceName: String?,
	@JsonProperty("owner_user_id") val ownerUserId: String?,
	/** As stored: `ready`, `limit_reached` or `disabled`. */
	val status: String,
	/** Why no run can start on it now; null when one can. */
	val block: AccountBlock?,
	val limits: AccountLimits,
	/** What its runs used today, in the day zone of the daily limit. */
	val today: UsageTotals,
	val version: Long,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("updated_at") val updatedAt: OffsetDateTime,
)

data class AccountRunView(
	val id: String,
	@JsonProperty("work_item_id") val workItemId: String,
	@JsonProperty("work_item_title") val workItemTitle: String,
	val state: String,
	@JsonProperty("end_reason") val endReason: String?,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("cost_usd") val costUsd: Double?,
	val turns: Long?,
	@JsonProperty("stopped_by") val stoppedBy: String?,
	@JsonProperty("auth_method") val authMethod: String?,
)

data class AccountAction(val name: String, val allowed: Boolean, val reason: String?)

data class AccountDetail(
	val account: AccountView,
	/** The last seven days with a run, newest first. */
	val days: List<DailyUsage>,
	/** Its newest runs. */
	val runs: List<AccountRunView>,
	val actions: List<AccountAction>,
	val history: List<HistoryEntry>,
)

data class AccountVersionIn(val version: Long)

/**
 * Shapes of secret values (API keys, tokens, private keys). Account text that matches is refused
 * with words that never repeat the value, so it reaches no row, audit line or export.
 */
object SecretShapes {
	private val SHAPES = listOf(
		Regex("(?i)sk-ant-"),
		Regex("""\bsk-[A-Za-z0-9_-]{16,}"""),
		Regex("""\bAKIA[0-9A-Z]{16}\b"""),
		Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""),
		Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}"""),
		Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
		Regex("""\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}"""),
	)
	/**
	 * One unbroken run of key characters long enough to be a key, with letters and digits mixed. A
	 * hyphen breaks the run and hexadecimal alone does not count, so identifiers, UUIDs and commit
	 * hashes a person writes in a purpose stay allowed.
	 */
	private val LONG_TOKEN = Regex("""[A-Za-z0-9_+/=]{32,}""")
	private val HEX = Regex("^[0-9a-fA-F]+$")

	fun looksSecret(s: String): Boolean =
		SHAPES.any { it.containsMatchIn(s) } ||
			LONG_TOKEN.findAll(s).any { m -> m.value.any(Char::isDigit) && m.value.any(Char::isLetter) && !HEX.matches(m.value) }
}

@Service
class AccountService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val usage: UsageService,
	private val sessions: SessionService,
) {
	companion object {
		const val ORG = "org_owner"
		val AUTH_KINDS = setOf("personal_subscription", "org_account", "api_key")
		val AUTH_SOURCES = setOf("device_login", "env:ANTHROPIC_API_KEY")
		val AFFILIATIONS = setOf("personal", "organization")
		/**
		 * Limits a new account starts with when the person names none: generous, so that stage-1 work
		 * is not stopped by surprise (docs/stage1/accounts.md). The cost is the price-list estimate.
		 */
		val DEFAULT_LIMITS = AccountLimits(200, BigDecimal("30.00"), 60, BigDecimal("200.00"))
		private val LIMIT_KEYS = listOf("run_turn_limit", "run_cost_limit_usd", "run_minute_limit", "daily_cost_limit_usd")
		private val TEXT_KEYS = mapOf("name" to 100, "purpose" to 300, "data_terms" to 500)
		const val SECRET_REFUSAL =
			"API 키나 토큰처럼 보이는 값이다. zm-baton은 키 값을 받지 않는다. 키는 그 기기의 환경 변수 ANTHROPIC_API_KEY에 두고, 여기에는 그 위치만 고른다"
	}

	private val mapType = object : TypeReference<Map<String, Any?>>() {}

	// ---------------------------------------------------------------- reads

	fun list(includeDisabled: Boolean): List<AccountView> {
		val now = usage.now()
		return jdbc.sql("$SELECT where (cast(:all as boolean) or a.status <> 'disabled') order by (a.status = 'disabled'), a.created_at, a.id")
			.param("all", includeDisabled).query { rs, _ -> view(rs, now) }.list()
	}

	fun get(id: String): AccountView =
		jdbc.sql("$SELECT where a.id = :id").param("id", id).query { rs, _ -> view(rs, usage.now()) }.optional()
			.orElseThrow { NotFoundException("no AI account $id") }

	fun detail(id: String): AccountDetail {
		val account = get(id)
		val runs = jdbc.sql(
			"""
			select r.id, r.work_item_id, w.title, r.state, r.end_reason, r.created_at, (r.usage ->> 'cost_usd')::numeric,
			       (r.usage ->> 'num_turns')::numeric, r.stopped_by, r.auth_method
			  from runs r join work_items w on w.id = r.work_item_id
			 where r.account_id = :id order by r.created_at desc, r.id desc limit 20
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			AccountRunView(
				rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
				rs.getObject(6, OffsetDateTime::class.java), rs.getBigDecimal(7)?.toDouble(), rs.getBigDecimal(8)?.toLong(),
				rs.getString(9), rs.getString(10),
			)
		}.list()
		val history = jdbc.sql(
			"""
			select action, actor_kind, actor_id, details::text, at from audit_log
			 where target_kind = 'acc' and target_id = :id order by at desc, id desc limit 30
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			HistoryEntry(rs.getString(1), rs.getString(2), rs.getString(3), json.readValue(rs.getString(4), mapType),
				rs.getObject(5, OffsetDateTime::class.java))
		}.list()
		return AccountDetail(account, usage.daily(id, 7), runs, actions(account), history)
	}

	/** What a person may do with the account now, each with the reason when not. */
	fun actions(a: AccountView): List<AccountAction> {
		val disabled = a.status == "disabled"
		val billing = a.block?.cause == "extra_billing"
		return listOf(
			AccountAction("edit", true, null),
			AccountAction("disable", !disabled, if (disabled) "이미 비활성화했다" else null),
			AccountAction("enable", disabled, if (disabled) null else "쓰는 중인 계정이다"),
			AccountAction("resume_billing", billing && !disabled, if (billing) null else "추가 과금 신호로 멈추지 않았다"),
		)
	}

	// ---------------------------------------------------------------- writes

	/**
	 * Registers an account. Absent limits start at [DEFAULT_LIMITS]; a limit sent as null means none.
	 * A personal subscription belongs to the one person (stage 1) and its runs do only that person's
	 * work (DEC-0076); an organisation's account has no owner.
	 */
	@Transactional
	fun register(body: Map<String, Any?>): AccountDetail {
		val unknown = body.keys - (setOf("name", "auth_kind", "affiliation", "purpose", "data_terms", "auth_source", "device_id") + LIMIT_KEYS)
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown")
		val texts = texts(body)
		val name = texts["name"] ?: throw BadRequestException("name is required")
		val kind = (body["auth_kind"] as? String)?.trim() ?: throw BadRequestException("auth_kind is required")
		if (kind !in AUTH_KINDS) throw BadRequestException("auth_kind must be one of $AUTH_KINDS")
		val affiliation = affiliationFor(kind, body["affiliation"] as? String)
		val source = sourceFor(kind, body["auth_source"])
		val device = device(body["device_id"])
		val limits = limits(body, DEFAULT_LIMITS)
		val id = Ids.account()
		jdbc.sql(
			"""
			insert into ai_accounts (id, org_id, owner_user_id, provider, auth_kind, name, purpose, affiliation, data_terms, auth_source,
			                         device_id, run_turn_limit, run_cost_limit_usd, run_minute_limit, daily_cost_limit_usd)
			values (:id, :org, :owner, 'anthropic', :kind, :name, :purpose, :affiliation, :terms, :source, :device,
			        :turns, :runCost, :minutes, :daily)
			""".trimIndent(),
		).param("id", id).param("org", ORG).param("owner", if (affiliation == "personal") SessionService.OWNER else null)
			.param("kind", kind).param("name", name).param("purpose", texts["purpose"]).param("affiliation", affiliation)
			.param("terms", texts["data_terms"]).param("source", source).param("device", device)
			.param("turns", limits.runTurnLimit).param("runCost", limits.runCostLimitUsd).param("minutes", limits.runMinuteLimit)
			.param("daily", limits.dailyCostLimitUsd).update()
		audit(id, "account.registered", mapOf("name" to name, "auth_kind" to kind, "affiliation" to affiliation, "auth_source" to source,
			"device_id" to device, "limits" to limits))
		return detail(id)
	}

	/**
	 * Changes the fields present in [patch]; `version` must be the version the caller saw. The kind of
	 * account and where its sign-in lives do not change: that is another account, registered anew.
	 */
	@Transactional
	fun update(id: String, patch: Map<String, Any?>): AccountDetail {
		val version = (patch["version"] as? Number)?.toLong() ?: throw BadRequestException("version is required")
		val unknown = patch.keys - (setOf("version", "name", "purpose", "data_terms", "affiliation", "device_id") + LIMIT_KEYS)
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown. 계정 종류와 자격 위치는 바꾸지 않는다. 새 계정으로 등록한다")
		val current = lock(id, version)
		val changes = linkedMapOf<String, Any?>()
		val texts = texts(patch)
		for (key in listOf("name", "purpose", "data_terms")) if (key in patch) changes[key] = texts[key]
		if ("name" in changes && changes["name"] == null) throw BadRequestException("name cannot be cleared")
		if ("affiliation" in patch) {
			val affiliation = affiliationFor(current.authKind, patch["affiliation"] as? String)
			changes["affiliation"] = affiliation
			changes["owner_user_id"] = if (affiliation == "personal") SessionService.OWNER else null
		}
		if ("device_id" in patch) changes["device_id"] = device(patch["device_id"])
		val limits = limits(patch, AccountLimits(null, null, null, null))
		if ("run_turn_limit" in patch) changes["run_turn_limit"] = limits.runTurnLimit
		if ("run_cost_limit_usd" in patch) changes["run_cost_limit_usd"] = limits.runCostLimitUsd
		if ("run_minute_limit" in patch) changes["run_minute_limit"] = limits.runMinuteLimit
		if ("daily_cost_limit_usd" in patch) changes["daily_cost_limit_usd"] = limits.dailyCostLimitUsd
		if (changes.isEmpty()) throw BadRequestException("nothing to change")
		// Column names come from the fixed lists above, never from input.
		val stmt = jdbc.sql("update ai_accounts set ${changes.keys.joinToString(", ") { "$it = :$it" }}, version = version + 1, updated_at = now() where id = :id")
			.param("id", id)
		changes.forEach { (k, v) -> stmt.param(k, v) }
		stmt.update()
		audit(id, "account.updated", changes.mapValues { it.value?.toString() })
		return detail(id)
	}

	/** Disabling keeps the account and its history; no new run is handed to it. Runs already going on go on. */
	@Transactional
	fun disable(id: String, version: Long): AccountDetail {
		val current = lock(id, version)
		if (current.status == "disabled") throw ConflictException("이미 비활성화한 계정이다")
		jdbc.sql("update ai_accounts set status = 'disabled', version = version + 1, updated_at = now() where id = :id").param("id", id).update()
		audit(id, "account.disabled", mapOf("from" to current.status))
		return detail(id)
	}

	@Transactional
	fun enable(id: String, version: Long): AccountDetail {
		val current = lock(id, version)
		if (current.status != "disabled") throw ConflictException("비활성화한 계정이 아니다")
		jdbc.sql("update ai_accounts set status = 'ready', limit_resets_at = null, version = version + 1, updated_at = now() where id = :id")
			.param("id", id).update()
		audit(id, "account.enabled", emptyMap())
		return detail(id)
	}

	/**
	 * A person lets an account stopped by an extra-billing signal run again (billing-signals 「사람이
	 * 다음 처리를 고른다」: 계속 쓰도록 허용). The work items that stopped keep `billing_stopped` until
	 * each is handed on again.
	 */
	@Transactional
	fun resumeBilling(id: String, version: Long): AccountDetail {
		lock(id, version)
		val cleared = jdbc.sql(
			"update ai_accounts set billing_stopped_at = null, version = version + 1, updated_at = now() where id = :id and billing_stopped_at is not null",
		).param("id", id).update()
		if (cleared == 0) throw ConflictException("추가 과금 신호로 멈춘 계정이 아니다")
		audit(id, "account.billing_resumed", emptyMap())
		return detail(id)
	}

	/**
	 * The account a delegation runs on (contract "위임" 무엇으로). Absent (a client from before
	 * accounts), the oldest subscription login that can start a run now; an API key is never picked
	 * on its own, because it is billed by use. Refused with words for a person when the account is
	 * disabled, holds another device's sign-in, is someone else's subscription, or is stopped now.
	 */
	fun forDelegation(accountId: String?, deviceId: String, responsibleUserId: String): UsageService.AccountRow {
		val acc = if (accountId != null) {
			usage.account(accountId.trim()) ?: throw BadRequestException("no AI account $accountId")
		} else {
			jdbc.sql("select id from ai_accounts where status <> 'disabled' and auth_kind <> 'api_key' order by created_at, id")
				.query(String::class.java).list().filterNotNull().mapNotNull { usage.account(it) }
				.firstOrNull { usage.block(it) == null && (it.deviceId == null || it.deviceId == deviceId) }
				?: throw ConflictException("account_id: 지금 쓸 수 있는 구독 로그인 계정이 없다. AI 계정 화면에서 계정을 고르거나 등록한다")
		}
		if (acc.deviceId != null && acc.deviceId != deviceId) {
			throw ConflictException("이 AI 계정의 자격은 다른 PC에 있다. 그 PC의 저장소를 고르거나 다른 계정을 고른다")
		}
		if (acc.authKind == "personal_subscription" && acc.ownerUserId != responsibleUserId) {
			throw ConflictException("개인 구독은 계정 주인 본인의 작업에만 쓴다(DEC-0076). 이 작업의 책임자는 ${responsibleUserId}다")
		}
		usage.block(acc)?.let { throw ConflictException(it.reason) }
		return acc
	}

	// ---------------------------------------------------------------- internals

	private data class Locked(val authKind: String, val status: String)

	private fun lock(id: String, version: Long): Locked {
		val row = jdbc.sql("select auth_kind, status, version from ai_accounts where id = :id for update").param("id", id)
			.query { rs, _ -> Triple(rs.getString(1), rs.getString(2), rs.getLong(3)) }.optional()
			.orElseThrow { NotFoundException("no AI account $id") }
		if (row.third != version) throw ConflictException("the account changed (version ${row.third}, you sent $version); reload it")
		return Locked(row.first, row.second)
	}

	/** Trimmed text fields, empty as null, each within its length and never shaped like a secret. */
	private fun texts(body: Map<String, Any?>): Map<String, String?> =
		TEXT_KEYS.filterKeys { it in body }.mapValues { (key, max) ->
			val raw = body[key]
			if (raw != null && raw !is String) throw BadRequestException("$key must be text")
			val t = (raw as String?)?.trim()?.ifEmpty { null }
			if (t != null && t.length > max) throw BadRequestException("$key must be at most $max characters")
			if (t != null && SecretShapes.looksSecret(t)) throw BadRequestException("$key: $SECRET_REFUSAL")
			t
		}

	private fun affiliationFor(kind: String, asked: String?): String {
		val a = asked?.trim()?.ifEmpty { null }
		if (a != null && a !in AFFILIATIONS) throw BadRequestException("affiliation must be personal or organization")
		return when (kind) {
			"personal_subscription" -> if (a == null || a == "personal") "personal" else throw BadRequestException("개인 구독의 소속은 개인이다")
			"org_account" -> if (a == null || a == "organization") "organization" else throw BadRequestException("조직 계정의 소속은 조직이다")
			else -> a ?: "organization"
		}
	}

	/** Where the device keeps the sign-in: the CLI's own login for a subscription, the API key variable for a key. */
	private fun sourceFor(kind: String, raw: Any?): String {
		if (raw != null && raw !is String) throw BadRequestException("auth_source must be text")
		val s = (raw as String?)?.trim()?.ifEmpty { null }
		if (s != null && SecretShapes.looksSecret(s)) throw BadRequestException("auth_source: $SECRET_REFUSAL")
		val source = s ?: if (kind == "api_key") "env:ANTHROPIC_API_KEY" else "device_login"
		if (source !in AUTH_SOURCES) {
			throw BadRequestException("auth_source must be device_login or env:ANTHROPIC_API_KEY. 다른 이름의 환경 변수에서 키를 옮겨 넣는 것은 실행 주입(2단계 금고)이라 아직 없다")
		}
		val expected = if (kind == "api_key") "env:ANTHROPIC_API_KEY" else "device_login"
		if (source != expected) throw BadRequestException("$kind 계정의 자격 위치는 ${expected}다")
		return source
	}

	private fun device(raw: Any?): String? {
		if (raw != null && raw !is String) throw BadRequestException("device_id must be text")
		val id = (raw as String?)?.trim()?.ifEmpty { null } ?: return null
		val live = jdbc.sql("select count(*) from devices where id = :id and revoked_at is null").param("id", id)
			.query(Long::class.javaObjectType).single() > 0
		if (!live) throw BadRequestException("no device $id")
		return id
	}

	/** The limits in [body]; a key that is absent keeps [base], a key sent as null means no limit. */
	private fun limits(body: Map<String, Any?>, base: AccountLimits): AccountLimits {
		fun int(key: String, max: Int, fallback: Int?): Int? {
			if (key !in body) return fallback
			val v = body[key] ?: return null
			val n = (v as? Number)?.takeIf { it.toDouble() == Math.floor(it.toDouble()) }?.toInt()
				?: throw BadRequestException("$key must be a whole number or null")
			if (n < 1 || n > max) throw BadRequestException("$key must be 1-$max or null")
			return n
		}
		fun usd(key: String, fallback: BigDecimal?): BigDecimal? {
			if (key !in body) return fallback
			val v = body[key] ?: return null
			val n = (v as? Number)?.let { BigDecimal(it.toString()) } ?: throw BadRequestException("$key must be a number or null")
			if (n.signum() <= 0 || n > BigDecimal("100000")) throw BadRequestException("$key must be more than 0 and at most 100000, or null")
			return n.setScale(2, java.math.RoundingMode.HALF_UP).max(BigDecimal("0.01"))
		}
		return AccountLimits(
			int("run_turn_limit", 10000, base.runTurnLimit), usd("run_cost_limit_usd", base.runCostLimitUsd),
			int("run_minute_limit", 1440, base.runMinuteLimit), usd("daily_cost_limit_usd", base.dailyCostLimitUsd),
		)
	}

	private fun audit(id: String, action: String, details: Map<String, Any?>) =
		sessions.audit("person", SessionService.OWNER, action, "acc", id, details)

	private val SELECT = """
		select a.id, a.name, a.provider, a.auth_kind, a.affiliation, a.purpose, a.data_terms, a.auth_source, a.device_id, d.name as device_name,
		       a.owner_user_id, a.status, a.run_turn_limit, a.run_cost_limit_usd, a.run_minute_limit, a.daily_cost_limit_usd,
		       a.limit_resets_at, a.billing_stopped_at, a.version, a.created_at, a.updated_at
		  from ai_accounts a left join devices d on d.id = a.device_id
	""".trimIndent()

	private fun view(rs: java.sql.ResultSet, now: OffsetDateTime): AccountView {
		val row = UsageService.AccountRow(
			rs.getString("id"), rs.getString("name"), rs.getString("auth_kind"), rs.getString("auth_source"), rs.getString("status"),
			rs.getString("device_id"), rs.getString("owner_user_id"), rs.getObject("run_turn_limit") as Int?, rs.getBigDecimal("run_cost_limit_usd"),
			rs.getObject("run_minute_limit") as Int?, rs.getBigDecimal("daily_cost_limit_usd"),
			rs.getObject("limit_resets_at", OffsetDateTime::class.java), rs.getObject("billing_stopped_at", OffsetDateTime::class.java),
		)
		return AccountView(
			row.id, row.name, rs.getString("provider"), row.authKind, UsageService.authMethod(row.authKind), rs.getString("affiliation"),
			rs.getString("purpose"), rs.getString("data_terms"), row.authSource, row.deviceId, rs.getString("device_name"), row.ownerUserId,
			row.status, usage.block(row, now), AccountLimits(row.runTurnLimit, row.runCostLimitUsd, row.runMinuteLimit, row.dailyCostLimitUsd),
			usage.totals(row.id, usage.dayStart(now)), rs.getLong("version"), rs.getObject("created_at", OffsetDateTime::class.java),
			rs.getObject("updated_at", OffsetDateTime::class.java),
		)
	}
}

@RestController
@RequestMapping("/api")
class AccountController(private val accounts: AccountService, private val usage: UsageService, private val people: PersonService) {

	@GetMapping("/ai-accounts")
	fun list(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(name = "include_disabled", defaultValue = "true") includeDisabled: Boolean,
	): List<AccountView> {
		people.refuseSessionToken(authorization, "list AI accounts")
		return accounts.list(includeDisabled)
	}

	@GetMapping("/ai-accounts/{id}")
	fun detail(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): AccountDetail {
		people.refuseSessionToken(authorization, "read an AI account")
		return accounts.detail(id)
	}

	@PostMapping("/ai-accounts")
	@ResponseStatus(HttpStatus.CREATED)
	fun register(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: Map<String, Any?>,
	): AccountDetail {
		people.refuseSessionToken(authorization, "register an AI account")
		return accounts.register(body)
	}

	@PatchMapping("/ai-accounts/{id}")
	fun update(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: Map<String, Any?>,
	): AccountDetail {
		people.refuseSessionToken(authorization, "change an AI account")
		return accounts.update(id, body)
	}

	@PostMapping("/ai-accounts/{id}/disable")
	fun disable(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: AccountVersionIn,
	): AccountDetail {
		people.refuseSessionToken(authorization, "disable an AI account")
		return accounts.disable(id, body.version)
	}

	@PostMapping("/ai-accounts/{id}/enable")
	fun enable(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: AccountVersionIn,
	): AccountDetail {
		people.refuseSessionToken(authorization, "enable an AI account")
		return accounts.enable(id, body.version)
	}

	@PostMapping("/ai-accounts/{id}/resume-billing")
	fun resumeBilling(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: AccountVersionIn,
	): AccountDetail {
		people.refuseSessionToken(authorization, "let an AI account run again after an extra-billing signal")
		return accounts.resumeBilling(id, body.version)
	}

	/** What each account used per day (FN-0409: 계정별·일별), newest first. */
	@GetMapping("/usage/daily")
	fun daily(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam(defaultValue = "7") days: Int,
	): List<DailyUsage> {
		people.refuseSessionToken(authorization, "read usage")
		return usage.daily(null, days.coerceIn(1, 90))
	}
}
