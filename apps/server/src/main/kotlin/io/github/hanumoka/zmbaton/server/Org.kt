package io.github.hanumoka.zmbaton.server

import com.fasterxml.jackson.annotation.JsonProperty
import io.github.hanumoka.zmbaton.server.admin.ServerAdministration
import io.github.hanumoka.zmbaton.server.permission.Act
import io.github.hanumoka.zmbaton.server.permission.Actor
import io.github.hanumoka.zmbaton.server.permission.Target
import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.Permissions
import io.github.hanumoka.zmbaton.server.permission.Role
import io.github.hanumoka.zmbaton.server.permission.Visibility
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

// The organisation's structure (FN-0101): people with their roles, teams, products, projects, the
// organisation's repositories and environments, each an item of its own, and the links between them
// (a team takes on a project, a product is made of projects, a project works in repositories and
// runs in environments). A one-person organisation is the same structure with one person who holds
// every role (FN-0104); a team grows it by adding people (G37.7). Every change is audited.

data class LabelView(val id: String, val label: String)

data class OrgPersonView(
	val id: String,
	@JsonProperty("display_name") val displayName: String,
	val status: String,
	val roles: List<String>,
	@JsonProperty("manager_id") val managerId: String?,
	val teams: List<String>,
	val projects: List<String>,
	/** The organisation's owner: holds every role, approves (stage 1), and their roles do not change. */
	val owner: Boolean,
)

data class TeamView(val id: String, val name: String, val members: List<String>, @JsonProperty("archived_at") val archivedAt: OffsetDateTime?)

data class OrgItemView(val id: String, val name: String, @JsonProperty("archived_at") val archivedAt: OffsetDateTime?)

data class OrgProjectView(
	val id: String,
	val name: String,
	@JsonProperty("external_ref") val externalRef: String?,
	val kind: String?,
	val members: List<String>,
)

data class RepositoryView(
	val id: String,
	val name: String,
	@JsonProperty("repo_key") val repoKey: String,
	val remote: String?,
	/** Devices whose local connector may run in it now (device_repositories). */
	val devices: Int,
	@JsonProperty("archived_at") val archivedAt: OffsetDateTime?,
)

data class EnvironmentView(val id: String, val name: String, val kind: String, @JsonProperty("archived_at") val archivedAt: OffsetDateTime?)

data class OrgLinkView(
	@JsonProperty("from_kind") val fromKind: String,
	@JsonProperty("from_id") val fromId: String,
	@JsonProperty("to_kind") val toKind: String,
	@JsonProperty("to_id") val toId: String,
)

data class OrgView(
	val id: String,
	val name: String,
	@JsonProperty("owner_user_id") val ownerUserId: String,
	/** One active person: the screen stays simple until a second person joins (DESIGN.md). */
	val solo: Boolean,
	val roles: List<LabelView>,
	val visibilities: List<LabelView>,
	@JsonProperty("environment_kinds") val environmentKinds: List<String>,
	val people: List<OrgPersonView>,
	val teams: List<TeamView>,
	val products: List<OrgItemView>,
	val projects: List<OrgProjectView>,
	val repositories: List<RepositoryView>,
	val environments: List<EnvironmentView>,
	val links: List<OrgLinkView>,
	/** Whether the person asking may change the structure (role operator). */
	@JsonProperty("can_manage") val canManage: Boolean,
	@JsonProperty("can_manage_logins") val canManageLogins: Boolean = false,
)

data class PersonIn(@JsonProperty("display_name") val displayName: String, val roles: List<String> = emptyList())

data class MembersIn(@JsonProperty("user_ids") val userIds: List<String>)

data class LinkIn(@JsonProperty("from_id") val fromId: String, @JsonProperty("to_id") val toId: String)

