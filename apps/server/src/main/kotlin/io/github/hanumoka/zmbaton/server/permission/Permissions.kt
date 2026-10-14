package io.github.hanumoka.zmbaton.server.permission

import io.github.hanumoka.zmbaton.server.ApprovalPolicy
import io.github.hanumoka.zmbaton.server.BadRequestException
import io.github.hanumoka.zmbaton.server.ForbiddenException
import io.github.hanumoka.zmbaton.server.NotFoundException
import io.github.hanumoka.zmbaton.server.SessionService
import io.github.hanumoka.zmbaton.server.auth.Requester
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service

/**
 * The judge every person request goes through (FN-0103): [person] says who asks (Requester), and
 * [require] and [see] judge an act or a read with [PermissionRules]. A refusal of an act is audited
 * (`permission.refused`); a record the person may not see answers as if it did not exist.
 */
@Service
class Permissions(val facts: PermissionFacts, private val requester: Requester, private val sessions: SessionService) {

	/**
	 * The person a request acts for, as an [Actor]; checked against [act] when given (an act about
	 * no record, such as creating one or changing a setting). Refuses a session token first.
	 */
	fun person(authorization: String?, action: String, act: Act? = null): Actor {
		val actor = facts.person(requester.person(authorization, action))
		if (act != null) require(actor, act)
		return actor
	}

	/** Refuses [act] unless [actor] may do it, on [target] when given. */
	fun require(actor: Actor, act: Act, target: Target? = null): Actor {
		val verdict = PermissionRules.check(actor, act, target)
		if (verdict.allowed) return actor
		if (verdict.hidden) throw NotFoundException(verdict.reason ?: "not found")
		// Not transactional: the audit row of a refusal must survive the refusal.
		sessions.audit(
			if (actor.kind == ActorKind.SESSION) "session" else if (actor.kind == ActorKind.PERSON) "person" else "system",
			actor.id, "permission.refused", target?.kind ?: "api", target?.id ?: act.wire, mapOf("act" to act.wire, "reason" to verdict.reason),
		)
		throw ForbiddenException(verdict.reason ?: "not allowed")
	}

	/** The record when [actor] may see it; a missing or hidden one is "not found". */
	fun see(actor: Actor, target: Target): Target {
		require(actor, Act.READ, target)
		return target
	}

	fun filter(actor: Actor, columns: ScopeColumns): SqlFilter = PermissionRules.filter(actor, columns)

	// Targets that must exist: the same "not found" as the services that read them.

	fun workItem(id: String): Target = facts.workItem(id) ?: throw NotFoundException("no work item $id")

	fun decision(id: String): Target = facts.decision(id) ?: throw NotFoundException("no decision $id")

	fun document(id: String): Target = facts.document(id) ?: throw NotFoundException("no document $id")

	fun question(id: String): Target = facts.question(id) ?: throw NotFoundException("no question $id")

	fun review(id: String): Target = facts.review(id) ?: throw NotFoundException("no review $id")

	/** A run is judged by its work item. */
	fun run(id: String): Target = facts.run(id) ?: throw NotFoundException("no run $id")

	fun inboxItem(id: String): Target = facts.inboxItem(id) ?: throw NotFoundException("no first-screen item $id")

	fun notice(id: String): Target = facts.notice(id) ?: throw NotFoundException("no notice $id")

	fun account(id: String): Target = facts.account(id) ?: throw NotFoundException("no AI account $id")

	fun device(id: String): Target =
		facts.device(id.trim()) ?: throw NotFoundException("no device $id; register it with zm-baton login")

	fun note(id: String): Target = facts.note(id) ?: throw NotFoundException("no note $id")
}

/**
 * The facts [PermissionRules] needs, read from the database: who an actor is in an organisation and
 * what a record's scope, owner, people and projects are. Reads only; depends on nothing but the database,
 * so services that build what a person sees (the status card) can use it too.
 */
@Component
class PermissionFacts(private val jdbc: JdbcClient) {

	/** A person as an actor: their organisation, whether they are active, roles, teams, projects and manager. */
	fun person(personId: String): Actor = actor(ActorKind.PERSON, personId, personId)

	/** A registered session acts for the person it belongs to; null for an unknown session. */
	fun session(sessionId: String): Actor? =
		jdbc.sql("select user_id from agent_sessions where id = :id").param("id", sessionId).query(String::class.java).optional()
			.orElse(null)?.let { actor(ActorKind.SESSION, sessionId, it) }

