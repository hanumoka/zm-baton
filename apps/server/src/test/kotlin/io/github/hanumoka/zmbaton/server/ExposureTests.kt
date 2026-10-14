package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.AuthMode
import io.github.hanumoka.zmbaton.server.auth.AuthSettings
import io.github.hanumoka.zmbaton.server.auth.Exposure
import io.github.hanumoka.zmbaton.server.auth.ExposureGuard
import io.github.hanumoka.zmbaton.server.auth.ExposureRefusedException
import io.github.hanumoka.zmbaton.server.auth.ExposureRefusedFailureAnalyzer
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the server may listen (stage-1 exit criterion 4, docs/stage1/auth.md): this PC's loopback in any
 * mode; anywhere else only with `required` and TLS. No server binds a port here: the guard refuses while
 * the context starts, before the web server would bind, so it is checked as a plain object.
 */
class ExposureTests {

	private fun facts(address: String?, mode: AuthMode = AuthMode.LOCAL, direct: Boolean = false, proxy: Boolean = false, published: Boolean = false) =
		Exposure.Facts(address, published, mode, direct, proxy)

	@Test
	fun `loopback literals and localhost are this PC, anything else is not, and no name is looked up`() {
		for (a in listOf("127.0.0.1", "127.0.0.2", "localhost", "LOCALHOST", "::1", "[::1]", "0:0:0:0:0:0:0:1", " 127.0.0.1 ")) {
			assertTrue(Exposure.isLoopback(a), a)
		}
		for (a in listOf(null, "", " ", "0.0.0.0", "::", "192.168.0.10", "10.0.0.1", "example.com", "my-pc", "999.0.0.1", "127.0.0.1.example.com")) {
			assertFalse(Exposure.isLoopback(a), a.toString())
		}
	}

	@Test
	fun `loopback starts in any mode, and so does a container published on loopback`() {
		assertNull(Exposure.refusal(facts("127.0.0.1")))
		assertNull(Exposure.refusal(facts("::1", AuthMode.REQUIRED)))
		assertNull(Exposure.refusal(facts("0.0.0.0", published = true)), "deploy/compose publishes 127.0.0.1:18081 only")
	}

	@Test
	fun `beyond loopback needs both required and TLS, its own or a proxy's`() {
		val bare = assertNotNull(Exposure.refusal(facts("0.0.0.0")))
		assertContains(bare, "ZM_BATON_AUTH=required")
		assertContains(bare, "TLS")
		assertContains(assertNotNull(Exposure.refusal(facts("", AuthMode.LOCAL, direct = true))), "ZM_BATON_AUTH=required")
		assertContains(assertNotNull(Exposure.refusal(facts("", AuthMode.LOCAL, direct = true))), "every interface")
		val noTls = assertNotNull(Exposure.refusal(facts("192.168.0.10", AuthMode.REQUIRED)))
		assertContains(noTls, "TLS")
		assertFalse("ZM_BATON_AUTH" in noTls)
		assertNull(Exposure.refusal(facts("0.0.0.0", AuthMode.REQUIRED, direct = true)))
		assertNull(Exposure.refusal(facts("0.0.0.0", AuthMode.REQUIRED, proxy = true)))
	}

	@Test
	fun `the guard reads the settings and refuses before anything listens, with a reason and an action`() {
		val local = MockEnvironment().withProperty("server.address", "0.0.0.0")
		val refused = assertFailsWith<ExposureRefusedException> { ExposureGuard(AuthSettings(local)) }
		assertContains(refused.reason, "ZM_BATON_AUTH=required")
		val analysis = ExposureRefusedFailureAnalyzer().analyze(refused)!!
		assertContains(analysis.description.orEmpty(), "refused to start")
		assertContains(analysis.action.orEmpty(), "ZM_BATON_SERVER_ADDRESS=127.0.0.1")

		// TLS enabled without a key store is not TLS.
		val emptyStore = MockEnvironment().withProperty("server.address", "0.0.0.0").withProperty("zm-baton.auth.mode", "required")
			.withProperty("server.ssl.enabled", "true").withProperty("server.ssl.key-store", "")
		assertFailsWith<ExposureRefusedException> { ExposureGuard(AuthSettings(emptyStore)) }

		val open = MockEnvironment().withProperty("server.address", "0.0.0.0").withProperty("zm-baton.auth.mode", "REQUIRED")
			.withProperty("server.ssl.enabled", "true").withProperty("server.ssl.key-store", "file:/somewhere/keys.p12")
		val settings = AuthSettings(open)
		assertTrue(settings.required && settings.tlsDirect && settings.secure)
		ExposureGuard(settings)

		val proxied = AuthSettings(MockEnvironment().withProperty("zm-baton.auth.mode", "required").withProperty("zm-baton.tls.proxy", "on"))
		assertTrue(proxied.tlsByProxy && proxied.secure)
		assertFalse(AuthSettings(MockEnvironment()).secure, "plain loopback: the cookie is not Secure")
	}

	@Test
	fun `the mode is local unless it says required, and a wrong value stops the start`() {
		assertEquals(AuthMode.LOCAL, AuthSettings(MockEnvironment()).mode)
		assertEquals(AuthMode.REQUIRED, AuthSettings(MockEnvironment().withProperty("zm-baton.auth.mode", " required ")).mode)
		assertFailsWith<IllegalStateException> { AuthSettings(MockEnvironment().withProperty("zm-baton.auth.mode", "off")) }
	}

	@Test
	fun `relays need required - in local mode on is ignored`() {
		val local = AuthSettings(MockEnvironment())
		val required = AuthSettings(MockEnvironment().withProperty("zm-baton.auth.mode", "required"))
		assertFalse(RelaySwitch("on", local).enabled, "H-21: without sign-in any program on this PC could answer")
		assertTrue(RelaySwitch("on", required).enabled)
		assertFalse(RelaySwitch("off", required).enabled)
	}
}
