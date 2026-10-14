package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64

// Contract v1 "직접 연 AI 세션을 서버에 등록한다" (protocol_version 2): device registration,
// session registration with a short-lived session token, and the events a session sends.
// Wire format: snake_case, like the run reports.

data class RegisterDevice(val name: String, val os: String? = null)

data class DeviceRegistered(@JsonProperty("device_id") val deviceId: String)

data class RepositoryIn(
	@JsonProperty("repo_key") val repoKey: String? = null,
	val remote: String? = null,
	val prefix: String? = null,
	val branch: String? = null,
)

data class RegisterSession(
	@JsonProperty("protocol_version") val protocolVersion: Int,
	@JsonProperty("device_id") val deviceId: String,
	val executor: String,
	@JsonProperty("executor_version") val executorVersion: String? = null,
	@JsonProperty("executor_session_id") val executorSessionId: String,
	val repository: RepositoryIn? = null,
)

data class SessionRegistered(
	@JsonProperty("session_id") val sessionId: String,
	val token: String,
	@JsonProperty("token_expires_at") val tokenExpiresAt: OffsetDateTime,
	/** True when a live registration of the same executor session was reused (a resume). */
	val reused: Boolean,
	@JsonProperty("status_card") val statusCard: String,
	/** Whether this server relays permission requests and questions (ZM_BATON_RELAY). Off by default. */
	@JsonProperty("relay_enabled") val relayEnabled: Boolean = false,
)

data class SessionEventIn(
	@JsonProperty("event_id") val eventId: String,
	@JsonProperty("local_seq") val localSeq: Long,
	val kind: String,
	val payload: Map<String, Any?> = emptyMap(),
)

data class SessionEventBatch(
	@JsonProperty("protocol_version") val protocolVersion: Int,
	val events: List<SessionEventIn>,
)

data class SessionEventResult(
	val stored: Int,
	val duplicates: Int,
	val rejected: Int,
	/** One line per rejected event, "<local_seq>: <why>", so the CLI can log what went wrong. */
	val reasons: List<String>,
)

data class StatusCard(@JsonProperty("status_card") val statusCard: String)

@ResponseStatus(HttpStatus.UNAUTHORIZED)
class UnauthorizedException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.FORBIDDEN)
class ForbiddenException(message: String) : RuntimeException(message)

/**
 * Session tokens belong to zm-baton, not to an AI provider (contract rule 9). The server keeps
 * only their SHA-256; the device keeps the value in its zm-baton config folder.
 */
object SessionTokens {
	private val random = SecureRandom()

	fun newToken(): String {
		val bytes = ByteArray(32).also(random::nextBytes)
		return "zmbs_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
	}

	fun hash(token: String): String =
		MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
			.joinToString("") { "%02x".format(it) }

	/** The token from an `Authorization: Bearer <token>` header, or null when there is none. */
	fun bearer(header: String?): String? {
		if (header == null) return null
		val trimmed = header.trim()
		if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
		return trimmed.substring(7).trim().ifEmpty { null }
	}
}

@RestController
@RequestMapping("/api")
class SessionController(private val sessions: SessionService) {

	/** `zm-baton login`: one record per device. No person authentication yet (loopback only). */
	@PostMapping("/devices")
	@ResponseStatus(HttpStatus.CREATED)
	fun registerDevice(@RequestBody body: RegisterDevice): DeviceRegistered = sessions.registerDevice(body)

	/** The start hook of a directly opened session (`zm-baton session register`). */
	@PostMapping("/sessions")
	@ResponseStatus(HttpStatus.CREATED)
	fun register(@RequestBody body: RegisterSession): SessionRegistered = sessions.register(body)

	@PostMapping("/sessions/{id}/events")
	fun events(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: SessionEventBatch,
	): SessionEventResult = sessions.ingest(id, requireToken(authorization), body)

	@GetMapping("/sessions/{id}/status-card")
	fun statusCard(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
	): StatusCard = StatusCard(sessions.statusCardFor(id, requireToken(authorization)))

	private fun requireToken(authorization: String?): String =
		SessionTokens.bearer(authorization) ?: throw UnauthorizedException("a session token is required")
}