@Service
class OrgService(private val jdbc: JdbcClient, private val facts: PermissionFacts, private val sessions: SessionService,
	private val administration: ServerAdministration) {

	companion object {
		const val NAME_MAX = 100
		val ENVIRONMENT_KINDS = listOf("development", "staging", "production", "other")

		/** Item kinds by id prefix, with their tables; the links' allowed pairs are V13's check. */
		private val KINDS = mapOf("tem_" to "team", "prd_" to "product", "prj_" to "project", "rep_" to "repository", "env_" to "environment")
		private val TABLES = mapOf(
			"team" to "teams", "product" to "products", "project" to "projects", "repository" to "repositories", "environment" to "environments",
		)
		private val LINKS = setOf("team" to "project", "product" to "project", "project" to "repository", "project" to "environment")
	}

	// ---------------------------------------------------------------- read

	fun view(orgId: String, canManage: Boolean, actor: Actor? = null): OrgView {
		val (name, owner) = jdbc.sql("select name, owner_user_id from organizations where id = :org").param("org", orgId)
			.query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElseThrow { NotFoundException("no organisation $orgId") }
		val teamMembers = pairs("select tm.team_id, tm.user_id from team_members tm join teams t on t.id = tm.team_id where t.org_id = :org", orgId)
		val projectMembers = pairs(
			"select pm.project_id, pm.user_id from project_members pm join projects p on p.id = pm.project_id where p.org_id = :org", orgId,
		)
		val people = jdbc.sql(
			"""
			select u.id, u.display_name, u.status, m.roles, g.id from memberships m join users u on u.id = m.user_id
			  left join managers g on g.org_id = m.org_id and g.user_id = u.id
			 where m.org_id = :org order by (u.id = :owner) desc, u.status, m.created_at, u.id
			""".trimIndent(),
		).param("org", orgId).param("owner", owner).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			val roles = (rs.getArray(4).array as Array<String>).toList().sortedBy { r -> Role.entries.indexOfFirst { it.wire == r } }
			val id = rs.getString(1)
			OrgPersonView(
				id, rs.getString(2), rs.getString(3), roles, rs.getString(5),
				teamMembers.filter { it.second == id }.map { it.first }, projectMembers.filter { it.second == id }.map { it.first }, id == owner,
			)
		}.list()
		val teams = jdbc.sql("select id, name, archived_at from teams where org_id = :org order by archived_at nulls first, name, id")
			.param("org", orgId).query { rs, _ ->
				TeamView(rs.getString(1), rs.getString(2), teamMembers.filter { it.first == rs.getString(1) }.map { it.second },
					rs.getObject(3, OffsetDateTime::class.java))
			}.list()
		val products = jdbc.sql("select id, name, archived_at from products where org_id = :org order by archived_at nulls first, name, id")
			.param("org", orgId).query { rs, _ -> OrgItemView(rs.getString(1), rs.getString(2), rs.getObject(3, OffsetDateTime::class.java)) }.list()
		val projects = jdbc.sql("select id, name, external_ref, kind from projects where org_id = :org order by external_ref nulls last, name, id")
			.param("org", orgId).query { rs, _ ->
				OrgProjectView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
					projectMembers.filter { it.first == rs.getString(1) }.map { it.second })
			}.list()
		val repositories = jdbc.sql(
			"""
			select r.id, r.name, r.repo_key, r.remote, r.archived_at,
			       (select count(*) from device_repositories dr join devices d on d.id = dr.device_id
			         where dr.repo_key = r.repo_key and d.org_id = r.org_id and dr.removed_at is null and d.revoked_at is null)
			  from repositories r where r.org_id = :org order by r.archived_at nulls first, r.name, r.id
			""".trimIndent(),
		).param("org", orgId).query { rs, _ ->
			RepositoryView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(6), rs.getObject(5, OffsetDateTime::class.java))
		}.list()
		val environments = jdbc.sql("select id, name, kind, archived_at from environments where org_id = :org order by archived_at nulls first, name, id")
			.param("org", orgId).query { rs, _ ->
				EnvironmentView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, OffsetDateTime::class.java))
			}.list()
		val links = jdbc.sql("select from_kind, from_id, to_kind, to_id from org_links where org_id = :org order by created_at, from_id, to_id")
			.param("org", orgId).query { rs, _ -> OrgLinkView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.list()
		return OrgView(
			orgId, name, owner, people.count { it.status == "active" } <= 1,
			Role.entries.map { LabelView(it.wire, it.label) }, Visibility.entries.map { LabelView(it.wire, it.label) }, ENVIRONMENT_KINDS,
			people, teams, products, projects, repositories, environments, links, canManage,
			actor != null && PermissionRules.check(actor, Act.LOGIN_MANAGE, Target("org", orgId, orgId, owner = owner)).allowed,
		)
	}

	private fun pairs(sql: String, orgId: String): List<Pair<String, String>> =
		jdbc.sql(sql).param("org", orgId).query { rs, _ -> rs.getString(1) to rs.getString(2) }.list()

	// ---------------------------------------------------------------- the organisation and its people

	@Transactional
	fun rename(actor: String, name: String) {
		val org = facts.orgOf(actor)
		jdbc.sql("update organizations set name = :name where id = :org").param("name", name(name)).param("org", org).update()
		sessions.audit("person", actor, "org.renamed", "org", org, mapOf("name" to name.trim()))
	}

	/** A new person in [actor]'s organisation, with their roles and their manager record (FN-0402). */
	@Transactional
	fun addPerson(actor: String, body: PersonIn): String {
		val org = facts.orgOf(actor)
		val roles = roles(body.roles)
		val id = Ids.user()
		jdbc.sql("insert into users (id, display_name) values (:id, :name)").param("id", id).param("name", name(body.displayName)).update()
		jdbc.sql("insert into memberships (org_id, user_id, roles) values (:org, :id, cast(:roles as text[]))")
			.param("org", org).param("id", id).param("roles", pgArray(roles)).update()
		jdbc.sql("insert into managers (id, org_id, user_id) values (:mgr, :org, :id)").param("mgr", Ids.managerOf(id)).param("org", org).param("id", id).update()
		sessions.audit("person", actor, "org.person_added", "usr", id, mapOf("display_name" to body.displayName.trim(), "roles" to roles))
		return id
	}

	/**
	 * Changes a person's name, roles or status (`active`, `inactive`). The organisation's owner keeps
	 * every role and stays active: what the owner could do, the owner can still do.
	 */
	@Transactional
	fun updatePerson(actor: String, id: String, patch: Map<String, Any?>) {
		if ("status" in patch) administration.guardPersonStatus(id, patch["status"] as? String)
		val org = facts.orgOf(actor)
		val unknown = patch.keys - setOf("display_name", "roles", "status")
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown")
		val member = jdbc.sql("select count(*) from memberships where org_id = :org and user_id = :id").param("org", org).param("id", id)
			.query(Long::class.javaObjectType).single() > 0
		if (!member) throw NotFoundException("no person $id in this organisation")
		val owner = facts.ownerOf(org) == id
		if (owner && ("roles" in patch || "status" in patch)) throw ConflictException("조직 소유자의 역할과 상태는 바꾸지 않는다. 소유자는 늘 모든 역할을 갖는다")
		val changed = linkedMapOf<String, Any?>()
		if ("display_name" in patch) {
			val n = name(patch["display_name"] as? String ?: throw BadRequestException("display_name must be text"))
			jdbc.sql("update users set display_name = :n where id = :id").param("n", n).param("id", id).update()
			changed["display_name"] = n
		}
		if ("roles" in patch) {
			val list = (patch["roles"] as? List<*>)?.map { it.toString() } ?: throw BadRequestException("roles must be a list")
			val roles = roles(list)
			jdbc.sql("update memberships set roles = cast(:roles as text[]) where org_id = :org and user_id = :id")
				.param("roles", pgArray(roles)).param("org", org).param("id", id).update()
			changed["roles"] = roles
		}
		if ("status" in patch) {
			val status = patch["status"] as? String
			if (status !in setOf("active", "inactive")) throw BadRequestException("status must be active or inactive")
			jdbc.sql("update users set status = :s where id = :id").param("s", status).param("id", id).update()
			if (status == "inactive") {
				jdbc.sql("update web_logins set ended_at = now() where user_id = :id and ended_at is null").param("id", id).update()
				jdbc.sql("update setup_tokens set dropped_at = now() where user_id = :id and used_at is null and dropped_at is null").param("id", id).update()
				// Reactivation never restores an old device or session credential. A person must
				// authorize a new device; an offline executor cannot be forcibly killed by the server.
				changed["revoked_devices"] = jdbc.sql("update devices set revoked_at = now() where user_id = :id and revoked_at is null")
					.param("id", id).update()
				jdbc.sql("update device_logins set denied_at = now() where approved_by = :id and consumed_at is null and denied_at is null")
					.param("id", id).update()
				changed["ended_sessions"] = sessions.endForPerson(id)
			}
			changed["status"] = status
		}
		if (changed.isEmpty()) throw BadRequestException("nothing to change")
		sessions.audit("person", actor, "org.person_changed", "usr", id, changed)
	}

	// ---------------------------------------------------------------- items

	/** A new team, product, project, repository or environment in [actor]'s organisation. */
	@Transactional
	fun createItem(actor: String, kind: String, body: Map<String, Any?>): String {
		val org = facts.orgOf(actor)
		val name = name(body["name"] as? String ?: throw BadRequestException("name is required"))
		val id: String
		try {
			id = when (kind) {
				"team" -> Ids.team().also { jdbc.sql("insert into teams (id, org_id, name) values (:id, :org, :name)").param("id", it).param("org", org).param("name", name).update() }
				"product" -> Ids.product().also { jdbc.sql("insert into products (id, org_id, name) values (:id, :org, :name)").param("id", it).param("org", org).param("name", name).update() }
				"project" -> Ids.project().also {
					jdbc.sql("insert into projects (id, org_id, name, kind) values (:id, :org, :name, :kind)")
						.param("id", it).param("org", org).param("name", name).param("kind", text(body["kind"], "kind", 50)).update()
				}
				"repository" -> {
					val key = (body["repo_key"] as? String)?.trim().orEmpty()
					if (!SessionService.isRepoKey(key)) throw BadRequestException("repo_key is not a repository key such as git:<root commit>")
					Ids.repository().also {
						jdbc.sql("insert into repositories (id, org_id, repo_key, name, remote) values (:id, :org, :key, :name, :remote)")
							.param("id", it).param("org", org).param("key", key).param("name", name).param("remote", sessions.cleanRemote(body["remote"] as? String))
							.update()
					}
				}
				"environment" -> Ids.environment().also {
					jdbc.sql("insert into environments (id, org_id, name, kind) values (:id, :org, :name, :kind)")
						.param("id", it).param("org", org).param("name", name).param("kind", environmentKind(body["kind"])).update()
				}
				else -> throw NotFoundException("no kind $kind")
			}
		} catch (e: DuplicateKeyException) {
			throw ConflictException("이 조직에 이미 같은 저장소가 있다")
		}
		sessions.audit("person", actor, "org.${kind}_created", id.take(3), id, mapOf("name" to name))
		return id
	}

	/**
	 * Changes an item's `name`, `archived` (true archives it, false brings it back) and, by kind, a
	 * project's `kind`, a repository's `remote` or an environment's `kind`. A project imported from
	 * Git keeps the name its source gives it.
	 */
	@Transactional
	fun updateItem(actor: String, id: String, patch: Map<String, Any?>) {
		val (kind, table) = item(actor, id)
		val allowed = setOf("name") + when (kind) {
			"project" -> setOf("kind")
			"repository" -> setOf("remote", "archived")
			"environment" -> setOf("kind", "archived")
			else -> setOf("archived")
		}
		val unknown = patch.keys - allowed
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown for a $kind")
		val changes = linkedMapOf<String, Any?>()
		if ("name" in patch) {
			if (kind == "project") {
				val ref = jdbc.sql("select external_ref from projects where id = :id").param("id", id).query(String::class.java).optional().orElse(null)
				if (ref != null) throw ConflictException("정본이 Git인 프로젝트($ref)다. 이름은 원본에서 고치고 다시 가져온다")
			}
			changes["name"] = name(patch["name"] as? String ?: throw BadRequestException("name must be text"))
		}
		if ("kind" in patch) changes["kind"] = if (kind == "environment") environmentKind(patch["kind"]) else text(patch["kind"], "kind", 50)
		if ("remote" in patch) changes["remote"] = sessions.cleanRemote(patch["remote"] as? String)
		if ("archived" in patch) {
			val archived = patch["archived"] as? Boolean ?: throw BadRequestException("archived must be true or false")
			changes["archived_at"] = if (archived) OffsetDateTime.now() else null
		}
		if (changes.isEmpty()) throw BadRequestException("nothing to change")
		// Table and column names come from the fixed lists above, never from input.
		val stmt = jdbc.sql("update $table set ${changes.keys.joinToString(", ") { "$it = :$it" }} where id = :id").param("id", id)
		changes.forEach { (k, v) -> stmt.param(k, v) }
		stmt.update()
		sessions.audit("person", actor, "org.${kind}_changed", id.take(3), id, changes.mapValues { it.value?.toString() })
	}

	/** The members of a team or a project: active or not, they must be people of the organisation. */
	@Transactional
	fun setMembers(actor: String, id: String, userIds: List<String>) {
		val (kind, _) = item(actor, id)
		if (kind != "team" && kind != "project") throw BadRequestException("only a team or a project has members")
		val org = facts.orgOf(actor)
		val ids = userIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
		val known = if (ids.isEmpty()) emptySet() else jdbc.sql("select user_id from memberships where org_id = :org and user_id in (:ids)")
			.param("org", org).param("ids", ids).query(String::class.java).list().toSet()
		val strangers = ids - known
		if (strangers.isNotEmpty()) throw BadRequestException("not people of this organisation: $strangers")
		val (table, column) = if (kind == "team") "team_members" to "team_id" else "project_members" to "project_id"
		jdbc.sql("delete from $table where $column = :id").param("id", id).update()
		for (user in ids) {
			jdbc.sql("insert into $table ($column, user_id) values (:id, :user)").param("id", id).param("user", user).update()
		}
		sessions.audit("person", actor, "org.${kind}_members", id.take(3), id, mapOf("members" to ids))
	}

	/** Ties two items: a team or product to a project, a project to a repository or an environment. */
	@Transactional
	fun link(actor: String, body: LinkIn) {
		val org = facts.orgOf(actor)
		val (fromKind, _) = item(actor, body.fromId.trim())
		val (toKind, _) = item(actor, body.toId.trim())
		if ((fromKind to toKind) !in LINKS) {
			throw BadRequestException("$fromKind → $toKind is not a link; links are ${LINKS.joinToString { "${it.first} → ${it.second}" }}")
		}
		jdbc.sql(
			"""
			insert into org_links (org_id, from_kind, from_id, to_kind, to_id) values (:org, :fk, :fid, :tk, :tid)
			on conflict do nothing
			""".trimIndent(),
		).param("org", org).param("fk", fromKind).param("fid", body.fromId.trim()).param("tk", toKind).param("tid", body.toId.trim()).update()
		sessions.audit("person", actor, "org.linked", body.fromId.trim().take(3), body.fromId.trim(), mapOf("to_kind" to toKind, "to_id" to body.toId.trim()))
	}

	@Transactional
	fun unlink(actor: String, fromId: String, toId: String) {
		val org = facts.orgOf(actor)
		val removed = jdbc.sql("delete from org_links where org_id = :org and from_id = :fid and to_id = :tid")
			.param("org", org).param("fid", fromId.trim()).param("tid", toId.trim()).update()
		if (removed == 0) throw NotFoundException("no link $fromId → $toId")
		sessions.audit("person", actor, "org.unlinked", fromId.trim().take(3), fromId.trim(), mapOf("to_id" to toId.trim()))
	}

	/**
	 * The organisation's repository for a repository key a device added (DelegationService), when it has
	 * none yet: a device's repository is always one of its organisation's.
	 */
	fun ensureRepository(orgId: String, repoKey: String, name: String, remote: String?) {
		jdbc.sql(
			"""
			insert into repositories (id, org_id, repo_key, name, remote) values (:id, :org, :key, :name, :remote)
			on conflict (org_id, repo_key) do nothing
			""".trimIndent(),
		).param("id", Ids.repository()).param("org", orgId).param("key", repoKey).param("name", name).param("remote", remote).update()
	}

	// ---------------------------------------------------------------- helpers

	/** The kind and table of an item of [actor]'s organisation, from its id's prefix. */
	private fun item(actor: String, id: String): Pair<String, String> {
		val kind = KINDS[id.take(4)] ?: throw NotFoundException("no team, product, project, repository or environment $id")
		val table = TABLES.getValue(kind)
		val found = jdbc.sql("select count(*) from $table where id = :id and org_id = :org").param("id", id).param("org", facts.orgOf(actor))
			.query(Long::class.javaObjectType).single() > 0
		if (!found) throw NotFoundException("no $kind $id")
		return kind to table
	}

	private fun name(raw: String): String {
		val t = raw.trim()
		if (t.isEmpty() || t.length > NAME_MAX) throw BadRequestException("name must be 1-$NAME_MAX characters")
		return t
	}

	private fun text(raw: Any?, field: String, max: Int): String? {
		if (raw != null && raw !is String) throw BadRequestException("$field must be text")
		val t = (raw as String?)?.trim()?.ifEmpty { null } ?: return null
		if (t.length > max) throw BadRequestException("$field must be at most $max characters")
		return t
	}

	private fun environmentKind(raw: Any?): String {
		val k = (raw as? String)?.trim()?.ifEmpty { null } ?: return "other"
		if (k !in ENVIRONMENT_KINDS) throw BadRequestException("kind must be one of $ENVIRONMENT_KINDS")
		return k
	}

	private fun roles(raw: List<String>): List<String> {
		val roles = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
		val unknown = roles.filter { Role.parse(it) == null }
		if (unknown.isNotEmpty()) throw BadRequestException("unknown roles $unknown; roles are ${Role.entries.map { it.wire }}")
		return roles.sortedBy { r -> Role.entries.indexOfFirst { it.wire == r } }
	}

	/** A text[] literal for `cast(:x as text[])`; roles are fixed words, so no quoting is needed. */
	private fun pgArray(values: List<String>): String = values.joinToString(",", "{", "}")
}

