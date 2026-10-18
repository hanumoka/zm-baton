package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/** Rechecks copied material immediately before a pending master run leaves the server. */
@Component
class MasterAccess(private val jdbc: JdbcClient, private val facts: PermissionFacts, private val sessions: SessionService) {
	/** Caller owns the transaction. A refused run is committed before the device receives a conflict. */
	fun refuseClaim(runId: String, deviceId: String? = null): Boolean {
		val source = jdbc.sql(
			"""select r.work_item_id, coalesce(dv.user_id, o.owner_user_id)
			from runs r join master_checks mc on mc.work_item_id = r.work_item_id
			join organizations o on o.id = mc.org_id
			left join delegations d on d.id = r.delegation_id
			left join devices dv on dv.id = d.device_id
			where r.id = :id and r.state = 'requested'
			and ((cast(:device as text) is null and r.executor = 'anthropic_api') or d.device_id = :device)
			for update of r""",
		).param("id", runId).param("device", deviceId)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null) ?: return false
		val target = facts.workItem(source.first)
		val actor = facts.person(source.second)
		if (actor.member && target != null && PermissionRules.canSee(actor, target)) return false
		jdbc.sql("update runs set state = 'ended', end_reason = 'cancelled', ended_at = now() where id = :id and state = 'requested'")
			.param("id", runId).update()
		sessions.audit("system", MasterCheckService.ACTOR, "run.refused_by_material_scope", "run", runId, emptyMap())
		return true
	}
}
