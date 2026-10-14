package io.github.hanumoka.zmbaton.server.auth

import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Who may call an endpoint. [AuthInterceptor] reads it; an endpoint without it is a person's. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Access(val value: AccessKind)

enum class AccessKind {
	/** A person: signed in on the Web (`required`), or in `local` mode the owner. A device token is refused. */
	PERSON,

	/** A person, or a device of theirs with its device token: what the CLI does for its person (export, import, repositories). */
	PERSON_OR_DEVICE,

	/** A device with its device token (`required`). In `local` mode a device token is checked when sent and not needed. */
	DEVICE,

	/** A directly opened session with its session token, which the session services check. */
	SESSION,

	/** Anyone: the sign-in endpoints and the start and poll of a device login. */
	PUBLIC,
}

/**
 * How a request authenticated, set by [AuthInterceptor] before the controller runs; [Requester] reads
 * it. [person] is the signed-in person or the person a device belongs to.
 */
data class Caller(
	val person: String? = null,
	/** The Web login (`lgn_`) the request came with. */
	val loginId: String? = null,
	/** The device (`dev_`) whose device token the request carried. */
	val device: String? = null,
) {
	companion object {
		const val ATTRIBUTE = "zm-baton.caller"
		val NONE = Caller()

		/** The caller of the request being handled; null outside a request (a test calling a service). */
		fun current(): Caller? =
			(RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getAttribute(ATTRIBUTE) as? Caller
	}
}

/**
 * zm-baton's own secrets: a prefix that says what the token is for, 32 random bytes, and only their
 * SHA-256 kept on the server (contract "등록과 세션 토큰" 7). `zmbd_` a device token, `zmbl_` a Web
 * login, `zmbt_` a one-time setup token, `zmbc_` a device login's device code. Session tokens are
 * `zmbs_` (SessionTokens).
 */
object Tokens {
	const val DEVICE = "zmbd_"
	const val LOGIN = "zmbl_"
	const val SETUP = "zmbt_"
	const val DEVICE_CODE = "zmbc_"

	private val random = SecureRandom()

	fun new(prefix: String): String {
		val bytes = ByteArray(32).also(random::nextBytes)
		return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	fun hash(token: String): String =
		MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

	/** Compares two strings in time that does not depend on where they differ. */
	fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
