package io.github.hanumoka.zmbaton.server.auth

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.BadRequestException
import io.github.hanumoka.zmbaton.server.ConflictException
import io.github.hanumoka.zmbaton.server.DeviceRegistered
import io.github.hanumoka.zmbaton.server.Ids
import io.github.hanumoka.zmbaton.server.NotFoundException
import io.github.hanumoka.zmbaton.server.RegisterDevice
import io.github.hanumoka.zmbaton.server.SessionService
import io.github.hanumoka.zmbaton.server.UnauthorizedException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.OffsetDateTime

data class DeviceView(
	val id: String,
	val name: String,
	val os: String?,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("last_seen_at") val lastSeenAt: OffsetDateTime?,
	@JsonProperty("revoked_at") val revokedAt: OffsetDateTime?,
	/** Whether the device holds a device token. A device registered before tokens has none: it works in `local` mode only. */
	@JsonProperty("has_token") val hasToken: Boolean,
	@JsonProperty("token_issued_at") val tokenIssuedAt: OffsetDateTime?,
)

data class DeviceSelf(
	@JsonProperty("device_id") val deviceId: String,
	val name: String,
	@JsonProperty("user_id") val userId: String,
	/** The server's authentication mode, so `zm-baton status` can say what it needs. */
	val mode: String,
)

/** `zm-baton login` on a server in `required` mode starts here (RFC 8628 "Device Authorization Request"). */
data class DeviceLoginStart(
	val name: String,
	val os: String? = null,
	/** This device's id when it asks for a new token for itself; absent for a new device. */
	@JsonProperty("device_id") val deviceId: String? = null,
)

/**
 * The answer, shaped like RFC 8628's: the CLI shows [userCode] and the page to open, then polls with
 * [deviceCode]. The server does not know the address the CLI reaches it by, so it gives the page as a
 * path the CLI puts after its server address.
 */
data class DeviceLoginStarted(
	@JsonProperty("device_code") val deviceCode: String,
	@JsonProperty("user_code") val userCode: String,
	@JsonProperty("verification_path") val verificationPath: String,
	@JsonProperty("verification_path_complete") val verificationPathComplete: String,
	@JsonProperty("expires_in") val expiresIn: Int,
	val interval: Int,
)

data class DeviceLoginPoll(@JsonProperty("device_code") val deviceCode: String)

/** What a signed-in person sees before approving: what asks, from where, and in which state. */
data class DeviceLoginView(
	@JsonProperty("user_code") val userCode: String,
	val name: String,
	val os: String?,
	/** The device that asks for a new token, when it is the person's own; null for a new device. */
	@JsonProperty("device_id") val deviceId: String?,
	@JsonProperty("device_name") val deviceName: String?,
	/** `pending`, `approved`, `denied`, `expired` or `done` (the CLI took its token). */
	val state: String,
	@JsonProperty("created_at") val createdAt: OffsetDateTime,
	@JsonProperty("expires_at") val expiresAt: OffsetDateTime,
)

/** A poll's answer: a token, or an RFC 8628 error code (`authorization_pending`, `slow_down`, `access_denied`, `expired_token`, `invalid_grant`). */
data class DeviceLoginOutcome(val token: DeviceRegistered?, val error: String?, val message: String?)

/**
 * Device tokens (docs/stage1/auth.md): `zm-baton login` gets one, the connector and the CLI send it on
 * device calls, and a person revokes a lost device. The server keeps the token's SHA-256 only. A device
 * login lets the CLI get a token on a server in `required` mode: it shows a code, the person approves
 * it on the Web, and the token is made when the CLI picks it up, so it is never stored anywhere.
 */
@Service
class DeviceAuthService(private val jdbc: JdbcClient, private val sessions: SessionService, private val settings: AuthSettings) {
	companion object {
		const val LOGIN_MINUTES = 10
		const val POLL_SECONDS = 2

		/** RFC 8628 6.1: consonants only, so a code spells no word and reads the same aloud. */
		private const val CODE_LETTERS = "BCDFGHJKLMNPQRSTVWXZ"
		private val random = SecureRandom()

		/** `bcdf-ghjk`, `BCDFGHJK` and `BCDF GHJK` are the same code. */
		fun normalCode(raw: String): String {
			val letters = raw.uppercase().filter { it in CODE_LETTERS }
			if (letters.length != 8) throw NotFoundException("no device login $raw")
			return letters.take(4) + "-" + letters.drop(4)
		}
	}

