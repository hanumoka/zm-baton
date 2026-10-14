package io.github.hanumoka.zmbaton.server.permission

/**
 * Who may do what (FN-0103, core module 1 "업무 판정: 상태 전이·권한·승인"). Pure, like Workflow and
 * DecisionRules: the caller loads the facts ([Actor], [Target]) and asks. [Permissions] is the one
 * service that does so for every request; nothing else decides whether a person may act.
 *
 * Three things decide: the actor's roles in the organisation (G02.1), the record's sharing scope
 * (G06.1) and the actor's relation to the record (responsible, executor, recipient). A one-person
 * organisation is the same structure with one member who holds every role (FN-0104, G37.1), so what
 * the owner could do before, the owner can still do.
 */

/** Roles in an organisation (data model "memberships.roles"). Disciplines are never checked (R55). */
enum class Role(val wire: String, val label: String) {
	REQUESTER("requester", "요청자"),
	ASSIGNEE("assignee", "담당자"),
	REVIEWER("reviewer", "검토자"),
	APPROVER("approver", "승인자"),
	OPERATOR("operator", "운영자"),
	MASTER_ADMIN("master_admin", "마스터 관리자");

	companion object {
		fun parse(wire: String): Role? = entries.firstOrNull { it.wire == wire }
	}
}

/** Sharing scope (G06.1), narrowest first. */
enum class Visibility(val wire: String, val label: String) {
	PERSONAL("personal", "개인"),
	RESTRICTED("restricted", "제한"),
	TEAM("team", "팀"),
	ORG("org", "조직"),
	PUBLIC("public", "공개");

	companion object {
		fun parse(wire: String?): Visibility? = entries.firstOrNull { it.wire == wire }

		/** A stored value. The database checks it; anything else counts as the narrowest scope. */
		fun stored(wire: String?): Visibility = parse(wire) ?: PERSONAL
	}
}

enum class ActorKind { PERSON, SESSION, RUN, MANAGER, SYSTEM }

/**
 * Who asks. A session, a run or a manager acts for a person ([person]) and holds that person's
 * organisation, roles, teams and projects; what only a person may do stays closed to it.
 */
data class Actor(
	val kind: ActorKind,
	/** `usr_`, `ses_`, `run_`, `mgr_` or the server's own actor. */
	val id: String,
	val person: String?,
	/** The person's manager (`mgr_`): an executor id the person stands behind. */
	val manager: String?,
	val orgId: String?,
	/** An active member of [orgId]. */
	val member: Boolean,
	val roles: Set<Role>,
	val teams: Set<String> = emptySet(),
	val projects: Set<String> = emptySet(),
)

/** The record an action is about, with the facts its scope and relation need. */
data class Target(
	/** `wrk`, `dcs`, `doc`, `qst`, `rvw`, `inb`, `ntc`, `acc`, `dev`, `ses`, `mnt`, `prj`. */
	val kind: String,
	val id: String,
	val orgId: String,
	val visibility: Visibility = Visibility.ORG,
	/** The person who owns it: the work item's responsible person, a document's owner, a note's writer. */
	val owner: String? = null,
	/** Others it concerns: an executor (`usr_` or `mgr_`), a decision's roles, a question's people, a recipient. */
	val people: Set<String> = emptySet(),
	val projects: Set<String> = emptySet(),
	/** The teams that take on those projects (org_links team → project). */
	val projectTeams: Set<String> = emptySet(),
	/** What a refusal says when the actor may not even know the record exists. */
	val missing: String = "no $kind $id",
)

enum class Relation {
	/** The role is enough. */
	ANY,

	/** The record's owner or one of its people, or whoever holds the act's override role. */
	INVOLVED,

	/** The record's owner only, with no override: their own things. */
	SELF,
}

/**
 * What an actor asks to do. [roles] empty: any active member. Starting values chosen with this
 * service (docs/stage1/org-and-permissions.md); FN-0102 (stage 2) assigns approvers and delegates.
 */
