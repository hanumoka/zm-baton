package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.auth.DeviceAuthService
import io.github.hanumoka.zmbaton.server.auth.DeviceLoginStart
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class DeviceIssuanceTests @Autowired constructor(
	private val devices: DeviceAuthService, private val org: OrgService, private val jdbc: JdbcClient,
	transactions: PlatformTransactionManager,
) {
	private val tx = TransactionTemplate(transactions)

	@Test fun `poll waits for the approving person lock before issuing a device`() {
		val person = org.addPerson("usr_owner", PersonIn("device race fixture", listOf("requester")))
		val login = devices.startLogin(DeviceLoginStart("pending-race"))
		devices.approve(login.userCode, person)
		val locked = CountDownLatch(1)
		val release = CountDownLatch(1)
		val changing = CompletableFuture.runAsync {
			tx.execute {
				jdbc.sql("update users set status = 'inactive' where id = :id").param("id", person).update()
				locked.countDown()
				check(release.await(15, TimeUnit.SECONDS))
			}
		}
		try {
			assertTrue(locked.await(5, TimeUnit.SECONDS))
			val polling = CompletableFuture.supplyAsync { devices.poll(login.deviceCode) }
			val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
			var waiting = false
			while (!polling.isDone && System.nanoTime() < deadline) {
				waiting = jdbc.sql("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and query like 'select status = %from users%for update%'")
					.query(Long::class.javaObjectType).single() > 0
				if (waiting) break
				Thread.sleep(10)
			}
			assertTrue(waiting, "poll must wait for the person row before creating a device")
			assertFalse(polling.isDone)
			release.countDown()
			changing.get(5, TimeUnit.SECONDS)
			assertEquals("access_denied", polling.get(5, TimeUnit.SECONDS).error)
			assertTrue(devices.list(person).isEmpty())
		} finally {
			release.countDown()
			changing.get(5, TimeUnit.SECONDS)
		}
	}

	@Test fun `inactive people cannot approve register or rekey and a pending approval stays denied after reactivation`() {
		val person = org.addPerson("usr_owner", PersonIn("inactive device fixture", listOf("requester")))
		val issued = devices.register(RegisterDevice("before-inactive"), person)
		val approved = devices.startLogin(DeviceLoginStart("approved-not-received"))
		devices.approve(approved.userCode, person)
		val pending = devices.startLogin(DeviceLoginStart("unapproved"))
		org.updatePerson("usr_owner", person, mapOf("status" to "inactive"))
		assertFailsWith<UnauthorizedException> { devices.approve(pending.userCode, person) }
		assertFailsWith<UnauthorizedException> { devices.register(RegisterDevice("late"), person) }
		assertFailsWith<UnauthorizedException> { devices.rekey(issued.deviceId, person) }
		assertEquals("access_denied", devices.poll(approved.deviceCode).error)
		org.updatePerson("usr_owner", person, mapOf("status" to "active"))
		assertEquals("access_denied", devices.poll(approved.deviceCode).error)
		assertFailsWith<UnauthorizedException> { devices.authenticate(issued.deviceToken!!) }
	}
}
