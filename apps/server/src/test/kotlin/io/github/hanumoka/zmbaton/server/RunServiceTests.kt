package io.github.hanumoka.zmbaton.server

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pass criteria 1-3 of docs/stage1/compat-test.md, against a real PostgreSQL 17 container. */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
class RunServiceTests @Autowired constructor(
	private val service: RunService,
	private val jdbc: JdbcClient,
) {

	private fun newRun(): Pair<String, String> {
		val work = service.createWorkItem("compat", "usr_owner")
		val run = service.createRun(work.id, "claude_code")
		return work.id to run.id
	}

	private fun event(gen: Long, seq: Long, kind: String = "answer", payload: Map<String, Any?> = mapOf("text" to "hi")) =
		EventIn(eventId = "evt_" + UUID.randomUUID(), generation = gen, seq = seq, kind = kind, payload = payload)

	private fun workRow(workId: String): Map<String, Any?> =
		jdbc.sql("select stage, wait_reasons::text as wait_reasons, generation, version, updated_at from work_items where id = :id")
			.param("id", workId).query().singleRow()

	private fun runRow(runId: String): Map<String, Any?> =
		jdbc.sql("select state, end_reason, generation, late_reports from runs where id = :id")
			.param("id", runId).query().singleRow()

	@Test
	fun `concurrent claims on one run have exactly one winner`() {
		val pool = Executors.newFixedThreadPool(8)
		try {
			repeat(20) {
				val (workId, runId) = newRun()
				val start = CountDownLatch(1)
				val wins = AtomicInteger()
				val conflicts = AtomicInteger()
				val futures = (1..8).map { device ->
					pool.submit {
						start.await()
						try {
							service.claim(runId, "dev_$device")
							wins.incrementAndGet()
						} catch (e: ConflictException) {
							conflicts.incrementAndGet()
						}
					}
				}
				start.countDown()
				futures.forEach { it.get(30, TimeUnit.SECONDS) }
				assertEquals(1, wins.get())
				assertEquals(7, conflicts.get())
				val workGeneration = jdbc.sql("select generation from work_items where id = :id")
					.param("id", workId).query(Long::class.javaObjectType).single()
				assertEquals(1L, workGeneration)
				assertEquals(1L, runRow(runId)["generation"])
			}
		} finally {
			pool.shutdownNow()
		}
	}

	@Test
	fun `a work item has at most one live run`() {
		val (workId, runId) = newRun()
		assertFailsWith<ConflictException> { service.createRun(workId, "claude_code") }

		val claimed = service.claim(runId, "dev_1")
		service.ingest(runId, EventBatch(1, listOf(event(claimed.generation, 1, "lifecycle", mapOf("phase" to "ended", "end_reason" to "replaced")))))
		assertEquals("ended", runRow(runId)["state"])
		service.createRun(workId, "claude_code") // allowed once the first run ended
	}

	@Test
	fun `an older generation report is stored but never changes state`() {
		val (workId, runA) = newRun()
		val genA = service.claim(runA, "dev_1").generation
		service.ingest(runA, EventBatch(1, listOf(event(genA, 1, "lifecycle", mapOf("phase" to "started")))))
		service.ingest(runA, EventBatch(1, listOf(event(genA, 2, "lifecycle", mapOf("phase" to "ended", "end_reason" to "replaced")))))

		val runB = service.createRun(workId, "codex").id
		val genB = service.claim(runB, "dev_2").generation
		assertEquals(genA + 1, genB)

		val workBefore = workRow(workId)
		val runBBefore = runRow(runB)

		// The old executor reports late, and even claims it ended successfully.
		val lateEvent = event(genA, 3, "lifecycle", mapOf("phase" to "ended", "end_reason" to "submitted"))
		val late = service.ingest(runA, EventBatch(1, listOf(lateEvent)))
		assertEquals(EventResult(stored = 1, duplicates = 0, late = 1, rejected = 0), late)
		val a = runRow(runA)
		assertEquals("replaced", a["end_reason"])
		assertEquals(true, a["late_reports"])
		assertEquals(runBBefore, runRow(runB))
		assertEquals(workBefore, workRow(workId))
		val stored = jdbc.sql("select generation, kind from run_events where event_id = :id")
			.param("id", lateEvent.eventId).query().singleRow()
		assertEquals(genA, stored["generation"])
		assertEquals("lifecycle", stored["kind"])
	}

	@Test
	fun `an ended report without a contract end reason is refused and changes nothing`() {
		val (_, runId) = newRun()
		val gen = service.claim(runId, "dev_1").generation
		service.ingest(runId, EventBatch(1, listOf(event(gen, 1, "lifecycle", mapOf("phase" to "started")))))
		val bad = listOf(
			mapOf("phase" to "ended"),
			mapOf("phase" to "ended", "end_reason" to "faild"),
			mapOf("phase" to "ended", "end_reason" to 3),
			mapOf("phase" to "ended", "end_reason" to null),
			mapOf("phase" to "paused"),
		)
		val refused = bad.mapIndexed { i, payload ->
			val ev = event(gen, 10L + i, "lifecycle", payload)
			val result = service.ingest(runId, EventBatch(1, listOf(ev)))
			assertEquals(EventResult(0, 0, 0, 1), result, "payload $payload")
			ev
		}
		assertEquals("running", runRow(runId)["state"])
		assertEquals(0L, jdbc.sql("select count(*) from run_events where run_id = :id and seq >= 10")
			.param("id", runId).query(Long::class.javaObjectType).single())

		// A refused event was not stored, so the corrected event may reuse its event_id and seq.
		val corrected = refused.first().copy(payload = mapOf("phase" to "ended", "end_reason" to "failed"))
		assertEquals(EventResult(1, 0, 0, 0), service.ingest(runId, EventBatch(1, listOf(corrected))))
		val row = runRow(runId)
		assertEquals("ended", row["state"])
		assertEquals("failed", row["end_reason"])
	}

	@Test
	fun `a report must carry the generation its own run was claimed with`() {
		val (workId, runA) = newRun()
		val genA = service.claim(runA, "dev_1").generation
		service.ingest(runA, EventBatch(1, listOf(event(genA, 1, "lifecycle", mapOf("phase" to "ended", "end_reason" to "replaced")))))
		val runB = service.createRun(workId, "codex").id

		// B is not claimed yet: even the work item's current generation is not B's to use.
		assertEquals(EventResult(0, 0, 0, 1), service.ingest(runB, EventBatch(1, listOf(event(genA, 1)))))
		assertEquals("requested", runRow(runB)["state"])

		val genB = service.claim(runB, "dev_2").generation
		assertEquals(EventResult(0, 0, 0, 1), service.ingest(runB, EventBatch(1, listOf(event(genA, 1)))))
		assertEquals(EventResult(1, 0, 0, 0), service.ingest(runB, EventBatch(1, listOf(event(genB, 1)))))
	}

	@Test
	fun `a lost run leaves its work item outcome_unknown once`() {
		val (workId, runId) = newRun()
		val gen = service.claim(runId, "dev_1").generation
		val before = workRow(workId)
		val lost = mapOf("phase" to "ended", "end_reason" to "lost")
		service.ingest(runId, EventBatch(1, listOf(event(gen, 1, "lifecycle", lost))))
		val row = runRow(runId)
		assertEquals("ended", row["state"])
		assertEquals("lost", row["end_reason"])
		val after = workRow(workId)
		assertEquals("{outcome_unknown}", after["wait_reasons"])
		assertEquals((before["version"] as Long) + 1, after["version"])

		// A second ended report for the same run changes nothing.
		service.ingest(runId, EventBatch(1, listOf(event(gen, 2, "lifecycle", lost))))
		assertEquals(after, workRow(workId))
	}

	@Test
	fun `a resent event id is stored once and still succeeds`() {
		val (_, runId) = newRun()
		val gen = service.claim(runId, "dev_1").generation
		val batch = EventBatch(1, listOf(event(gen, 1), event(gen, 2, "usage", mapOf("tokens" to 10))))
		assertEquals(EventResult(2, 0, 0, 0), service.ingest(runId, batch))
		assertEquals(EventResult(0, 2, 0, 0), service.ingest(runId, batch))
		val rows = jdbc.sql("select count(*) from run_events where run_id = :id")
			.param("id", runId).query(Long::class.javaObjectType).single()
		assertEquals(2L, rows)
	}

	@Test
	fun `unknown kinds, future generations and unknown protocol versions are refused`() {
		val (_, runId) = newRun()
		val gen = service.claim(runId, "dev_1").generation
		val result = service.ingest(runId, EventBatch(1, listOf(event(gen, 1, "chatter"), event(gen + 5, 2))))
		assertEquals(EventResult(0, 0, 0, 2), result)
		assertFailsWith<BadRequestException> { service.ingest(runId, EventBatch(2, listOf(event(gen, 3)))) }
	}

	@Test
	fun `ids are prefixed uuid v7`() {
		val id = Ids.run()
		assertTrue(id.startsWith("run_"))
		val uuid = UUID.fromString(id.removePrefix("run_"))
		assertEquals(7, uuid.version())
		assertEquals(2, uuid.variant())
		assertFalse(Ids.workItem() == Ids.workItem())
	}
}