enum class Act(
	val wire: String,
	val label: String,
	val roles: Set<Role> = emptySet(),
	val relation: Relation = Relation.ANY,
	val override: Role? = null,
	/** Only a person (contract "등록과 세션 토큰" 6; data model constraint 6). */
	val personOnly: Boolean = false,
	/** What a session, run or manager is told instead, when it is a fixed sentence. */
	val notPerson: String? = null,
) {
	READ("read", "보기"),
	WORK_CREATE("work.create", "작업 만들기", setOf(Role.REQUESTER, Role.ASSIGNEE, Role.OPERATOR)),
	WORK_CHANGE("work.change", "작업 고치기·단계 옮기기", setOf(Role.ASSIGNEE, Role.OPERATOR), Relation.INVOLVED, Role.OPERATOR),
	WORK_HANDOFF("work.handoff", "AI에게 맡기기·실행 다루기", setOf(Role.ASSIGNEE, Role.OPERATOR), Relation.INVOLVED, Role.OPERATOR),
	SHARE("share", "공유 범위 바꾸기", relation = Relation.SELF),
	QUESTION_ASK("question.ask", "질문 남기기"),
	QUESTION_ANSWER("question.answer", "질문에 답하기·거두기", relation = Relation.INVOLVED, override = Role.OPERATOR),
	DECISION_WRITE("decision.write", "결정 제안·고치기", setOf(Role.REQUESTER, Role.ASSIGNEE, Role.APPROVER, Role.OPERATOR)),
	DECISION_APPROVE("decision.approve", "결정 채택·기각", setOf(Role.APPROVER), personOnly = true),
	DOCUMENT_WRITE("document.write", "문서 등록·판 쓰기", setOf(Role.REQUESTER, Role.ASSIGNEE, Role.OPERATOR)),
	DOCUMENT_EDIT("document.edit", "문서 정보 고치기", setOf(Role.REQUESTER, Role.ASSIGNEE, Role.OPERATOR), Relation.INVOLVED, Role.OPERATOR),
	DOCUMENT_APPROVE("document.approve", "문서판 승인", setOf(Role.APPROVER), personOnly = true),
	REVIEW_DECIDE(
		"review.decide", "사람 검토 승인·반려", setOf(Role.REVIEWER, Role.APPROVER), Relation.INVOLVED, Role.OPERATOR, personOnly = true,
		notPerson = "사람 검토 요청은 사람만 승인하거나 반려한다. AI는 닫을 수 없다",
	),
	RELAY_ANSWER("relay.answer", "중계된 요청에 답하기", relation = Relation.SELF, personOnly = true),
	NOTICE_JUDGE("notice.judge", "알림 판정", relation = Relation.INVOLVED, override = Role.MASTER_ADMIN, personOnly = true),
	SETTINGS("settings", "조직 설정 바꾸기", setOf(Role.OPERATOR), personOnly = true),
	ACCOUNTS("accounts", "AI 계정 관리", setOf(Role.OPERATOR), personOnly = true),
	DEVICE("device", "기기 저장소 관리", relation = Relation.INVOLVED, override = Role.OPERATOR),
	ORG_MANAGE("org.manage", "조직 구조 관리", setOf(Role.OPERATOR), personOnly = true),
	AUDIT_READ("audit.read", "감사 기록 보기", setOf(Role.OPERATOR), personOnly = true),
	EXPORT("export", "내보내기", setOf(Role.OPERATOR), personOnly = true),
	IMPORT("import", "zm-docs 가져오기", setOf(Role.OPERATOR)),
	OWN_RECORD("own_record", "내 매니저 기록", relation = Relation.SELF),
}

data class Verdict(
	val allowed: Boolean,
	val reason: String?,
	/** The actor may not see the record: say it does not exist rather than why. */
	val hidden: Boolean = false,
)

/** A where-clause and its named parameters. */
data class SqlFilter(val sql: String, val params: Map<String, Any?>)

/**
 * How [PermissionRules.filter] reads one table: column expressions with the table's alias, and the
 * project tests written with `:pv_projects` and `:pv_teams`.
 */
data class ScopeColumns(
	val org: String,
	val visibility: String,
	val owner: String,
	val people: List<String>,
	val inProjects: String,
	val inProjectTeams: String,
) {
	companion object {
		private fun teamsOf(project: String) =
			"exists (select 1 from org_links l where l.from_kind = 'team' and l.to_kind = 'project' and l.to_id = $project and l.from_id in (:pv_teams))"

		/** `work_items w`: the responsible person owns it, its executor (a person or their manager) is involved. */
		val WORK_ITEMS = ScopeColumns(
			"w.org_id", "w.visibility", "w.responsible_user_id", listOf("w.executor_id"),
			"w.project_id in (:pv_projects)", teamsOf("w.project_id"),
		)

		/** `documents d`. */
		val DOCUMENTS = ScopeColumns(
			"d.org_id", "d.visibility", "d.owner_user_id", emptyList(),
			"d.project_id in (:pv_projects)", teamsOf("d.project_id"),
		)

		/** `decisions d`: its roles are involved; it may name several projects (decision_projects). */
		val DECISIONS = ScopeColumns(
			"d.org_id", "d.visibility", "d.owner_user_id",
			listOf(
				"d.roles ->> 'driver'", "d.roles ->> 'approver'",
				"(select min(c) from jsonb_array_elements_text(coalesce(d.roles -> 'contributors', '[]'::jsonb)) c where c in (:pv_who))",
				"(select min(c) from jsonb_array_elements_text(coalesce(d.roles -> 'informed', '[]'::jsonb)) c where c in (:pv_who))",
			),
			"exists (select 1 from decision_projects dp where dp.decision_id = d.id and dp.project_id in (:pv_projects))",
			"exists (select 1 from decision_projects dp join org_links l on l.from_kind = 'team' and l.to_kind = 'project' " +
				"and l.to_id = dp.project_id where dp.decision_id = d.id and l.from_id in (:pv_teams))",
		)
	}
}

