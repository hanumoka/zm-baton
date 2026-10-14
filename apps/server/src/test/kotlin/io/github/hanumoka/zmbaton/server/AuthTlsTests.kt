package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.LoginService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TLS with Spring Boot's own settings (docs/stage1/auth.md): a self-signed key store made here with the
 * JDK's keytool and never kept, a server on loopback in `required` mode, and the sign-in cookie that
 * TLS makes Secure and names `__Host-zmb_login`.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = ["zm-baton.auth.mode=required", "zm-baton.auth.bcrypt-strength=4"],
)
class AuthTlsTests @Autowired constructor(
	env: Environment,
	private val json: ObjectMapper,
	private val logins: LoginService,
) {
	companion object {
		private val dir: Path = Files.createTempDirectory("zmb-tls-test")
		private val store: Path = dir.resolve("server.p12")

		/** Synthetic, made for this run. */
		private val password = UUID.randomUUID().toString()

		@JvmStatic
		@DynamicPropertySource
		fun tls(registry: DynamicPropertyRegistry) {
			if (!Files.exists(store)) {
				val keytool = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool")
				val p = ProcessBuilder(
					keytool.toString(), "-genkeypair", "-alias", "zmb", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
					"-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
					"-keystore", store.toString(), "-storepass", password, "-keypass", password,
				).redirectErrorStream(true).start()
				check(p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0) { p.inputStream.bufferedReader().readText() }
				store.toFile().deleteOnExit()
				dir.toFile().deleteOnExit()
			}
			registry.add("server.ssl.enabled") { "true" }
			registry.add("server.ssl.key-store") { store.toUri().toString() }
			registry.add("server.ssl.key-store-password") { password }
			registry.add("server.ssl.key-store-type") { "PKCS12" }
		}
	}

	private val base = "https://localhost:" + env.getRequiredProperty("local.server.port")

	/** Trusts the test's own certificate only. */
	private val http: HttpClient = run {
		val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(store).use { load(it, password.toCharArray()) } }
		val trust = KeyStore.getInstance("PKCS12").apply { load(null, null); setCertificateEntry("zmb", keys.getCertificate("zmb") as X509Certificate) }
		val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
		HttpClient.newBuilder().sslContext(SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }).build()
	}

	private fun send(method: String, path: String, body: String? = null): HttpResponse<String> =
		http.send(
			HttpRequest.newBuilder(URI.create(base + path)).header("Content-Type", "application/json")
				.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()).build(),
			HttpResponse.BodyHandlers.ofString(),
		)

	@Test
	fun `over TLS the state says secure and the sign-in cookie is Secure with the __Host- name`() {
		val state = json.readValue(send("GET", "/api/auth/state").body(), Map::class.java)
		assertEquals(true, state["secure"])
		val token = logins.issueSetupToken(SessionService.OWNER, "reset").first
		val setup = send("POST", "/api/auth/setup", """{"setup_token":"$token","login":"${AuthHttp.OWNER_LOGIN}","password":"${AuthHttp.OWNER_PASSWORD}"}""")
		assertEquals(200, setup.statusCode(), setup.body())
		val cookie = setup.headers().firstValue("set-cookie").orElseThrow()
		assertTrue(cookie.startsWith("__Host-zmb_login="), cookie.substringBefore('='))
		assertContains(cookie, "Secure")
		assertContains(cookie, "HttpOnly")
		assertContains(cookie, "SameSite=Strict")
		assertContains(cookie, "Path=/")
	}
}
