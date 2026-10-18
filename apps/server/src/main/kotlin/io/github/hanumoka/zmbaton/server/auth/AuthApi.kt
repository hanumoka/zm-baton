package io.github.hanumoka.zmbaton.server.auth

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.admin.ServerAdministration
import io.github.hanumoka.zmbaton.server.ConflictException
import io.github.hanumoka.zmbaton.server.DeviceRegistered
import io.github.hanumoka.zmbaton.server.UnauthorizedException
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Permissions
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// The sign-in API (docs/stage1/auth.md). Wire format: snake_case, like the rest of the API.

data class PersonRef(val id: String, @JsonProperty("display_name") val displayName: String, val login: String?)

/** What the Web screen needs to know before it draws anything. */
data class AuthState(
	/** `local` or `required`. */
	val mode: String,
	/** The browser reaches the server over TLS. */
	val secure: Boolean,
	/** Who the screen acts for: the signed-in person, or in `local` mode the owner. Null when nobody is signed in. */
	val person: PersonRef?,
	/** The login's CSRF token, sent back in `X-CSRF-Token` with every changing request. */
	@JsonProperty("csrf_token") val csrfToken: String?,
	/** No organisation owner has a password yet: the screen offers 「처음 비밀번호 정하기」 with the setup token from the server's log. */
	@JsonProperty("setup_pending") val setupPending: Boolean,
	@JsonProperty("server_admin") val serverAdmin: Boolean = false,
	@JsonProperty("server_setup_pending") val serverSetupPending: Boolean = false,
)

data class LoginIn(val login: String, val password: String)

data class SetupIn(@JsonProperty("setup_token") val setupToken: String, val login: String, val password: String)

data class PasswordIn(@JsonProperty("current_password") val currentPassword: String, @JsonProperty("new_password") val newPassword: String)

data class PasswordChanged(@JsonProperty("other_logins_ended") val otherLoginsEnded: Int)

@RestController
@RequestMapping("/api/auth")
class AuthController(
	private val logins: LoginService,
	private val settings: AuthSettings,
	private val jdbc: JdbcClient,
	private val requester: Requester,
	private val administration: ServerAdministration,
) {

	@Access(AccessKind.PUBLIC)
	@GetMapping("/state")
	fun state(request: HttpServletRequest): AuthState = stateFor(request, Caller.current()?.person, cookieToken(request))

	/** Signs in and sets the cookie: HttpOnly, SameSite=Strict, and Secure over TLS. */
	@Access(AccessKind.PUBLIC)
	@PostMapping("/login")
	fun login(@RequestBody body: LoginIn, request: HttpServletRequest, response: HttpServletResponse): AuthState {
		requireRequired()
		val signed = logins.login(body.login, body.password, request.remoteAddr)
		response.addHeader(HttpHeaders.SET_COOKIE, logins.cookie(signed.token).toString())
		return stateFor(request, signed.person, signed.token)
	}

	/** The first password or reset, with a one-time code from the organisation owner or server recovery. Signs in too. */
	@Access(AccessKind.PUBLIC)
	@PostMapping("/setup")
	fun setup(@RequestBody body: SetupIn, request: HttpServletRequest, response: HttpServletResponse): AuthState {
		requireRequired()
		val signed = logins.setup(body.setupToken, body.login, body.password, request.remoteAddr)
		response.addHeader(HttpHeaders.SET_COOKIE, logins.cookie(signed.token).toString())
		return stateFor(request, signed.person, signed.token)
	}

	@PostMapping("/logout")
	fun logout(request: HttpServletRequest, response: HttpServletResponse): AuthState {
		logins.logout(cookieToken(request))
		response.addHeader(HttpHeaders.SET_COOKIE, logins.clearCookie().toString())
		return stateFor(request, null, null)
	}

	/** Changes the signed-in person's password; their other logins end. */
	@PutMapping("/password")
	fun password(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?, @RequestBody body: PasswordIn): PasswordChanged {
		requireRequired()
		val person = requester.person(authorization, "change my password")
		return PasswordChanged(logins.changePassword(person, Caller.current()?.loginId, body.currentPassword, body.newPassword))
	}

	private fun requireRequired() {
		if (!settings.required) throw ConflictException("이 서버는 local 모드라 로그인이 없다. 이 PC에서 소유자로 쓴다")
	}

	private fun cookieToken(request: HttpServletRequest): String? =
		request.cookies?.firstOrNull { it.name == logins.cookieName }?.value?.ifBlank { null }

	private fun stateFor(request: HttpServletRequest, signedIn: String?, token: String?): AuthState {
		val person = if (settings.required) signedIn else Requester.STAGE1_PERSON
		val ref = person?.let { p ->
			jdbc.sql("select display_name from users where id = :id").param("id", p).query(String::class.java).optional().orElse(null)
				?.let { PersonRef(p, it, logins.loginName(p)) }
		}
		val csrf = if (settings.required && signedIn != null && token != null) logins.csrfFor(token) else null
		val pending = settings.required && logins.ownersWithoutPassword().isNotEmpty()
		val admin = person != null && administration.isAdministrator(person)
		return AuthState(settings.mode.wire, settings.secure, ref, csrf, pending, admin, admin && administration.setupPending())
	}
}

