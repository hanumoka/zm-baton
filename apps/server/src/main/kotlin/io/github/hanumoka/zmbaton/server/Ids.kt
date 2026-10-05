package io.github.hanumoka.zmbaton.server

import java.security.SecureRandom
import java.util.UUID

/**
 * Prefixed, time-ordered identifiers (contract v1 "식별자"): `wrk_<uuidv7>`, `run_<uuidv7>`.
 */
object Ids {
	private val random = SecureRandom()

	fun workItem(): String = "wrk_" + uuidV7()

	fun run(): String = "run_" + uuidV7()

	/** RFC 9562 UUIDv7: 48-bit Unix milliseconds, version 7, variant 10, the rest random. */
	fun uuidV7(nowMillis: Long = System.currentTimeMillis()): UUID {
		val randomBits = ByteArray(10).also(random::nextBytes)
		var msb = (nowMillis and 0xFFFF_FFFF_FFFFL) shl 16
		msb = msb or (0x7L shl 12)
		msb = msb or (((randomBits[0].toLong() and 0x0F) shl 8) or (randomBits[1].toLong() and 0xFF))
		var lsb = 0x2L shl 62
		for (i in 2 until 10) {
			lsb = lsb or ((randomBits[i].toLong() and 0xFF) shl ((9 - i) * 8))
		}
		lsb = (lsb and 0x3FFF_FFFF_FFFF_FFFFL) or (0x2L shl 62)
		return UUID(msb, lsb)
	}
}
