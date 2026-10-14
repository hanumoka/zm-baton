package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.OffsetDateTime

/**
 * Who approves what (core module 1 "업무 판정: 상태 전이·권한·승인"). This is the one place that
 * names an approver. Stage 1 is a one-person organisation (FN-0104) with no roles or permission
 * checks yet (FN-0101, FN-0103), so the owner approves everything, their own proposals included
 * (G37.3: in a one-person organisation the same person may propose and approve; the approval is
 * still recorded as a person's). When roles arrive, only [approverFor] and [POLICY] change.
 */
object ApprovalPolicy {

	/** Recorded with every approval, so a later policy can tell which rule chose the approver. */
	const val POLICY = "stage1.owner_approves"

	/** The person who may approve or reject a target of [targetKind] in [orgId]. */
	@Suppress("UNUSED_PARAMETER")
	fun approverFor(orgId: String, targetKind: String): String = SessionService.OWNER

	/** How a person is shown next to an approval until people have names on the server. */
	fun label(personId: String): String = if (personId == SessionService.OWNER) "소유자($personId)" else personId
}

/**
 * The person a person action acts for. Until person authentication exists there is one person and
 * the server listens on loopback only (SessionService), so it is the owner; a session token is
 * refused before this is asked (PersonService.refuseSessionToken). Authentication replaces this.
 */
object Requester {
	fun person(): String = SessionService.OWNER
}

data class ApprovalView(
	val id: String,
	/** `approved`, `rejected` or `revoked` (data model "approvals"). */
	val decision: String,
	@JsonProperty("approver_kind") val approverKind: String,
	@JsonProperty("approver_id") val approverId: String,
	@JsonProperty("target_version") val targetVersion: Long,
	@JsonProperty("policy_ref") val policyRef: String,
	val note: String?,
	@JsonProperty("decided_at") val decidedAt: OffsetDateTime,
)

/** Approval rows. The caller checks the rules and writes the target in the same transaction. */
@Service
class ApprovalService(private val jdbc: JdbcClient) {

	fun record(targetKind: String, targetId: String, targetVersion: Long, approverId: String, decision: String, note: String?): String {
		val id = Ids.approval()
		jdbc.sql(
			"""
			insert into approvals (id, target_kind, target_id, target_version, policy_ref, approver_kind, approver_id, decision, note)
			values (:id, :kind, :target, :version, :policy, 'human', :approver, :decision, :note)
			""".trimIndent(),
		).param("id", id).param("kind", targetKind).param("target", targetId).param("version", targetVersion)
			.param("policy", ApprovalPolicy.POLICY).param("approver", approverId).param("decision", decision).param("note", note)
			.update()
		return id
	}

	/** Oldest first: the order they were given in. */
	fun list(targetKind: String, targetId: String): List<ApprovalView> =
		jdbc.sql(
			"""
			select id, decision, approver_kind, approver_id, target_version, policy_ref, note, decided_at
			  from approvals where target_kind = :kind and target_id = :target order by decided_at, id
			""".trimIndent(),
		).param("kind", targetKind).param("target", targetId).query { rs, _ ->
			ApprovalView(
				rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
				rs.getString(7), rs.getObject(8, OffsetDateTime::class.java),
			)
		}.list()
}
