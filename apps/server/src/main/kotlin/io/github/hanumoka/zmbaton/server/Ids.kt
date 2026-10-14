package io.github.hanumoka.zmbaton.server

import java.security.SecureRandom
import java.util.UUID

/**
 * Prefixed, time-ordered identifiers (contract v1 "식별자"): `wrk_<uuidv7>`, `run_<uuidv7>`, `dlg_<uuidv7>`.
 */
object Ids {
	private val random = SecureRandom()

	fun workItem(): String = "wrk_" + uuidV7()

	fun run(): String = "run_" + uuidV7()

	fun delegation(): String = "dlg_" + uuidV7()

	fun device(): String = "dev_" + uuidV7()

	fun session(): String = "ses_" + uuidV7()

	fun inbox(): String = "inb_" + uuidV7()

	fun notice(): String = "ntc_" + uuidV7()

	fun event(): String = "evt_" + uuidV7()

	fun project(): String = "prj_" + uuidV7()

	fun decision(): String = "dcs_" + uuidV7()

	fun review(): String = "rvw_" + uuidV7()

	fun question(): String = "qst_" + uuidV7()

	/** An instruction a person sends to a running executor. Beyond the contract's prefixes, like `aud_`. */
	fun input(): String = "inp_" + uuidV7()

	/** Approvals have no contract prefix either; `apv_` keeps them readable. */
	fun approval(): String = "apv_" + uuidV7()

	fun document(): String = "doc_" + uuidV7()

	/** Document versions have no contract prefix; `dvr_` keeps them readable. */
	fun documentVersion(): String = "dvr_" + uuidV7()

	/** An AI account reference (contract "식별자" `acc_`): never a login, only whose account it is. */
	fun account(): String = "acc_" + uuidV7()

	/** A usage signal has no contract prefix; `usg_` keeps it readable. */
	fun usageSignal(): String = "usg_" + uuidV7()

	/** A master check (MasterChecks.kt) has no contract prefix; `mck_` keeps it readable. */
	fun masterCheck(): String = "mck_" + uuidV7()

	/** Audit rows have no contract prefix; `aud_` keeps them readable next to the others. */
	fun audit(): String = "aud_" + uuidV7()

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