@RestController
@RequestMapping("/api")
class DeviceAuthController(
	private val devices: DeviceAuthService,
	private val permissions: Permissions,
	private val requester: Requester,
) {

	@GetMapping("/me/devices")
	fun mine(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): List<DeviceView> =
		devices.list(permissions.person(authorization, "list my devices", Act.READ).person!!)

	/** A lost device: its token stops and its live sessions end. */
	@PostMapping("/me/devices/{id}/revoke")
	fun revoke(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): DeviceView {
		val me = permissions.person(authorization, "revoke a device")
		permissions.require(me, Act.DEVICE, permissions.device(id))
		return devices.revoke(id.trim(), me.person!!)
	}

	/** A device token for a device that has none (`zm-baton login` on a device registered before tokens, `local` mode). */
	@PostMapping("/devices/{id}/token")
	fun rekey(@PathVariable id: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): DeviceRegistered {
		val me = permissions.person(authorization, "get a device token")
		permissions.require(me, Act.DEVICE, permissions.device(id))
		return devices.rekey(id.trim(), me.person!!)
	}

	/** The device of the token that came: `zm-baton status` checks its token with it. */
	@Access(AccessKind.DEVICE)
	@GetMapping("/devices/self")
	fun self(): DeviceSelf = devices.self(requester.device(null) ?: throw UnauthorizedException("기기 토큰이 없다. zm-baton login을 다시 실행한다"))

	@Access(AccessKind.PUBLIC)
	@PostMapping("/device-logins")
	fun start(@RequestBody body: DeviceLoginStart): DeviceLoginStarted = devices.startLogin(body)

	/** The CLI's poll. Errors answer HTTP 400 with an RFC 8628 `error` code, a token answers 200. */
	@Access(AccessKind.PUBLIC)
	@PostMapping("/device-logins/token")
	fun poll(@RequestBody body: DeviceLoginPoll): ResponseEntity<Any> {
		val outcome = devices.poll(body.deviceCode)
		return outcome.token?.let { ResponseEntity.ok(it as Any) }
			?: ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to outcome.error, "message" to outcome.message))
	}

	@GetMapping("/device-logins/{code}")
	fun view(@PathVariable code: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): DeviceLoginView =
		devices.loginView(code, permissions.person(authorization, "read a device login", Act.READ).person!!)

	@PostMapping("/device-logins/{code}/approve")
	fun approve(@PathVariable code: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): DeviceLoginView =
		devices.approve(code, permissions.person(authorization, "approve a device login", Act.READ).person!!)

	@PostMapping("/device-logins/{code}/deny")
	fun deny(@PathVariable code: String, @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): DeviceLoginView =
		devices.deny(code, permissions.person(authorization, "deny a device login", Act.READ).person!!)
}