	/** Any actor for [personId], e.g. a run (kind RUN) or the person's manager (kind MANAGER). */
	fun actor(kind: ActorKind, id: String, personId: String): Actor {
		data class Membership(val org: String, val active: Boolean, val roles: Set<Role>)
		val m = jdbc.sql(
			"""
			select m.org_id, u.status = 'active', m.roles from memberships m join users u on u.id = m.user_id
			 where m.user_id = :p order by m.created_at, m.org_id limit 1
			""".trimIndent(),
		).param("p", personId).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			Membership(rs.getString(1), rs.getBoolean(2), (rs.getArray(3).array as Array<String>).mapNotNull { Role.parse(it) }.toSet())
		}.optional().orElse(null) ?: return Actor(kind, id, personId, null, null, false, emptySet())
		val teams = jdbc.sql(
			"""
			select tm.team_id from team_members tm join teams t on t.id = tm.team_id
			 where tm.user_id = :p and t.org_id = :org and t.archived_at is null
			""".trimIndent(),
		).param("p", personId).param("org", m.org).query(String::class.java).list().filterNotNull().toSet()
		val projects = jdbc.sql(
			"select pm.project_id from project_members pm join projects p on p.id = pm.project_id where pm.user_id = :p and p.org_id = :org",
		).param("p", personId).param("org", m.org).query(String::class.java).list().filterNotNull().toSet()
		return Actor(kind, id, personId, managerOf(personId, m.org), m.org, m.active, m.roles, teams, projects)
	}

	/** The organisation a person works in. Stage 1: one per person. */
	fun orgOf(personId: String): String =
		jdbc.sql("select org_id from memberships where user_id = :p order by created_at, org_id limit 1").param("p", personId)
			.query(String::class.java).optional().orElseThrow { BadRequestException("$personId is not a member of any organisation") }

	fun ownerOf(orgId: String): String =
		jdbc.sql("select owner_user_id from organizations where id = :org").param("org", orgId).query(String::class.java).optional()
			.orElse(null) ?: throw NotFoundException("organisation $orgId has no owner")

	/** Who approves a target of [targetKind] in [orgId] ([ApprovalPolicy], with the organisation's owner). */
	fun approverFor(orgId: String, targetKind: String): String = ApprovalPolicy.approverFor(orgId, targetKind, ownerOf(orgId))

	/** A person's manager record (`mgr_`), or null when they have none in [orgId]. */
	fun managerOf(personId: String, orgId: String): String? =
		jdbc.sql("select id from managers where org_id = :org and user_id = :p").param("org", orgId).param("p", personId)
			.query(String::class.java).optional().orElse(null)

	/** Whether [personId] is an active member of [orgId]. */
	fun isActiveMember(orgId: String, personId: String): Boolean =
		jdbc.sql(
			"select count(*) from memberships m join users u on u.id = m.user_id where m.org_id = :org and m.user_id = :p and u.status = 'active'",
		).param("org", orgId).param("p", personId).query(Long::class.javaObjectType).single() > 0

	// ---------------------------------------------------------------- targets

	fun workItem(id: String): Target? =
		jdbc.sql("select org_id, visibility, responsible_user_id, executor_id, project_id from work_items where id = :id")
			.param("id", id).query { rs, _ ->
				val projects = setOfNotNull(rs.getString(5))
				Target(
					"wrk", id, rs.getString(1), Visibility.stored(rs.getString(2)), rs.getString(3), setOfNotNull(rs.getString(4)),
					projects, teamsOf(projects), "no work item $id",
				)
			}.optional().orElse(null)

	fun decision(id: String): Target? =
		jdbc.sql(
			"""
			select org_id, visibility, owner_user_id, roles ->> 'driver', roles ->> 'approver',
			       coalesce((select array_agg(c) from jsonb_array_elements_text(coalesce(roles -> 'contributors', '[]'::jsonb)) c), '{}'),
			       coalesce((select array_agg(c) from jsonb_array_elements_text(coalesce(roles -> 'informed', '[]'::jsonb)) c), '{}'),
			       coalesce((select array_agg(project_id) from decision_projects where decision_id = d.id), '{}')
			  from decisions d where id = :id
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			fun strings(i: Int) = (rs.getArray(i).array as Array<String>).toSet()
			val projects = strings(8)
			Target(
				"dcs", id, rs.getString(1), Visibility.stored(rs.getString(2)), rs.getString(3),
				setOfNotNull(rs.getString(4), rs.getString(5)) + strings(6) + strings(7), projects, teamsOf(projects), "no decision $id",
			)
		}.optional().orElse(null)

	fun document(id: String): Target? =
		jdbc.sql("select org_id, visibility, owner_user_id, project_id from documents where id = :id").param("id", id).query { rs, _ ->
			val projects = setOfNotNull(rs.getString(4))
			Target("doc", id, rs.getString(1), Visibility.stored(rs.getString(2)), rs.getString(3), emptySet(), projects, teamsOf(projects), "no document $id")
		}.optional().orElse(null)

	/**
	 * A question: its own scope, owned by the person it was routed to; the asker and the work item's
	 * people are involved too.
	 */
	fun question(id: String): Target? {
		data class Row(val workItemId: String, val visibility: String, val routed: String?, val asker: String?)
		val row = jdbc.sql(
			"""
			select work_item_id, visibility, case when routed_to_kind = 'person' then routed_to_id end,
			       case when asked_by_kind = 'person' then asked_by_id end
			  from questions where id = :id
			""".trimIndent(),
		).param("id", id).query { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }
			.optional().orElse(null) ?: return null
		val work = workItem(row.workItemId) ?: return null
		return work.copy(
			kind = "qst", id = id, visibility = Visibility.stored(row.visibility), owner = row.routed ?: work.owner,
			people = work.people + setOfNotNull(work.owner, row.asker), missing = "no question $id",
		)
	}

	/** A review request: the work item's scope when it has one; the recipient owns it. */
	fun review(id: String): Target? {
		data class Row(val org: String, val workItemId: String?, val recipient: String, val project: String?)
		val row = jdbc.sql("select org_id, work_item_id, recipient_user_id, project_id from review_items where id = :id").param("id", id)
			.query { rs, _ -> Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.optional().orElse(null)
			?: return null
		val work = row.workItemId?.let { workItem(it) }
		val projects = setOfNotNull(row.project) + work?.projects.orEmpty()
		return Target(
			"rvw", id, row.org, work?.visibility ?: Visibility.ORG, row.recipient, work?.let { it.people + setOfNotNull(it.owner) }.orEmpty(),
			projects, teamsOf(projects), "no review $id",
		)
	}

	fun run(id: String): Target? =
		jdbc.sql("select work_item_id from runs where id = :id").param("id", id).query(String::class.java).optional().orElse(null)
			?.let { workItem(it) }?.copy(kind = "run", id = id, missing = "no run $id")

	/** A first-screen item is its person's own. */
	fun inboxItem(id: String): Target? =
		jdbc.sql("select user_id from inbox_items where id = :id").param("id", id).query(String::class.java).optional().orElse(null)
			?.let { user -> Target("inb", id, orgOfOrNull(user) ?: "", Visibility.PERSONAL, user, missing = "no first-screen item $id") }

	/** A notice concerns its recipients, in their organisation. */
	fun notice(id: String): Target? =
		jdbc.sql("select recipients from notices where id = :id").param("id", id).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			(rs.getArray(1).array as Array<String>).toList()
		}.optional().orElse(null)?.let { recipients ->
			Target("ntc", id, recipients.firstNotNullOfOrNull { orgOfOrNull(it) } ?: "", people = recipients.toSet(), missing = "no notice $id")
		}

	fun account(id: String): Target? =
		jdbc.sql("select org_id, owner_user_id from ai_accounts where id = :id").param("id", id)
			.query { rs, _ -> Target("acc", id, rs.getString(1), owner = rs.getString(2), missing = "no AI account $id") }.optional().orElse(null)

	/** A live device belongs to its person. */
	fun device(id: String): Target? =
		jdbc.sql("select org_id, user_id from devices where id = :id and revoked_at is null").param("id", id)
			.query { rs, _ -> Target("dev", id, rs.getString(1), owner = rs.getString(2), missing = "no device $id; register it with zm-baton login") }
			.optional().orElse(null)

	/** A manager note is personal: its writer's only. */
	fun note(id: String): Target? =
		jdbc.sql("select m.org_id, m.user_id from manager_notes n join managers m on m.id = n.manager_id where n.id = :id").param("id", id)
			.query { rs, _ -> Target("mnt", id, rs.getString(1), Visibility.PERSONAL, rs.getString(2), missing = "no note $id") }
			.optional().orElse(null)

	private fun orgOfOrNull(personId: String): String? =
		jdbc.sql("select org_id from memberships where user_id = :p order by created_at, org_id limit 1").param("p", personId)
			.query(String::class.java).optional().orElse(null)

	/** The teams that take on any of [projects]. */
	private fun teamsOf(projects: Set<String>): Set<String> =
		if (projects.isEmpty()) emptySet()
		else jdbc.sql(
			"""
			select l.from_id from org_links l join teams t on t.id = l.from_id
			 where l.from_kind = 'team' and l.to_kind = 'project' and l.to_id in (:projects) and t.archived_at is null
			""".trimIndent(),
		).param("projects", projects.toList()).query(String::class.java).list().filterNotNull().toSet()
}