object PermissionRules {

	/** What a refusal says when the actor is not an active member of the record's organisation. */
	const val NOT_MEMBER = "이 조직에서 활동 중인 사람이 아니다"

	/** Whether [a] may see [t]: its scope, and for a narrow scope who [a] is to it. */
	fun canSee(a: Actor, t: Target): Boolean {
		if (t.visibility == Visibility.PUBLIC) return true
		if (!a.member || a.orgId != t.orgId) return false
		return when (t.visibility) {
			Visibility.PERSONAL -> owns(a, t)
			Visibility.RESTRICTED -> involved(a, t) || a.projects.any { it in t.projects }
			Visibility.TEAM -> involved(a, t) || a.projects.any { it in t.projects } || a.teams.any { it in t.projectTeams }
			Visibility.ORG, Visibility.PUBLIC -> true
		}
	}

	fun owns(a: Actor, t: Target): Boolean = a.person != null && a.person == t.owner

	/** The record's owner, or one of its people as the person or as the person's manager. */
	fun involved(a: Actor, t: Target): Boolean =
		owns(a, t) || (a.person != null && a.person in t.people) || (a.manager != null && a.manager in t.people)

	/** Whether [a] may do [act], on [t] when the act is about a record. The first reason that applies is given. */
	fun check(a: Actor, act: Act, t: Target? = null): Verdict {
		if (act.personOnly && a.kind != ActorKind.PERSON) {
			return no(act.notPerson ?: "이 일(${act.label})은 사람만 한다. ${kindLabel(a.kind)}은 할 수 없다")
		}
		if (!a.member) return no(NOT_MEMBER)
		// A record the actor may not see answers as missing before any other reason, so no refusal tells it exists.
		if (t != null && !canSee(a, t)) return Verdict(false, t.missing, hidden = true)
		// Another organisation's public record may be read, never changed.
		if (t != null && t.orgId != a.orgId && act != Act.READ) return Verdict(false, t.missing, hidden = true)
		if (act.roles.isNotEmpty() && act.roles.none { it in a.roles }) {
			return no("이 일(${act.label})에는 ${act.roles.joinToString("·") { it.label }} 역할 가운데 하나가 필요하다")
		}
		if (t == null) return Verdict(true, null)
		return when (act.relation) {
			Relation.ANY -> Verdict(true, null)
			Relation.INVOLVED ->
				if (involved(a, t) || (act.override != null && act.override in a.roles)) Verdict(true, null)
				else no("이 일(${act.label})은 그 기록의 책임자·담당·받는 사람${act.override?.let { "이나 ${it.label}" } ?: ""}만 한다")
			Relation.SELF ->
				if (owns(a, t)) Verdict(true, null) else no("이 일(${act.label})은 본인만 한다")
		}
	}

	/**
	 * The same scope test as [canSee], as SQL over one table, so a list shows exactly the rows its
	 * records would allow (PermissionRulesTests compares the two on the same rows).
	 */
	fun filter(a: Actor, c: ScopeColumns): SqlFilter {
		if (!a.member || a.orgId == null) return SqlFilter("(${c.visibility} = 'public')", emptyMap())
		val who = listOfNotNull(a.person, a.manager).ifEmpty { listOf(NONE) }
		val involved = (listOf("${c.owner} = :pv_person") + c.people.map { "$it in (:pv_who)" }).joinToString(" or ")
		val sql = """
			(${c.visibility} = 'public' or (${c.org} = :pv_org and (
			    ${c.visibility} = 'org'
			 or (${c.visibility} = 'personal' and ${c.owner} = :pv_person)
			 or (${c.visibility} in ('restricted', 'team') and ($involved or ${c.inProjects}))
			 or (${c.visibility} = 'team' and ${c.inProjectTeams}))))
		""".trimIndent()
		return SqlFilter(
			sql,
			mapOf(
				"pv_org" to a.orgId, "pv_person" to (a.person ?: NONE), "pv_who" to who,
				"pv_projects" to a.projects.toList().ifEmpty { listOf(NONE) }, "pv_teams" to a.teams.toList().ifEmpty { listOf(NONE) },
			),
		)
	}

	/** Matches no id: an empty list cannot be written as `in ()`. */
	private const val NONE = "-"

	private fun no(reason: String) = Verdict(false, reason)

	private fun kindLabel(kind: ActorKind) = when (kind) {
		ActorKind.PERSON -> "사람"
		ActorKind.SESSION -> "AI 세션"
		ActorKind.RUN -> "AI 실행"
		ActorKind.MANAGER -> "매니저"
		ActorKind.SYSTEM -> "서버"
	}
}
