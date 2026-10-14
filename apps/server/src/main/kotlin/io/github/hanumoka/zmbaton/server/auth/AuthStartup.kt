package io.github.hanumoka.zmbaton.server.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * At start in `required` mode, a one-time setup token for each organisation owner without a password,
 * and for the person ZM_BATON_AUTH_SETUP names (a forgotten password, or another person's first one).
 *
 * The token is written to the server's own log, once: reading that log takes this PC (or the server's
 * machine), which is the point. Only its hash is stored, it works once and for 24 hours, and the next
 * start replaces it. This is the one place a secret reaches a log, on purpose (docs/stage1/auth.md).
 */
@Component
class AuthStartup(
	private val settings: AuthSettings,
	private val logins: LoginService,
	private val jdbc: JdbcClient,
	@Value("\${zm-baton.auth.setup-for:}") private val setupFor: String,
) : ApplicationRunner {
	private val log = LoggerFactory.getLogger(AuthStartup::class.java)

	override fun run(args: ApplicationArguments) {
		val named = setupFor.trim().ifEmpty { null }
		if (!settings.required) {
			if (named != null) log.warn("ZM_BATON_AUTH_SETUP is ignored: this server is in local mode, where nobody signs in")
			return
		}
		for ((person, name) in logins.ownersWithoutPassword()) issue(person, name, "first_password")
		if (named != null) {
			val name = jdbc.sql("select display_name from users where id = :id and status = 'active'").param("id", named)
				.query(String::class.java).optional().orElse(null)
			if (name == null) {
				log.warn("ZM_BATON_AUTH_SETUP names {}, which is not an active person; no setup token was made", named)
			} else {
				issue(named, name, "reset")
				log.warn("Remove ZM_BATON_AUTH_SETUP once the password is set: while it stays, every start makes a new setup token for {}", named)
			}
		}
	}

	private fun issue(person: String, name: String, reason: String) {
		val (token, expires) = logins.issueSetupToken(person, reason)
		val what = if (reason == "reset") "a new password" else "the first password"
		log.warn(
			"zm-baton setup token for {} of {} ({}), valid until {} or its first use; the next start replaces it: {}  " +
				"Open the Web screen and choose 「처음 비밀번호 정하기」.",
			what, person, name, expires, token,
		)
	}
}

/**
 * Drops what sign-in leaves behind, once an hour: logins ended or expired 30 days ago, and setup tokens
 * and device logins a day past their end.
 */
@Component
class AuthSweeper(private val jdbc: JdbcClient) {
	@Scheduled(fixedDelayString = "\${zm-baton.auth-sweep-ms:3600000}", initialDelayString = "\${zm-baton.auth-sweep-first-ms:300000}")
	fun sweep() {
		jdbc.sql("delete from web_logins where coalesce(ended_at, expires_at) < now() - interval '30 days'").update()
		jdbc.sql("delete from setup_tokens where expires_at < now() - interval '1 day'").update()
		jdbc.sql("delete from device_logins where expires_at < now() - interval '1 day'").update()
	}
}
