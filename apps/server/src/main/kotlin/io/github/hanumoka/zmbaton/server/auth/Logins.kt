package io.github.hanumoka.zmbaton.server.auth

import io.github.hanumoka.zmbaton.server.BadRequestException
import io.github.hanumoka.zmbaton.server.ConflictException
import io.github.hanumoka.zmbaton.server.Ids
import io.github.hanumoka.zmbaton.server.SessionService
import io.github.hanumoka.zmbaton.server.UnauthorizedException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
class TooManyAttemptsException(message: String) : RuntimeException(message)

/**
 * A person's sign-in (docs/stage1/auth.md): a password kept as a BCrypt hash, a Web login kept as the
 * SHA-256 of the cookie's token, and one-time setup tokens for the first password. Nothing here keeps
 * or writes a password or a token in the clear: not the database, not the audit log, not the log.
 *
 * Refusals are audited outside any transaction, so the audit row survives the refusal.
 */
@Service
class LoginService(
	private val jdbc: JdbcClient,
	private val settings: AuthSettings,
	private val sessions: SessionService,
	private val throttle: LoginThrottle,
	transactions: PlatformTransactionManager,
	@Value("\${zm-baton.auth.bcrypt-strength:12}") strength: Int,
) {
	private val tx = TransactionTemplate(transactions)

	companion object {
		const val COOKIE = "zmb_login"

		/** Over TLS the cookie takes the `__Host-` prefix: the browser then keeps it to this host, path `/` and Secure. */
		const val SECURE_COOKIE = "__Host-zmb_login"
		const val CSRF_HEADER = "X-CSRF-Token"

		/** A login unused this long ends; a login ends this long after it began, used or not. */
		const val IDLE_DAYS = 7
		const val MAX_DAYS = 30
		const val SETUP_HOURS = 24
		const val MIN_PASSWORD = 12

		/** BCrypt reads at most 72 bytes; a longer password is refused rather than cut without a word. */
		const val MAX_PASSWORD_BYTES = 72
		private val LOGIN_NAME = Regex("^[\\p{L}\\p{N}._@-]{2,50}$")
	}

	private val encoder = BCryptPasswordEncoder(strength)

	/** Checked when no person has the name, so a wrong name takes as long as a wrong password. */
	private val noSuchPerson: String = encoder.encode(Tokens.new("absent-")) ?: error("BCrypt gave no hash")

	data class WebLogin(val id: String, val person: String)

	data class SignedIn(val token: String, val person: String, val loginId: String)

	val cookieName: String get() = if (settings.secure) SECURE_COOKIE else COOKIE

	/** The CSRF token of a login: derived from the cookie's token, so nothing more is stored. */
	fun csrfFor(token: String): String = Tokens.hash("csrf:$token")

	/** The live login of a cookie's token, pushed forward when used; null when it ended, expired or its person is inactive. */
	fun authenticate(token: String): WebLogin? {
		data class Row(val id: String, val person: String, val stale: Boolean)
		val row = jdbc.sql(
			"""
			select l.id, l.user_id, l.last_seen_at < now() - interval '5 minutes'
			  from web_logins l join users u on u.id = l.user_id
			 where l.token_hash = :hash and l.ended_at is null and l.expires_at > now() and u.status = 'active'
			""".trimIndent(),
		).param("hash", Tokens.hash(token)).query { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getBoolean(3)) }.optional().orElse(null)
			?: return null
		if (row.stale) {
			jdbc.sql(
				"""
				update web_logins set last_seen_at = now(),
				       expires_at = least(created_at + make_interval(days => :max), now() + make_interval(days => :idle))
				 where id = :id
				""".trimIndent(),
			).param("max", MAX_DAYS).param("idle", IDLE_DAYS).param("id", row.id).update()
		}
		return WebLogin(row.id, row.person)
	}

	/** Signs a person in with their login name and password. Five wrong tries in a row lock the name and the address for a while. */
	fun login(login: String, password: String, remote: String?): SignedIn {
		val name = login.trim()
		val keys = listOf("login:" + name.lowercase(), "ip:" + (remote ?: "-"))
		throttle.check(keys)
		data class Row(val person: String, val hash: String)
		val row = jdbc.sql(
			"""
			select p.user_id, p.password_hash from person_passwords p join users u on u.id = p.user_id
			 where lower(p.login) = lower(:login) and u.status = 'active'
			""".trimIndent(),
		).param("login", name).query { rs, _ -> Row(rs.getString(1), rs.getString(2)) }.optional().orElse(null)
		val matches = encoder.matches(password, row?.hash ?: noSuchPerson)
		if (row == null || !matches) {
			throttle.failed(keys)
			// Who it was meant for when the name is known; never the name or password typed.
			sessions.audit("system", SessionService.SYSTEM_ACTOR, "auth.login_failed", "usr", row?.person, mapOf("known_login" to (row != null)))
			throw UnauthorizedException("아이디나 비밀번호가 맞지 않다")
		}
		throttle.succeeded(keys)
		return start(row.person, "auth.login")
	}

	/** Ends the login of a cookie's token. Nothing happens when it already ended. */
	fun logout(token: String?) {
		if (token == null) return
		val ended = jdbc.sql("update web_logins set ended_at = now() where token_hash = :hash and ended_at is null returning id, user_id")
			.param("hash", Tokens.hash(token)).query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null) ?: return
		sessions.audit("person", ended.second, "auth.logout", "usr", ended.second, mapOf("login_id" to ended.first))
	}

	/**
	 * Sets a password with a one-time setup token (a first password, or a reset the server's operator
	 * asked for) and signs the person in. The token is used up in the same transaction that stores the
	 * password, so a refused password leaves the token usable.
	 */
	fun setup(setupToken: String, login: String, password: String, remote: String?): SignedIn {
		val keys = listOf("setup:" + (remote ?: "-"))
		throttle.check(keys)
		val name = login.trim()
		validate(name, password)
		val person = tx.execute {
			val person = jdbc.sql(
				"""
				update setup_tokens set used_at = now()
				 where token_hash = :hash and used_at is null and dropped_at is null and expires_at > now()
				returning user_id
				""".trimIndent(),
			).param("hash", Tokens.hash(setupToken.trim())).query(String::class.java).optional().orElse(null) ?: return@execute null
			val taken = jdbc.sql("select count(*) from person_passwords where lower(login) = lower(:login) and user_id <> :p")
				.param("login", name).param("p", person).query(Long::class.javaObjectType).single()
			if (taken > 0) throw ConflictException("그 아이디는 다른 사람이 쓴다")
			jdbc.sql(
				"""
				insert into person_passwords (user_id, login, password_hash) values (:p, :login, :hash)
				on conflict (user_id) do update set login = excluded.login, password_hash = excluded.password_hash, set_at = now()
				""".trimIndent(),
			).param("p", person).param("login", name).param("hash", hash(password)).update()
			// A reset ends every login the old password opened.
			jdbc.sql("update web_logins set ended_at = now() where user_id = :p and ended_at is null").param("p", person).update()
			person
		}
		if (person == null) {
			throttle.failed(keys)
			sessions.audit("system", SessionService.SYSTEM_ACTOR, "auth.setup_refused", "usr", null, emptyMap())
			throw UnauthorizedException("설정 토큰이 맞지 않거나, 이미 썼거나, 기한이 지났다. 서버를 다시 띄우면 서버 기록에 새 토큰이 나온다")
		}
		throttle.succeeded(keys)
		sessions.audit("person", person, "auth.password_set", "usr", person, mapOf("with" to "setup_token"))
		return start(person, "auth.login")
	}

	/** Changes a signed-in person's password. Their other logins end; [keepLogin] (this one) stays. */
	fun changePassword(person: String, keepLogin: String?, current: String, next: String): Int {
		val keys = listOf("password:$person")
		throttle.check(keys)
		data class Row(val login: String, val hash: String)
		val row = jdbc.sql("select login, password_hash from person_passwords where user_id = :p").param("p", person)
			.query { rs, _ -> Row(rs.getString(1), rs.getString(2)) }.optional().orElse(null)
			?: throw ConflictException("아직 비밀번호가 없다. 처음 비밀번호는 서버 기록의 설정 토큰으로 정한다")
		if (!encoder.matches(current, row.hash)) {
			throttle.failed(keys)
			sessions.audit("person", person, "auth.password_change_refused", "usr", person, emptyMap())
			throw UnauthorizedException("지금 비밀번호가 맞지 않다")
		}
		throttle.succeeded(keys)
		validate(row.login, next)
		if (encoder.matches(next, row.hash)) throw BadRequestException("새 비밀번호가 지금 비밀번호와 같다")
		val ended = tx.execute {
			jdbc.sql("update person_passwords set password_hash = :hash, set_at = now() where user_id = :p").param("hash", hash(next)).param("p", person).update()
			jdbc.sql("update web_logins set ended_at = now() where user_id = :p and ended_at is null and id is distinct from :keep")
				.param("p", person).param("keep", keepLogin).update()
		}
		sessions.audit("person", person, "auth.password_changed", "usr", person, mapOf("other_logins_ended" to ended))
		return ended
	}

	/**
	 * A one-time setup token for [person], valid [SETUP_HOURS] hours or until used. Earlier unused tokens
	 * of the person are dropped. Only the hash is stored; the caller shows the token once (AuthStartup).
	 */
	fun issueSetupToken(person: String, reason: String): Pair<String, OffsetDateTime> {
		val token = Tokens.new(Tokens.SETUP)
		val expires = tx.execute {
			jdbc.sql("update setup_tokens set dropped_at = now() where user_id = :p and used_at is null and dropped_at is null").param("p", person).update()
			jdbc.sql(
				"""
				insert into setup_tokens (id, user_id, token_hash, reason, expires_at)
				values (:id, :p, :hash, :reason, now() + make_interval(hours => :hours))
				returning expires_at
				""".trimIndent(),
			).param("id", "stp_" + Ids.uuidV7()).param("p", person).param("hash", Tokens.hash(token)).param("reason", reason)
				.param("hours", SETUP_HOURS).query(OffsetDateTime::class.java).single()
		}
		sessions.audit("system", SessionService.SYSTEM_ACTOR, "auth.setup_token_issued", "usr", person, mapOf("reason" to reason, "expires_at" to expires.toString()))
		return token to expires
	}

	fun loginName(person: String): String? =
		jdbc.sql("select login from person_passwords where user_id = :p").param("p", person).query(String::class.java).optional().orElse(null)

	/** The organisations' owners who have no password yet: until one has, nobody can sign in. */
	fun ownersWithoutPassword(): List<Pair<String, String>> =
		jdbc.sql(
			"""
			select u.id, u.display_name from organizations o join users u on u.id = o.owner_user_id
			 where u.status = 'active' and not exists (select 1 from person_passwords p where p.user_id = u.id)
			 order by o.created_at, o.id
			""".trimIndent(),
		).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()

	fun cookie(token: String): ResponseCookie =
		ResponseCookie.from(cookieName, token).httpOnly(true).secure(settings.secure).sameSite("Strict").path("/")
			.maxAge(Duration.ofDays(MAX_DAYS.toLong())).build()

	fun clearCookie(): ResponseCookie =
		ResponseCookie.from(cookieName, "").httpOnly(true).secure(settings.secure).sameSite("Strict").path("/").maxAge(0).build()

	private fun start(person: String, action: String): SignedIn {
		val token = Tokens.new(Tokens.LOGIN)
		val id = "lgn_" + Ids.uuidV7()
		jdbc.sql(
			"""
			insert into web_logins (id, user_id, token_hash, expires_at)
			values (:id, :p, :hash, now() + make_interval(days => :idle))
			""".trimIndent(),
		).param("id", id).param("p", person).param("hash", Tokens.hash(token)).param("idle", IDLE_DAYS).update()
		sessions.audit("person", person, action, "usr", person, mapOf("login_id" to id))
		return SignedIn(token, person, id)
	}

	private fun hash(password: String): String = encoder.encode(password) ?: error("BCrypt gave no hash")

	private fun validate(login: String, password: String) {
		if (!LOGIN_NAME.matches(login)) throw BadRequestException("아이디는 2~50자의 글자·숫자와 . _ @ - 다")
		if (password.length < MIN_PASSWORD) throw BadRequestException("비밀번호는 ${MIN_PASSWORD}자 이상이다")
		if (password.toByteArray(Charsets.UTF_8).size > MAX_PASSWORD_BYTES) {
			throw BadRequestException("비밀번호가 너무 길다. UTF-8로 ${MAX_PASSWORD_BYTES}바이트까지다(한국어는 24자쯤)")
		}
		if (password.trim().equals(login, ignoreCase = true)) throw BadRequestException("비밀번호를 아이디와 다르게 정한다")
	}
}