@RestController
@RequestMapping("/api/org")
class OrgController(private val org: OrgService, private val permissions: Permissions) {

	/** The whole structure of my organisation: small in stage 1, so one read. */
	@GetMapping
	fun view(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?): OrgView {
		val me = permissions.person(authorization, "read the organisation", Act.READ)
		return org.view(me.orgId!!, PermissionRules.check(me, Act.ORG_MANAGE).allowed, me)
	}

	@PatchMapping
	fun rename(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: Map<String, Any?>,
	): OrgView {
		val me = permissions.person(authorization, "rename the organisation", Act.ORG_MANAGE)
		org.rename(me.person!!, body["name"] as? String ?: throw BadRequestException("name is required"))
		return org.view(me.orgId!!, true, me)
	}

	@PostMapping("/people")
	@ResponseStatus(HttpStatus.CREATED)
	fun addPerson(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?, @RequestBody body: PersonIn): OrgView {
		val me = permissions.person(authorization, "add a person", Act.ORG_MANAGE)
		org.addPerson(me.person!!, body)
		return org.view(me.orgId!!, true, me)
	}

	@PatchMapping("/people/{id}")
	fun updatePerson(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: Map<String, Any?>,
	): OrgView {
		val me = permissions.person(authorization, "change a person", Act.ORG_MANAGE)
		org.updatePerson(me.person!!, id, body)
		return org.view(me.orgId!!, true, me)
	}

