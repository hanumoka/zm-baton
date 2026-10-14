package io.github.hanumoka.zmbaton.server.auth

import io.github.hanumoka.zmbaton.server.ForbiddenException
import io.github.hanumoka.zmbaton.server.SessionService
import io.github.hanumoka.zmbaton.server.SessionTokens
import io.github.hanumoka.zmbaton.server.UnauthorizedException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Authenticates every `/api` request before its controller runs, by the endpoint's [Access] kind, and
 * leaves the [Caller] for [Requester]. In `local` mode it changes nothing for a request without
 * credentials; a device token sent is still checked. In `required` mode:
 *
 * 1. A person's endpoint needs the sign-in cookie, and a changing request (anything but GET, HEAD and
 *    OPTIONS) also the login's CSRF token in `X-CSRF-Token`. A device token is refused there. A
 *    session token passes on to Requester, which refuses it and writes the refusal down.
 * 2. A device's endpoint needs a device token of a live device.
 * 3. A session's endpoint is checked by the session services (session token), as before.
 *
 * In both modes a browser request that another site's page sent (`Sec-Fetch-Site: cross-site`) is
 * refused: no page of another site has a reason to call this API.
 */
@Component
class AuthInterceptor(
	private val settings: AuthSettings,
	private val logins: LoginService,
	private val devices: DeviceAuthService,
	private val sessions: SessionService,
) : HandlerInterceptor {

	companion object {
		private val SAFE = setOf("GET", "HEAD", "OPTIONS")
	}

	override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
		if (handler !is HandlerMethod) return true
		if (request.getHeader("Sec-Fetch-Site").equals("cross-site", ignoreCase = true)) {
			throw ForbiddenException("다른 사이트의 페이지가 보낸 요청은 받지 않는다")
		}
		val kind = handler.getMethodAnnotation(Access::class.java)?.value ?: AccessKind.PERSON
		val bearer = SessionTokens.bearer(request.getHeader(HttpHeaders.AUTHORIZATION))
		val deviceToken = bearer?.takeIf { it.startsWith(Tokens.DEVICE) }
		val sessionToken = bearer != null && deviceToken == null

		val caller: Caller? = when (kind) {
			AccessKind.DEVICE, AccessKind.PERSON_OR_DEVICE ->
				deviceToken?.let { devices.authenticate(it) } ?: if (kind == AccessKind.PERSON_OR_DEVICE) signedIn(request) else null
			AccessKind.PERSON -> {
				if (deviceToken != null && settings.required) refuseDeviceToken(deviceToken, request)
				if (sessionToken) null else signedIn(request)
			}
			AccessKind.PUBLIC -> signedIn(request, checkCsrf = false)
			AccessKind.SESSION -> null
		}

		if (settings.required) {
			when (kind) {
				AccessKind.DEVICE -> if (caller?.device == null) {
					throw UnauthorizedException("이 서버는 기기 토큰을 요구한다. 그 기기에서 zm-baton login을 다시 실행한다")
				}
				AccessKind.PERSON_OR_DEVICE -> if (caller == null) {
					throw UnauthorizedException("로그인이나 기기 토큰이 필요하다. 웹에 로그인하거나 그 기기에서 zm-baton login을 다시 실행한다")
				}
				AccessKind.PERSON -> if (caller == null && !sessionToken) throw UnauthorizedException("로그인이 필요하다")
				AccessKind.SESSION, AccessKind.PUBLIC -> {}
			}
		}
		request.setAttribute(Caller.ATTRIBUTE, caller ?: Caller.NONE)
		return true
	}

	/**
	 * The signed-in person of the request's cookie, in `required` mode only; null without a live login.
	 * A changing request must carry the login's CSRF token.
	 */
	private fun signedIn(request: HttpServletRequest, checkCsrf: Boolean = true): Caller? {
		if (!settings.required) return null
		val token = request.cookies?.firstOrNull { it.name == logins.cookieName }?.value?.ifBlank { null } ?: return null
		val login = logins.authenticate(token) ?: return null
		if (checkCsrf && request.method.uppercase() !in SAFE) {
			val sent = request.getHeader(LoginService.CSRF_HEADER)
			if (sent == null || !Tokens.same(sent, logins.csrfFor(token))) {
				throw ForbiddenException("CSRF 토큰이 없거나 맞지 않다. 화면을 다시 불러온다")
			}
		}
		return Caller(person = login.person, loginId = login.id)
	}

	/** A device token on a person's endpoint: a device acts for its person only through the CLI's own calls. */
	private fun refuseDeviceToken(token: String, request: HttpServletRequest): Nothing {
		val device = runCatching { devices.authenticate(token).device }.getOrNull() ?: "unknown"
		sessions.audit("system", device, "person_action.refused", "api", request.requestURI, mapOf("reason" to "device token"))
		throw ForbiddenException("사람이 하는 일은 기기 토큰으로 할 수 없다. 웹에 로그인해서 한다")
	}
}

@Configuration(proxyBeanMethods = false)
class AuthWebConfig(private val interceptor: AuthInterceptor) : WebMvcConfigurer {
	override fun addInterceptors(registry: InterceptorRegistry) {
		registry.addInterceptor(interceptor).addPathPatterns("/api/**")
	}
}