/**
 * A simple limit on sign-in attempts, kept in memory (a restart forgets it): five wrong tries in a row
 * for the same key lock it for 30 seconds, and every further wrong try doubles that, up to 15 minutes.
 * A right try clears the key; a key quiet for an hour starts over.
 */
@Component
class LoginThrottle {
	companion object {
		const val FREE_TRIES = 5
		val FIRST_LOCK: Duration = Duration.ofSeconds(30)
		val MAX_LOCK: Duration = Duration.ofMinutes(15)
		val FORGET_AFTER: Duration = Duration.ofHours(1)
	}

	/** Replaced in tests. */
	var clock: Clock = Clock.systemUTC()

	private class Entry(var failures: Int, var lockedUntil: Instant?, var last: Instant)

	private val entries = ConcurrentHashMap<String, Entry>()

	fun check(keys: Collection<String>) {
		val now = clock.instant()
		val until = keys.mapNotNull { entries[it]?.lockedUntil }.filter { it.isAfter(now) }.maxOrNull() ?: return
		val seconds = Duration.between(now, until).seconds + 1
		throw TooManyAttemptsException("시도가 너무 많다. ${seconds}초 뒤에 다시 한다")
	}

	fun failed(keys: Collection<String>) {
		val now = clock.instant()
		for (key in keys) {
			entries.compute(key) { _, old ->
				val e = if (old == null || Duration.between(old.last, now) > FORGET_AFTER) Entry(0, null, now) else old
				e.failures++
				e.last = now
				if (e.failures >= FREE_TRIES) {
					val doubled = FIRST_LOCK.multipliedBy(1L shl (e.failures - FREE_TRIES).coerceAtMost(10))
					e.lockedUntil = now.plus(if (doubled > MAX_LOCK) MAX_LOCK else doubled)
				}
				e
			}
		}
	}

	fun succeeded(keys: Collection<String>) {
		keys.forEach(entries::remove)
	}
}
