package io.github.hanumoka.zmbaton.server.auth

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.ConflictException
import io.github.hanumoka.zmbaton.server.NotFoundException
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.Permissions
import io.github.hanumoka.zmbaton.server.permission.Target
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class PersonLoginView(
	val login: String?,
	@JsonProperty("pending_until") val pendingUntil: OffsetDateTime?,
	@JsonProperty("can_issue") val canIssue: Boolean,
	val reason: String?,
)

data class LoginSetupView(
	@JsonProperty("setup_token") val setupToken: String,
	@JsonProperty("expires_at") val expiresAt: OffsetDateTime,
)

/** 조직 소유자가 구성원에게 첫 비밀번호 또는 재설정 코드를 전달한다. 발급만으로 기존 비밀번호를 바꾸지 않는다. */
@RestController
@RequestMapping("/api/org/people/{id}/login")
class LoginAdministration(
	private val permissions: Permissions,
	private val jdbc: JdbcClient,
	private val logins: LoginService,
	private val settings: AuthSettings,
) {
	@GetMapping
	fun view(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): ResponseEntity<PersonLoginView> {
		val me = owner(authorization)
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(state(me, id))
	}

	@PostMapping("/setup")
	fun issue(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): ResponseEntity<LoginSetupView> {
		val me = owner(authorization)
		val state = state(me, id)
		if (!state.canIssue) throw ConflictException(state.reason!!)
		val (token, until) = logins.issueSetupToken(id, if (state.login == null) "first_password" else "reset", me.person)
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(LoginSetupView(token, until))
	}

	private fun owner(authorization: String?): Actor {
		val me = permissions.person(authorization, "manage member logins", Act.READ)
		val orgId = me.orgId!!
		return permissions.require(me, Act.LOGIN_MANAGE, Target("org", orgId, orgId, owner = permissions.facts.ownerOf(orgId)))
	}

	private fun state(me: Actor, id: String): PersonLoginView {
		val status = jdbc.sql("select u.status from users u join memberships m on m.user_id = u.id where u.id = :id and m.org_id = :org")
			.param("id", id).param("org", me.orgId).query(String::class.java).optional().orElse(null)
			?: throw NotFoundException("이 조직에 없는 사람이다")
		val reason = when {
			!settings.required -> "개인 로그인이 꺼져 있다. 서버에서 로그인 필수 모드를 켠 뒤 발급할 수 있다."
			id == me.person -> "내 비밀번호는 내 계정에서 바꾼다. 비밀번호를 잊었으면 서버의 계정 복구 절차를 따른다."
			status != "active" -> "비활성 상태다. 활동 중으로 바꾼 뒤 발급할 수 있다."
			else -> null
		}
		val pending = jdbc.sql("select max(expires_at) from setup_tokens where user_id = :id and used_at is null and dropped_at is null and expires_at > now()")
			.param("id", id).query { rs, _ -> rs.getObject(1, OffsetDateTime::class.java) }.list().firstOrNull()
		return PersonLoginView(logins.loginName(id), pending, reason == null, reason)
	}
}
