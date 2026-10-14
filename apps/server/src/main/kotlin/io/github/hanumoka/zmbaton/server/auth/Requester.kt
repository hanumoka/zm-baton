package io.github.hanumoka.zmbaton.server.auth

import io.github.hanumoka.zmbaton.server.ForbiddenException
import io.github.hanumoka.zmbaton.server.SessionService
import io.github.hanumoka.zmbaton.server.SessionTokens
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * Who sent a person's request: the one place that says so. Every person endpoint asks it first and
 * hands the answer to Permissions, which judges what that person may do.
 *
 * There is no person authentication yet (the next slice adds it), and the server listens on loopback
 * only (stage-1 exit criterion 4), so [person] answers [STAGE1_PERSON], the one-person organisation's
 * owner. Person authentication replaces the body of [person] and nothing else.
 */
@Component
class Requester(private val jdbc: JdbcClient, private val sessions: SessionService) {

	companion object {
		/**
		 * The person every person request comes from until person authentication exists. Also the default
		 * actor of service methods that tests and server-internal callers use without naming one.
		 */
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
		val token = SessionTokens.bearer(authorization)
		if (token != null) {
			val sessionId = jdbc.sql("select id from agent_sessions where token_hash = :hash")
				.param("hash", SessionTokens.hash(token)).query(String::class.java).optional().orElse("unknown")
			sessions.audit("session", sessionId, "person_action.refused", "api", action, mapOf("reason" to "session token"))
			throw ForbiddenException("$action is a person action; a session token cannot do it")
		}
		return STAGE1_PERSON
	}
}
