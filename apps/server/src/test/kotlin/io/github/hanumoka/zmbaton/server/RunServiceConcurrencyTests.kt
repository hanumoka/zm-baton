package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Row-lock behaviour behind contract v1 claims and late reports, with two real transactions. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RunServiceConcurrencyTests @Autowired constructor(
	private val service: RunService,
	private val jdbc: JdbcClient,
	private val tx: TransactionTemplate,
) {

	@Test
	fun `a claim skips a run row locked by another transaction instead of waiting`() {
		val work = service.createWorkItem("locked", "usr_owner")
		val run = service.createRun(work.id, "claude_code")
		val locked = CountDownLatch(1)
		val release = CountDownLatch(1)
		val pool = Executors.newSingleThreadExecutor()
		try {
			val holder = pool.submit {
				tx.executeWithoutResult {
					jdbc.sql("select id from runs where id = :id for update").param("id", run.id).query().listOfRows()
					locked.countDown()
					release.await(10, TimeUnit.SECONDS)
				}
			}
			assertTrue(locked.await(10, TimeUnit.SECONDS))
			val started = System.nanoTime()
			assertFailsWith<ConflictException> { service.claim(run.id, "dev_1") }
			val waitedMillis = (System.nanoTime() - started) / 1_000_000
			release.countDown()
			holder.get(10, TimeUnit.SECONDS)
			assertTrue(waitedMillis < 2_000, "claim waited $waitedMillis ms for the lock")
			service.claim(run.id, "dev_1") // claimable once the other transaction ends
		} finally {
			release.countDown()
			pool.shutdownNow()
		}
	}

	@Test
	fun `a report waits for a concurrent generation bump and is then judged late`() {
		val work = service.createWorkItem("race", "usr_owner")
		val run = service.createRun(work.id, "claude_code")
		val generation = service.claim(run.id, "dev_1").generation
		val locked = CountDownLatch(1)
		val pool = Executors.newSingleThreadExecutor()
		try {
			// Another transaction holds the work item and bumps its generation, like a claim in flight.
			val bump = pool.submit {
				tx.executeWithoutResult {
					jdbc.sql("select id from work_items where id = :id for update").param("id", work.id).query().listOfRows()
					locked.countDown()
					Thread.sleep(700)
					jdbc.sql("update work_items set generation = generation + 1 where id = :id").param("id", work.id).update()
				}
			}
			assertTrue(locked.await(10, TimeUnit.SECONDS))
			val result = service.ingest(
				run.id,
				EventBatch(1, listOf(EventIn(eventId = "evt_race", generation = generation, seq = 1, kind = "answer", payload = mapOf("text" to "x")))),
			)
			bump.get(10, TimeUnit.SECONDS)
			assertEquals(EventResult(stored = 1, duplicates = 0, late = 1, rejected = 0), result)
		} finally {
			pool.shutdownNow()
		}
	}
}
