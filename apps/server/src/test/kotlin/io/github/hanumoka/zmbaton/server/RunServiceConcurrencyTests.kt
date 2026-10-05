package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
		val pool = Executors.newFixedThreadPool(2)
		try {
			val holder = pool.submit {
				tx.executeWithoutResult {
					jdbc.sql("select id from runs where id = :id for update").param("id", run.id).query().listOfRows()
					locked.countDown()
					check(release.await(30, TimeUnit.SECONDS)) { "the lock holder was never released" }
				}
			}
			assertTrue(locked.await(10, TimeUnit.SECONDS))
			// The holder keeps its lock until the claim has returned, so a claim that waited
			// for the lock would never return and the timeout below would fail the test.
			val claim = pool.submit<Claimed> { service.claim(run.id, "dev_1") }
			val error = assertFailsWith<ExecutionException> { claim.get(10, TimeUnit.SECONDS) }
			assertIs<ConflictException>(error.cause)
			release.countDown()
			holder.get(10, TimeUnit.SECONDS)
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
		val release = CountDownLatch(1)
		val pool = Executors.newFixedThreadPool(2)
		try {
			// Another transaction holds the work item and bumps its generation, like a claim in flight.
			val bump = pool.submit {
				tx.executeWithoutResult {
					jdbc.sql("select id from work_items where id = :id for update").param("id", work.id).query().listOfRows()
					locked.countDown()
					check(release.await(30, TimeUnit.SECONDS)) { "the generation bump was never released" }
					jdbc.sql("update work_items set generation = generation + 1 where id = :id").param("id", work.id).update()
				}
			}
			assertTrue(locked.await(10, TimeUnit.SECONDS))
			val report = pool.submit<EventResult> {
				service.ingest(
					run.id,
					EventBatch(1, listOf(EventIn(eventId = "evt_race", generation = generation, seq = 1, kind = "answer", payload = mapOf("text" to "x")))),
				)
			}
			// Only bump once the report is seen blocked on a row lock, so the order is not left to timing.
			awaitReportBlockedOnLock()
			release.countDown()
			bump.get(10, TimeUnit.SECONDS)
			assertEquals(EventResult(stored = 1, duplicates = 0, late = 1, rejected = 0), report.get(10, TimeUnit.SECONDS))
		} finally {
			release.countDown()
			pool.shutdownNow()
		}
	}

	@Test
	fun `an old run's report and the new run's claim never deadlock`() {
		val pool = Executors.newFixedThreadPool(2)
		try {
			repeat(20) { round ->
				val work = service.createWorkItem("handover $round", "usr_owner")
				val runA = service.createRun(work.id, "claude_code").id
				val genA = service.claim(runA, "dev_1").generation
				service.ingest(runA, EventBatch(1, listOf(EventIn("evt_end_$round", genA, 1, "lifecycle", mapOf("phase" to "ended", "end_reason" to "replaced")))))
				val runB = service.createRun(work.id, "codex").id

				val start = CountDownLatch(1)
				val report = pool.submit<EventResult> {
					start.await()
					service.ingest(runA, EventBatch(1, listOf(EventIn("evt_old_$round", genA, 2, "answer", mapOf("text" to "late")))))
				}
				val claim = pool.submit<Claimed> {
					start.await()
					service.claim(runB, "dev_2")
				}
				start.countDown()
				// A deadlock would surface as an exception from PostgreSQL's deadlock detector.
				val result = report.get(10, TimeUnit.SECONDS)
				assertEquals(genA + 1, claim.get(10, TimeUnit.SECONDS).generation)
				assertEquals(1, result.stored)
				// Whichever took the work item first decides: before the claim the report is current, after it late.
				assertTrue(result.late in 0..1, "round $round: $result")
			}
		} finally {
			pool.shutdownNow()
		}
	}

	private fun awaitReportBlockedOnLock() {
		val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
		while (System.nanoTime() < deadline) {
			val waiting = jdbc.sql(
				"""
				select count(*) from pg_stat_activity
				 where wait_event_type = 'Lock' and query like '%join work_items w on w.id = r.work_item_id%' and pid <> pg_backend_pid()
				""".trimIndent(),
			).query(Long::class.javaObjectType).single()
			if (waiting > 0) return
			Thread.sleep(20)
		}
		throw AssertionError("the report never waited on the locked work item")
	}
}