	/** `kind`: teams, products, projects, repositories or environments. */
	@PostMapping("/{kind}")
	@ResponseStatus(HttpStatus.CREATED)
	fun create(
		@PathVariable kind: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: Map<String, Any?>,
	): OrgView {
		val me = permissions.person(authorization, "add to the organisation", Act.ORG_MANAGE)
		org.createItem(me.person!!, singular(kind), body)
		return org.view(me.orgId!!, true, me)
	}

	@PatchMapping("/{kind}/{id}")
	fun update(
		@PathVariable kind: String,
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: Map<String, Any?>,
	): OrgView {
		val me = permissions.person(authorization, "change the organisation", Act.ORG_MANAGE)
		singular(kind)
		org.updateItem(me.person!!, id, body)
		return org.view(me.orgId!!, true, me)
	}

	@PutMapping("/{kind}/{id}/members")
	fun members(
		@PathVariable kind: String,
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: MembersIn,
	): OrgView {
		val me = permissions.person(authorization, "change members", Act.ORG_MANAGE)
		singular(kind)
		org.setMembers(me.person!!, id, body.userIds)
		return org.view(me.orgId!!, true, me)
	}

	@PostMapping("/links")
	fun link(@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?, @RequestBody body: LinkIn): OrgView {
		val me = permissions.person(authorization, "link organisation items", Act.ORG_MANAGE)
		org.link(me.person!!, body)
		return org.view(me.orgId!!, true, me)
	}

	@DeleteMapping("/links")
	fun unlink(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestParam("from_id") fromId: String,
		@RequestParam("to_id") toId: String,
	): OrgView {
		val me = permissions.person(authorization, "unlink organisation items", Act.ORG_MANAGE)
		org.unlink(me.person!!, fromId, toId)
		return org.view(me.orgId!!, true, me)
	}

	private fun singular(kind: String): String = when (kind) {
		"teams" -> "team"
		"products" -> "product"
		"projects" -> "project"
		"repositories" -> "repository"
		"environments" -> "environment"
		else -> throw NotFoundException("no organisation item kind $kind")
	}
}