	/** The device and person of a device token. A token of a revoked device, or of an inactive person, is refused. */
	fun authenticate(token: String): Caller {
		data class Row(val id: String, val person: String, val revoked: Boolean, val active: Boolean)
		val row = jdbc.sql(
			"select d.id, d.user_id, d.revoked_at is not null, u.status = 'active' from devices d join users u on u.id = d.user_id where d.token_hash = :hash",
		).param("hash", Tokens.hash(token)).query { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)) }
			.optional().orElse(null) ?: throw UnauthorizedException("모르는 기기 토큰이다. 그 기기에서 zm-baton login을 다시 실행한다")
		if (row.revoked) throw UnauthorizedException("이 기기는 폐기됐다. 그 기기를 다시 쓰려면 zm-baton login --force로 새 기기로 등록한다")
		if (!row.active) throw UnauthorizedException("이 기기의 사람은 활동 중이 아니다")
		return Caller(person = row.person, device = row.id)
	}

	/** `zm-baton login`: a new device of [person], with its first device token. */
	@Transactional
	fun register(body: RegisterDevice, person: String): DeviceRegistered {
		requireActivePerson(person)
		val id = sessions.registerDevice(body, person).deviceId
		return DeviceRegistered(id, issue(id, person, replaced = false))
	}

	/** A new device token for a device that has none (registered before tokens) or lost its own; the old token stops. */
	@Transactional
	fun rekey(deviceId: String, person: String): DeviceRegistered {
		requireActivePerson(person)
		val had = jdbc.sql("select token_hash is not null from devices where id = :id and revoked_at is null").param("id", deviceId)
			.query(Boolean::class.javaObjectType).optional().orElseThrow { NotFoundException("no device $deviceId; register it with zm-baton login") }
		return DeviceRegistered(deviceId, issue(deviceId, person, replaced = had))
	}

	fun list(person: String): List<DeviceView> =
		jdbc.sql(
			"""
			select id, name, os, created_at, last_seen_at, revoked_at, token_hash is not null, token_issued_at
			  from devices where user_id = :p order by revoked_at is not null, created_at desc, id
			""".trimIndent(),
		).param("p", person).query { rs, _ ->
			DeviceView(
				rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime::class.java),
				rs.getObject(5, OffsetDateTime::class.java), rs.getObject(6, OffsetDateTime::class.java), rs.getBoolean(7),
				rs.getObject(8, OffsetDateTime::class.java),
			)
		}.list()

	/**
	 * A lost device: its token stops, it can no longer register sessions or claim runs, and its live
	 * sessions end (their waits and relays close as when a session ends). Work already handed to it
	 * stays until a person takes it back.
	 */
	@Transactional
	fun revoke(deviceId: String, actor: String): DeviceView {
		val owner = jdbc.sql("update devices set revoked_at = now() where id = :id and revoked_at is null returning user_id")
			.param("id", deviceId).query(String::class.java).optional().orElseThrow { NotFoundException("no device $deviceId") }
		val ended = sessions.endForDevice(deviceId)
		sessions.audit("person", actor, "device.revoked", "dev", deviceId, mapOf("ended_sessions" to ended))
		return list(owner).first { it.id == deviceId }
	}

	fun self(deviceId: String): DeviceSelf =
		jdbc.sql("select id, name, user_id from devices where id = :id").param("id", deviceId)
			.query { rs, _ -> DeviceSelf(rs.getString(1), rs.getString(2), rs.getString(3), settings.mode.wire) }.single()

	// ---------------------------------------------------------------- device login

	@Transactional
	fun startLogin(body: DeviceLoginStart): DeviceLoginStarted {
		val name = body.name.trim()
		if (name.isEmpty() || name.length > 100) throw BadRequestException("name must be 1-100 characters")
		val deviceCode = Tokens.new(Tokens.DEVICE_CODE)
		repeat(5) {
			val userCode = (1..8).map { CODE_LETTERS[random.nextInt(CODE_LETTERS.length)] }.joinToString("").let { it.take(4) + "-" + it.drop(4) }
			val inserted = jdbc.sql(
				"""
				insert into device_logins (id, device_code_hash, user_code, device_id, name, os, expires_at)
				values (:id, :hash, :code, :device, :name, :os, now() + make_interval(mins => :mins))
				on conflict (user_code) where consumed_at is null and denied_at is null do nothing
				""".trimIndent(),
			).param("id", "dvl_" + Ids.uuidV7()).param("hash", Tokens.hash(deviceCode)).param("code", userCode)
				.param("device", body.deviceId?.trim()?.ifEmpty { null }).param("name", name).param("os", body.os?.trim()?.take(50))
				.param("mins", LOGIN_MINUTES).update()
			if (inserted == 1) {
				val path = "/#me?device_login=$userCode"
				return DeviceLoginStarted(deviceCode, userCode, "/#me", path, LOGIN_MINUTES * 60, POLL_SECONDS)
			}
		}
		throw ConflictException("could not make a free code; try again")
	}

	fun loginView(rawCode: String, person: String): DeviceLoginView {
		val code = normalCode(rawCode)
		return jdbc.sql(
			"""
			select l.user_code, l.name, l.os, d.id, d.name,
			       case when l.denied_at is not null then 'denied' when l.consumed_at is not null then 'done'
			            when l.expires_at <= now() then 'expired' when l.approved_at is not null then 'approved' else 'pending' end,
			       l.created_at, l.expires_at
			  from device_logins l left join devices d on d.id = l.device_id and d.user_id = :p
			 where l.user_code = :code order by l.created_at desc limit 1
			""".trimIndent(),
		).param("p", person).param("code", code).query { rs, _ ->
			DeviceLoginView(
				rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
				rs.getObject(7, OffsetDateTime::class.java), rs.getObject(8, OffsetDateTime::class.java),
			)
		}.optional().orElseThrow { NotFoundException("no device login $code") }
	}

	/** A person approves a waiting device login. A login that names a device is approved only by that device's person. */
	@Transactional
	fun approve(rawCode: String, person: String): DeviceLoginView {
		requireActivePerson(person)
		val code = normalCode(rawCode)
		val updated = jdbc.sql(
			"""
			update device_logins set approved_by = :p, approved_at = now()
			 where user_code = :code and approved_at is null and denied_at is null and consumed_at is null and expires_at > now()
			   and (device_id is null or device_id in (select id from devices where user_id = :p and revoked_at is null))
			returning id
			""".trimIndent(),
		).param("p", person).param("code", code).query(String::class.java).optional().orElse(null)
		if (updated == null) {
			val view = loginView(code, person)
			throw ConflictException(
				when (view.state) {
					"pending" -> "이 요청은 당신의 기기가 아니거나 폐기된 기기의 것이다"
					"expired" -> "기한(${LOGIN_MINUTES}분)이 지났다. 그 기기에서 zm-baton login을 다시 실행한다"
					else -> "이미 처리한 요청이다"
				},
			)
		}
		sessions.audit("person", person, "device_login.approved", "dvl", updated, mapOf("user_code" to code))
		return loginView(code, person)
	}

	@Transactional
	fun deny(rawCode: String, person: String): DeviceLoginView {
		val code = normalCode(rawCode)
		val updated = jdbc.sql(
			"""
			update device_logins set denied_at = now()
			 where user_code = :code and denied_at is null and consumed_at is null and expires_at > now()
			returning id
			""".trimIndent(),
		).param("code", code).query(String::class.java).optional().orElseThrow { ConflictException("이미 처리했거나 기한이 지난 요청이다") }
		sessions.audit("person", person, "device_login.denied", "dvl", updated, mapOf("user_code" to code))
		return loginView(code, person)
	}

	/**
	 * The CLI's poll (RFC 8628 3.4-3.5). Once approved, the first poll registers the device (or, for a
	 * device that asked for itself, replaces its token) and hands the token over; later polls get nothing.
	 * Errors are answers, not exceptions, so the poll time is kept.
	 */
	@Transactional
	fun poll(deviceCode: String): DeviceLoginOutcome {
		// The same lock order as deactivation: person before device/login. A login approved
		// after this read waits for the next poll, rather than taking the locks backwards.
		val approved = jdbc.sql("select approved_by from device_logins where device_code_hash = :hash")
			.param("hash", Tokens.hash(deviceCode.trim())).query(String::class.java).optional().orElse(null)
		if (approved != null && !lockActivePerson(approved)) return DeviceLoginOutcome(null, "access_denied", "the approving person is inactive")
		data class Row(
			val id: String, val approvedBy: String?, val denied: Boolean, val consumed: Boolean, val expired: Boolean, val tooSoon: Boolean,
			val deviceId: String?, val name: String, val os: String?,
		)
		val row = jdbc.sql(
			"""
			select id, approved_by, denied_at is not null, consumed_at is not null, expires_at <= now(),
			       coalesce(last_polled_at > now() - make_interval(secs => :interval), false), device_id, name, os
			  from device_logins where device_code_hash = :hash for update
			""".trimIndent(),
		).param("interval", POLL_SECONDS - 0.5).param("hash", Tokens.hash(deviceCode.trim())).query { rs, _ ->
			Row(
				rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4), rs.getBoolean(5), rs.getBoolean(6),
				rs.getString(7), rs.getString(8), rs.getString(9),
			)
		}.optional().orElse(null) ?: return DeviceLoginOutcome(null, "invalid_grant", "unknown device code")
		jdbc.sql("update device_logins set last_polled_at = now() where id = :id").param("id", row.id).update()
		return when {
			row.consumed -> DeviceLoginOutcome(null, "invalid_grant", "this device login was used already")
			row.denied -> DeviceLoginOutcome(null, "access_denied", "the person denied this device login")
			row.expired -> DeviceLoginOutcome(null, "expired_token", "the code expired; run zm-baton login again")
			row.approvedBy != approved -> DeviceLoginOutcome(null, "authorization_pending", "approval changed; poll again")
			row.approvedBy == null && row.tooSoon -> DeviceLoginOutcome(null, "slow_down", "poll every $POLL_SECONDS seconds")
			row.approvedBy == null -> DeviceLoginOutcome(null, "authorization_pending", "waiting for a person to approve the code")
			else -> {
				jdbc.sql("update device_logins set consumed_at = now() where id = :id").param("id", row.id).update()
				val issued = if (row.deviceId != null) rekey(row.deviceId, row.approvedBy) else register(RegisterDevice(row.name, row.os), row.approvedBy)
				jdbc.sql("update device_logins set issued_device_id = :d where id = :id").param("d", issued.deviceId).param("id", row.id).update()
				DeviceLoginOutcome(issued, null, null)
			}
		}
	}

	private fun lockActivePerson(person: String): Boolean =
		jdbc.sql("select status = 'active' from users where id = :id for update").param("id", person)
			.query(Boolean::class.javaObjectType).optional().orElse(false)

	private fun requireActivePerson(person: String) {
		if (!lockActivePerson(person)) throw UnauthorizedException("활동 중인 구성원만 기기를 승인하거나 등록할 수 있다")
	}

	/** Sets a new token on a live device; only its hash is stored. */
	private fun issue(deviceId: String, actor: String, replaced: Boolean): String {
		val token = Tokens.new(Tokens.DEVICE)
		val updated = jdbc.sql("update devices set token_hash = :hash, token_issued_at = now() where id = :id and revoked_at is null")
			.param("hash", Tokens.hash(token)).param("id", deviceId).update()
		if (updated != 1) throw ConflictException("device $deviceId is revoked")
		sessions.audit("person", actor, "device.token_issued", "dev", deviceId, mapOf("replaced" to replaced))
		return token
	}
}
