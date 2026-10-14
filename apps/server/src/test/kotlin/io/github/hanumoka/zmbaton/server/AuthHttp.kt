package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.LoginService
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/**
 * HTTP calls as the Web screen and the CLI make them on a server in `required` mode (docs/stage1/auth.md):
 * the owner signed in (the cookie, and the CSRF header on a changing request), a device with its device
 * token, or a session with its session token. A synthetic password, made for the test.
 */
class AuthHttp(private val base: String, private val json: ObjectMapper) {
	companion object {
		const val OWNER_LOGIN = "owner"
		const val OWNER_PASSWORD = "synthetic test password 1"
	}

	private val http = HttpClient.newHttpClient()

	/** `name=value` of the sign-in cookie; null until [signIn]. */
	var cookie: String? = null

	var csrf: String? = null

	data class Answer(val status: Int, val body: String, val headers: Map<String, List<String>>)

	/**
	 * One request. [signedIn] sends the cookie (and on a changing request the CSRF header); [bearer]
	 * sends `Authorization: Bearer`. [headers] are added as they are.
	 */
	fun send(
		method: String,
		path: String,
		body: String? = null,
		bearer: String? = null,
		signedIn: Boolean = true,
		csrfHeader: String? = null,
		headers: Map<String, String> = emptyMap(),
	): Answer {
		val b = HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
		if (bearer != null) b.header("Authorization", "Bearer $bearer")
		if (signedIn && cookie != null) {
			b.header("Cookie", cookie)
			if (method != "GET") (csrfHeader ?: csrf)?.let { b.header(LoginService.CSRF_HEADER, it) }
		}
		headers.forEach { (k, v) -> b.header(k, v) }
		val request = b.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build()
		val response = http.send(request, HttpResponse.BodyHandlers.ofString())
		return Answer(response.statusCode(), response.body(), response.headers().map())
	}

	fun call(method: String, path: String, body: String? = null, bearer: String? = null, signedIn: Boolean = true): Pair<Int, String> =
		send(method, path, body, bearer, signedIn).let { it.status to it.body }

	fun obj(body: String): Map<*, *> = json.readValue(body, Map::class.java)

	/**
	 * Signs the owner in. The first time in a database the owner has no password: a setup token makes
	 * it, as the server's log would give it.
	 */
	fun signIn(logins: LoginService): AuthHttp {
		val answer = if (logins.loginName(SessionService.OWNER) == null) {
			val token = logins.issueSetupToken(SessionService.OWNER, "first_password").first
			send("POST", "/api/auth/setup", """{"setup_token":"$token","login":"$OWNER_LOGIN","password":"$OWNER_PASSWORD"}""", signedIn = false)
		} else {
			send("POST", "/api/auth/login", """{"login":"$OWNER_LOGIN","password":"$OWNER_PASSWORD"}""", signedIn = false)
		}
		assertEquals(200, answer.status, answer.body)
		takeCookie(answer)
		csrf = obj(answer.body)["csrf_token"] as String
		return this
	}

	fun takeCookie(answer: Answer) {
		val set = answer.headers.entries.firstOrNull { it.key.equals("set-cookie", ignoreCase = true) }?.value?.firstOrNull()
			?: error("no Set-Cookie")
		cookie = set.substringBefore(';')
	}

	/** A device of the signed-in owner and its device token (`zm-baton login` in local mode, here signed in). */
	fun device(name: String = "auth-test-pc"): Pair<String, String> {
		val (status, body) = call("POST", "/api/devices", """{"name":"$name","os":"windows"}""")
		assertEquals(201, status, body)
		val o = obj(body)
		return o["device_id"] as String to o["device_token"] as String
	}
}
