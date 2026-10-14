package io.github.hanumoka.zmbaton.server.auth

import io.github.hanumoka.zmbaton.server.ForbiddenException
import io.github.hanumoka.zmbaton.server.SessionService
import io.github.hanumoka.zmbaton.server.SessionTokens
import io.github.hanumoka.zmbaton.server.UnauthorizedException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * Who sent a request: the one place that says so. Every person endpoint asks it first and hands the
 * answer to Permissions, which judges what that person may do; every device endpoint asks it which
 * device calls.
 *
 * [AuthInterceptor] has authenticated the request by then and left its [Caller]. In `local` mode a
 * person request without a sign-in is [STAGE1_PERSON], the one-person organisation's owner, as before
 * person authentication; the server then listens on this PC only (AuthSettings, ExposureGuard). In
 * `required` mode the person is the one signed in on the Web, and a device is the one whose device
 * token came with the request (docs/stage1/auth.md).
 */
@Component
class Requester(private val jdbc: JdbcClient, private val sessions: SessionService, private val settings: AuthSettings) {

	companion object {
		/** The person every person request comes from in `local` mode. */
		const val STAGE1_PERSON = "usr_owner"

		/** The one-person organisation stage 1 starts with (V3). */
		const val STAGE1_ORG = "org_owner"
	}

	/**
	 * The person a person action acts for. A session token is refused and the refusal audited first:
	 * what a person decides is never done with a session token (contract "등록과 세션 토큰" 6). Not
	 * transactional on purpose: the audit row of a refusal must survive the refusal.
	 */
	fun person(authorization: String?, action: String): String {
		refuseSessionToken(authorization, action)
		val caller = Caller.current()
		if (caller?.person != null && caller.device == null) return caller.person
		if (!settings.required) return STAGE1_PERSON
		throw UnauthorizedException("로그인이 필요하다($action)")
	}

	/**
	 * The person a CLI command acts for: the signed-in person, or the person of the device whose token
	 * came (export, zm-docs import, the device's repositories). The device is given too, for the record.
	 */
	fun personOrDevice(authorization: String?, action: String): Pair<String, String?> {
		refuseSessionToken(authorization, action)
		val caller = Caller.current()
		if (caller?.person != null) return caller.person to caller.device
		if (!settings.required) return STAGE1_PERSON to null
		throw UnauthorizedException("로그인이나 기기 토큰이 필요하다($action)")
	}

	/**
	 * The device a device call comes from: the device of its token, which must be the device the call
	 * names ([claimed], when it names one). In `local` mode without a token the call is taken as the
	 * device it names (null when it names none), unless that device was revoked.
	 */
	fun device(claimed: String?): String? {
		val named = claimed?.trim()?.ifEmpty { null }
		val token = Caller.current()?.device
		if (token != null) {
			if (named != null && named != token) throw ForbiddenException("이 기기 토큰은 $named 기기의 것이 아니다")
			return token
		}
		if (settings.required) throw UnauthorizedException("이 서버는 기기 토큰을 요구한다. 그 기기에서 zm-baton login을 다시 실행한다")
		if (named != null) {
			val revoked = jdbc.sql("select revoked_at is not null from devices where id = :id").param("id", named)
				.query(Boolean::class.javaObjectType).optional().orElse(false)
			if (revoked) throw UnauthorizedException("이 기기는 폐기됐다. 그 기기를 다시 쓰려면 zm-baton login --force로 새 기기로 등록한다")
		}
		return named
	}

	private fun refuseSessionToken(authorization: String?, action: String) {
		val token = SessionTokens.bearer(authorization)?.takeIf { !it.startsWith(Tokens.DEVICE) } ?: return
		val sessionId = jdbc.sql("select id from agent_sessions where token_hash = :hash")
			.param("hash", SessionTokens.hash(token)).query(String::class.java).optional().orElse("unknown")
		sessions.audit("session", sessionId, "person_action.refused", "api", action, mapOf("reason" to "session token"))
		throw ForbiddenException("$action is a person action; a session token cannot do it")
	}
}
