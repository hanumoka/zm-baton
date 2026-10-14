package io.github.hanumoka.zmbaton.server.auth

import org.slf4j.LoggerFactory
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer
import org.springframework.boot.diagnostics.FailureAnalysis
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * How the server authenticates (docs/stage1/auth.md, stage-1 exit criterion 4). Read at start only.
 *
 * - `local` (the default) keeps stage 1 as it was: the server answers this PC only and every person
 *   request is the owner's. A device token is checked when a device sends one and is not required.
 * - `required`: a person signs in on the Web, a device sends its device token, and a person's
 *   request carries the sign-in cookie and the CSRF header.
 */
enum class AuthMode(val wire: String) {
	LOCAL("local"),
	REQUIRED("required");

	companion object {
		fun parse(s: String?): AuthMode? = entries.firstOrNull { it.wire.equals(s?.trim(), ignoreCase = true) }
	}
}

/** The settings authentication and the listening rule read: the mode, TLS and where the server listens. */
@Component
class AuthSettings(env: Environment) {
	val mode: AuthMode = AuthMode.parse(env.getProperty("zm-baton.auth.mode", "local"))
		?: throw IllegalStateException("zm-baton.auth.mode (ZM_BATON_AUTH) must be local or required")

	/** `server.address`: empty means every interface. */
	val address: String? = env.getProperty("server.address")

	/** Spring Boot's own TLS: `server.ssl.enabled` with a key store, a bundle or a certificate. */
	val tlsDirect: Boolean = env.getProperty("server.ssl.enabled", Boolean::class.java, false) &&
		listOf("server.ssl.key-store", "server.ssl.bundle", "server.ssl.certificate").any { !env.getProperty(it).isNullOrBlank() }

	/** A proxy in front ends TLS and forwards plain HTTP to this server (ZM_BATON_TLS_PROXY=on). */
	val tlsByProxy: Boolean = on(env.getProperty("zm-baton.tls.proxy"))

	/**
	 * The server listens beyond loopback only inside a container whose port is published on this PC's
	 * loopback (deploy/compose: `127.0.0.1:18081:18081`, ZM_BATON_PUBLISHED_ON_LOOPBACK=on).
	 */
	val publishedOnLoopback: Boolean = on(env.getProperty("zm-baton.network.published-on-loopback"))

	val required: Boolean get() = mode == AuthMode.REQUIRED

	/** The browser reaches the server over TLS: the sign-in cookie is Secure and takes the `__Host-` name. */
	val secure: Boolean get() = tlsDirect || tlsByProxy

	fun exposure() = Exposure.Facts(address, publishedOnLoopback, mode, tlsDirect, tlsByProxy)

	private fun on(value: String?) = value?.trim()?.lowercase() in setOf("on", "true", "yes", "1")
}

/**
 * Whether the server may listen where it is told to (DEC-0094 1-4, DEC-0106 3-3): on this PC's loopback
 * always; anywhere else only with `required` and TLS, either its own or a proxy's. Pure, so every case
 * is a unit test (ExposureTests).
 */
object Exposure {
	data class Facts(
		val address: String?,
		val publishedOnLoopback: Boolean,
		val mode: AuthMode,
		val tlsDirect: Boolean,
		val tlsByProxy: Boolean,
	)

	private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

	/** Null when the server may start; otherwise what is missing, for the person reading the log. */
	fun refusal(f: Facts): String? {
		if (isLoopback(f.address) || f.publishedOnLoopback) return null
		val missing = buildList {
			if (f.mode != AuthMode.REQUIRED) add("ZM_BATON_AUTH=required (people sign in, devices send a device token)")
			if (!f.tlsDirect && !f.tlsByProxy) {
				add(
					"TLS: ZM_BATON_TLS_ENABLED=true with ZM_BATON_TLS_KEY_STORE and ZM_BATON_TLS_KEY_STORE_PASSWORD, " +
						"or ZM_BATON_TLS_PROXY=on when a proxy in front ends TLS",
				)
			}
		}
		if (missing.isEmpty()) return null
		val where = f.address?.trim()?.ifEmpty { null } ?: "every interface"
		return "the server is set to listen on $where, beyond this PC's loopback, without: ${missing.joinToString("; ")}"
	}

	/**
	 * A loopback address written as a literal, or `localhost`. A host name is never looked up: what a
	 * name resolves to can change, so anything that is not a loopback literal counts as beyond this PC.
	 */
	fun isLoopback(address: String?): Boolean {
		val a = address?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
		if (a.isEmpty()) return false
		if (a.equals("localhost", ignoreCase = true)) return true
		if (!IPV4.matches(a) && ':' !in a) return false
		if (IPV4.matches(a) && a.split('.').any { it.toInt() > 255 }) return false
		// A literal (IPv4 dotted or IPv6 with colons) is parsed, never resolved through DNS.
		return try {
			InetAddress.getByName(a).isLoopbackAddress
		} catch (e: UnknownHostException) {
			false
		}
	}
}

/** Thrown at start when [Exposure.refusal] says no; the failure analyzer prints why and what to do. */
class ExposureRefusedException(val reason: String) : IllegalStateException("zm-baton refuses to start: $reason")

/** Checks the listening rule while the context starts, before the web server binds its port. */
@Component
class ExposureGuard(settings: AuthSettings) {
	private val log = LoggerFactory.getLogger(ExposureGuard::class.java)

	init {
		Exposure.refusal(settings.exposure())?.let { throw ExposureRefusedException(it) }
		log.info(
			"zm-baton authentication: {} (listening on {}{}{})", settings.mode.wire, settings.address?.ifBlank { null } ?: "every interface",
			if (settings.publishedOnLoopback) ", published on this PC's loopback only" else "",
			when {
				settings.tlsDirect -> ", TLS by this server"
				settings.tlsByProxy -> ", TLS ended by a proxy"
				else -> ""
			},
		)
	}
}

/** Prints [ExposureRefusedException] as a reason and the action, instead of a stack trace (META-INF/spring.factories). */
class ExposureRefusedFailureAnalyzer : AbstractFailureAnalyzer<ExposureRefusedException>() {
	override fun analyze(rootFailure: Throwable, cause: ExposureRefusedException): FailureAnalysis =
		FailureAnalysis(
			"zm-baton refused to start: ${cause.reason}.",
			"Listen on this PC only (ZM_BATON_SERVER_ADDRESS=127.0.0.1, the default), or set what is missing. " +
				"Opening the server beyond this PC is the owner's decision; read docs/stage1/auth.md first.",
			cause,
		)
}
