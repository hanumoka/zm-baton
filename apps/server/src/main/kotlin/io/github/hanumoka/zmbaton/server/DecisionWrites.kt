package io.github.hanumoka.zmbaton.server

import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

// Decisions written in zm-baton (FN-0604, G46.3): a person proposes one, edits it while it is
// proposed, and its approver accepts or rejects it; an accepted decision is replaced by a later
// one that names it in `supersedes_id`. Copies from zm-docs are refused with the reason. Every
// change checks the `version` the person saw and is written to audit_log, which is also the
// decision's history; every verdict is an approvals row.

data class ApprovalIn(
	/** `approved` (accept) or `rejected` (reject). */
	val decision: String,
	val version: Long,
	val note: String? = null,
)

@Service
class DecisionWriteService(
	private val jdbc: JdbcClient,
	private val json: ObjectMapper,
	private val decisions: DecisionService,
	private val approvals: ApprovalService,
	private val sessions: SessionService,
	private val events: ApplicationEventPublisher,
) {
	companion object {
		const val QUESTION_MAX = 1000
		const val TEXT_MAX = 20_000
		const val OPTION_TITLE_MAX = 300
		const val OPTION_BODY_MAX = 4000
		const val OPTIONS_MAX = 20
		const val PEOPLE_MAX = 20
		const val NAME_MAX = 100
		const val PROJECTS_MAX = 20
		const val WORK_MAX = 50
		const val NOTE_MAX = 1000
		val FIELDS = listOf(
			"question", "context", "options", "chosen_option", "choice", "rationale", "review_conditions", "roles",
			"project_ids", "work_item_ids", "supersedes_id",
		)
		private val KEY = Regex("^[A-Z]{1,2}$")
	}

	private val rolesType = object : TypeReference<DecisionRoles>() {}
	private val optionsType = object : TypeReference<List<DecisionOption>>() {}

	/** Everything a person writes in a decision, checked. */
	private data class Draft(
		val question: String,
		val context: String?,
		val options: List<DecisionOption>,
		val chosenOption: String?,
		val choice: String?,
		val rationale: String?,
		val reviewConditions: String?,
		val roles: DecisionRoles,
		val projectIds: List<String>,
		val workItemIds: List<String>,
		val supersedesId: String?,
	) {
		fun value(field: String): Any? = when (field) {
			"question" -> question
			"context" -> context
			"options" -> options
			"chosen_option" -> chosenOption
			"choice" -> choice
			"rationale" -> rationale
			"review_conditions" -> reviewConditions
			"roles" -> roles
			"project_ids" -> projectIds
			"work_item_ids" -> workItemIds
			else -> supersedesId
		}
	}

	// ---------------------------------------------------------------- propose and edit

	/** A new proposal, numbered `DCS-NNNN`. */
	@Transactional
	fun propose(body: Map<String, Any?>, actor: String): DecisionDetail {
		unknownFields(body.keys)
		if (body["question"] == null) throw BadRequestException("question is required")
		val empty = Draft("", null, emptyList(), null, null, null, null, DecisionRoles(driver = actor), emptyList(), emptyList(), null)
		val draft = overlay(empty, body, actor, selfId = null)
		val id = Ids.decision()
		val number = jdbc.sql("select nextval('decision_numbers')").query(Long::class.javaObjectType).single().toInt()
		jdbc.sql(
			"""
			insert into decisions (id, org_id, number, question, status, source_of_truth, context, options, chosen_option,
			                       choice, rationale, review_conditions, roles, supersedes_id)
			values (:id, :org, :number, :question, 'proposed', 'product', :context, cast(:options as jsonb), :chosen,
			        :choice, :rationale, :review, cast(:roles as jsonb), :supersedes)
			""".trimIndent(),
		).param("id", id).param("org", WorkItemService.ORG).param("number", number).also { bind(it, draft) }.update()
		writeLinks(id, draft)
		audit(actor, "decision.proposed", id, mapOf("ref" to DecisionService.refOf(number), "question" to draft.question))
		return decisions.detail(id, actor)
	}

	/**
	 * Changes the fields present in [patch] while the decision is proposed. A present key with null
	 * clears the field; `question` cannot be cleared. `version` must be the version the person saw.
	 */
	@Transactional
	fun update(id: String, patch: Map<String, Any?>, actor: String): DecisionDetail {
		val version = (patch["version"] as? Number)?.toLong() ?: throw BadRequestException("version is required")
		unknownFields(patch.keys - "version")
		val row = lock(id, version)
		allow(row, actor, DecisionRules.EDIT)
		val before = current(id)
		val after = overlay(before, patch - "version", actor, selfId = id)
		val changed = FIELDS.filter { it in patch && before.value(it) != after.value(it) }
		if (changed.isEmpty()) throw BadRequestException("nothing to change")
		jdbc.sql(
			"""
			update decisions
			   set question = :question, context = :context, options = cast(:options as jsonb), chosen_option = :chosen,
			       choice = :choice, rationale = :rationale, review_conditions = :review, roles = cast(:roles as jsonb),
			       supersedes_id = :supersedes, version = version + 1, updated_at = now()
			 where id = :id
			""".trimIndent(),
		).param("id", id).also { bind(it, after) }.update()
		writeLinks(id, after)
		audit(actor, "decision.updated", id, mapOf("fields" to changed))
		return decisions.detail(id, actor)
	}

	// ---------------------------------------------------------------- accept and reject

	/**
	 * The approver's verdict on a proposal. Accepting one that names an earlier decision in
	 * `supersedes_id` replaces that decision in the same transaction: it becomes `superseded`
	 * and points back here. Once an acceptance commits, the master compares the decision with related
	 * documents and decisions (contract "문서 모순 후보" 1, MasterChecks.kt).
	 */
	@Transactional
	fun approve(id: String, body: ApprovalIn, actor: String): DecisionDetail {
		val verdict = body.decision.trim()
		val action = when (verdict) {
			"approved" -> DecisionRules.ACCEPT
			"rejected" -> DecisionRules.REJECT
			else -> throw BadRequestException("decision must be approved or rejected")
		}
		val note = body.note?.trim()?.take(NOTE_MAX)?.ifEmpty { null }
		val row = lock(id, body.version)
		allow(row, actor, action)
		val target = if (action == DecisionRules.ACCEPT) row.supersedesId?.let { lockTarget(it, actor) } else null
		val status = if (action == DecisionRules.ACCEPT) "accepted" else "rejected"
		jdbc.sql("update decisions set status = :status, decided_on = current_date, version = version + 1, updated_at = now() where id = :id")
			.param("status", status).param("id", id).update()
		val approval = approvals.record("decision", id, row.version, actor, verdict, note)
		if (target != null) {
			jdbc.sql(
				"""
				update decisions set status = 'superseded', superseded_by_id = :by, version = version + 1, updated_at = now()
				 where id = :id
				""".trimIndent(),
			).param("by", id).param("id", target.id).update()
			audit(actor, "decision.superseded", target.id, mapOf("by" to row.ref))
		}
		audit(
			actor, if (action == DecisionRules.ACCEPT) "decision.accepted" else "decision.rejected", id,
			mapOf("approval" to approval, "version" to row.version, "note" to note, "supersedes" to target?.ref),
		)
		if (action == DecisionRules.ACCEPT) {
			val trigger = if (target != null) "decision_superseded" else "decision_accepted"
			events.publishEvent(ContradictionTrigger(trigger, listOf(CheckSubject("decision", id))))
		}
		return decisions.detail(id, actor)
	}

	// ---------------------------------------------------------------- internals

	/** A decision locked for a change, with what the rules need. */
	private data class Row(
		val id: String,
		val ref: String?,
		val version: Long,
		val supersedesId: String?,
		val facts: DecisionRules.Facts,
	)

	private fun load(id: String, forUpdate: Boolean): Row? {
		if (forUpdate) jdbc.sql("select id from decisions where id = :id for update").param("id", id).query(String::class.java).optional()
		val view = decisions.load(id) ?: return null
		return jdbc.sql("select git_ref ->> 'path', choice, chosen_option, org_id, supersedes_id from decisions where id = :id")
			.param("id", id).query { rs, _ ->
				val facts = decisions.facts(view, rs.getString(1)?.let { mapOf("path" to it) }, rs.getString(2), rs.getString(3), rs.getString(4))
				Row(id, view.ref, view.version, rs.getString(5), facts)
			}.single()
	}

	/** Locks the decision and checks the version the person saw. */
	private fun lock(id: String, version: Long): Row {
		val row = load(id, forUpdate = true) ?: throw NotFoundException("no decision $id")
		if (row.version != version) {
			throw ConflictException("the decision changed (version ${row.version}, you sent $version); reload it")
		}
		return row
	}

	private fun allow(row: Row, actor: String, action: String) {
		val a = DecisionRules.action(row.facts, actor, action)
		if (!a.allowed) throw ConflictException(a.reason ?: "not allowed")
	}

	/** The decision a proposal replaces, locked; it must still be one that may be replaced. */
	private fun lockTarget(id: String, actor: String): Row {
		val target = load(id, forUpdate = true) ?: throw ConflictException("대체할 결정 $id 을(를) 찾지 못했다")
		val a = DecisionRules.action(target.facts, actor, DecisionRules.SUPERSEDE)
		if (!a.allowed) throw ConflictException("대체할 결정 ${target.ref ?: id}: ${a.reason}")
		return target
	}

	/** What is stored now, as a draft to change. */
	private fun current(id: String): Draft =
		jdbc.sql(
			"""
			select question, context, options::text, chosen_option, choice, rationale, review_conditions, roles::text, supersedes_id,
			       coalesce((select array_agg(project_id order by project_id) from decision_projects where decision_id = d.id), '{}'),
			       coalesce((select array_agg(work_item_id order by created_at, work_item_id) from decision_work_items where decision_id = d.id), '{}')
			  from decisions d where d.id = :id
			""".trimIndent(),
		).param("id", id).query { rs, _ ->
			@Suppress("UNCHECKED_CAST")
			Draft(
				rs.getString(1), rs.getString(2), json.readValue(rs.getString(3), optionsType), rs.getString(4), rs.getString(5),
				rs.getString(6), rs.getString(7), json.readValue(rs.getString(8), rolesType),
				(rs.getArray(10).array as Array<String>).toList(), (rs.getArray(11).array as Array<String>).toList(), rs.getString(9),
			)
		}.single()

	/** [base] with the fields present in [fields], each checked. */
	private fun overlay(base: Draft, fields: Map<String, Any?>, actor: String, selfId: String?): Draft {
		var d = base
		if ("question" in fields) {
			val q = text(fields["question"], "question", QUESTION_MAX) ?: throw BadRequestException("question cannot be empty")
			d = d.copy(question = q)
		}
		if ("context" in fields) d = d.copy(context = text(fields["context"], "context", TEXT_MAX))
		if ("choice" in fields) d = d.copy(choice = text(fields["choice"], "choice", TEXT_MAX))
		if ("rationale" in fields) d = d.copy(rationale = text(fields["rationale"], "rationale", TEXT_MAX))
		if ("review_conditions" in fields) d = d.copy(reviewConditions = text(fields["review_conditions"], "review_conditions", TEXT_MAX))
		if ("options" in fields) d = d.copy(options = options(fields["options"], base.options.map { it.key }))
		if ("chosen_option" in fields) d = d.copy(chosenOption = text(fields["chosen_option"], "chosen_option", 2))
		if ("roles" in fields) d = d.copy(roles = roles(fields["roles"], actor))
		if ("project_ids" in fields) d = d.copy(projectIds = ids(fields["project_ids"], "project_ids", PROJECTS_MAX, "projects"))
		if ("work_item_ids" in fields) d = d.copy(workItemIds = ids(fields["work_item_ids"], "work_item_ids", WORK_MAX, "work_items"))
		if ("supersedes_id" in fields) d = d.copy(supersedesId = supersedes(fields["supersedes_id"], actor, selfId))
		val chosen = d.chosenOption
		if (chosen != null && d.options.none { it.key == chosen }) throw BadRequestException("chosen_option $chosen is not one of the options")
		return d.copy(roles = d.roles.copy(approver = ApprovalPolicy.approverFor(WorkItemService.ORG, "decision")))
	}

	private fun unknownFields(keys: Set<String>) {
		val unknown = keys - FIELDS.toSet()
		if (unknown.isNotEmpty()) throw BadRequestException("unknown fields $unknown")
	}

	private fun text(raw: Any?, field: String, max: Int): String? {
		if (raw != null && raw !is String) throw BadRequestException("$field must be text")
		val t = (raw as String?)?.trim()?.ifEmpty { null } ?: return null
		if (t.length > max) throw BadRequestException("$field must be at most $max characters")
		return t
	}

	/**
	 * Options in order. A missing key gets the next letter that neither this list nor the stored one
	 * [used], so `A`, `B`, ... stay put when one is removed and a key removed in this change does
	 * not come back as a different option.
	 */
	private fun options(raw: Any?, used: List<String>): List<DecisionOption> {
		if (raw == null) return emptyList()
		val list = raw as? List<*> ?: throw BadRequestException("options must be a list")
		if (list.size > OPTIONS_MAX) throw BadRequestException("at most $OPTIONS_MAX options")
		val given = list.map { o ->
			val m = o as? Map<*, *> ?: throw BadRequestException("each option must be an object")
			val title = text(m["title"], "option title", OPTION_TITLE_MAX) ?: throw BadRequestException("each option needs a title")
			val key = text(m["key"], "option key", 2)
			if (key != null && !KEY.matches(key)) throw BadRequestException("option key must be one or two capital letters, not $key")
			Triple(key, title, text(m["body"], "option body", OPTION_BODY_MAX))
		}
		val keys = given.mapNotNull { it.first }
		if (keys.size != keys.toSet().size) throw BadRequestException("option keys must be different")
		val free = (('A'..'Z').map { "$it" } + ('A'..'Z').flatMap { a -> ('A'..'Z').map { b -> "$a$b" } }).filter { it !in keys && it !in used }.iterator()
		return given.map { (key, title, body) -> DecisionOption(key ?: free.next(), title, body) }
	}

	private fun roles(raw: Any?, actor: String): DecisionRoles {
		if (raw == null) return DecisionRoles(driver = actor)
		val m = raw as? Map<*, *> ?: throw BadRequestException("roles must be an object")
		val unknown = m.keys.map { it.toString() } - setOf("driver", "approver", "contributors", "informed")
		if (unknown.isNotEmpty()) throw BadRequestException("unknown roles $unknown")
		val approver = ApprovalPolicy.approverFor(WorkItemService.ORG, "decision")
		val asked = text(m["approver"], "approver", NAME_MAX)
		if (asked != null && asked != approver) {
			throw BadRequestException("1단계의 승인자는 ${ApprovalPolicy.label(approver)} 한 명이다. 다른 승인자는 권한 판정이 생긴 뒤에 고른다")
		}
		return DecisionRoles(
			driver = text(m["driver"], "driver", NAME_MAX) ?: actor,
			approver = approver,
			contributors = people(m["contributors"], "contributors"),
			informed = people(m["informed"], "informed"),
		)
	}

	private fun people(raw: Any?, field: String): List<String> {
		if (raw == null) return emptyList()
		val list = raw as? List<*> ?: throw BadRequestException("$field must be a list")
		val names = list.mapNotNull { text(it, field, NAME_MAX) }.distinct()
		if (names.size > PEOPLE_MAX) throw BadRequestException("at most $PEOPLE_MAX $field")
		return names
	}

	/** Ids that must exist in [table]; order kept, repeats dropped. [table] is one of two fixed names. */
	private fun ids(raw: Any?, field: String, max: Int, table: String): List<String> {
		if (raw == null) return emptyList()
		val list = raw as? List<*> ?: throw BadRequestException("$field must be a list")
		val ids = list.map { (it as? String)?.trim()?.ifEmpty { null } ?: throw BadRequestException("$field must hold ids") }.distinct()
		if (ids.size > max) throw BadRequestException("at most $max $field")
		if (ids.isEmpty()) return ids
		check(table == "projects" || table == "work_items")
		val found = jdbc.sql("select id from $table where id in (:ids)").param("ids", ids).query(String::class.java).list().toSet()
		val missing = ids.filter { it !in found }
		if (missing.isNotEmpty()) throw BadRequestException("no ${table.removeSuffix("s").replace('_', ' ')} $missing")
		return ids
	}

	private fun supersedes(raw: Any?, actor: String, selfId: String?): String? {
		val id = text(raw, "supersedes_id", 100) ?: return null
		if (id == selfId) throw BadRequestException("a decision cannot replace itself")
		val target = load(id, forUpdate = false) ?: throw BadRequestException("no decision $id")
		val a = DecisionRules.action(target.facts, actor, DecisionRules.SUPERSEDE)
		if (!a.allowed) throw ConflictException("${target.ref ?: id}: ${a.reason}")
		return id
	}

	private fun bind(stmt: JdbcClient.StatementSpec, d: Draft) {
		stmt.param("question", d.question).param("context", d.context).param("options", json.writeValueAsString(d.options))
			.param("chosen", d.chosenOption).param("choice", d.choice).param("rationale", d.rationale)
			.param("review", d.reviewConditions).param("roles", json.writeValueAsString(d.roles)).param("supersedes", d.supersedesId)
	}

	private fun writeLinks(id: String, d: Draft) {
		jdbc.sql("delete from decision_projects where decision_id = :id").param("id", id).update()
		for (p in d.projectIds) {
			jdbc.sql("insert into decision_projects (decision_id, project_id) values (:d, :p)").param("d", id).param("p", p).update()
		}
		jdbc.sql("delete from decision_work_items where decision_id = :id and work_item_id not in (:keep)")
			.param("id", id).param("keep", d.workItemIds.ifEmpty { listOf("") }).update()
		for (w in d.workItemIds) {
			jdbc.sql("insert into decision_work_items (decision_id, work_item_id) values (:d, :w) on conflict do nothing")
				.param("d", id).param("w", w).update()
		}
	}

	private fun audit(actor: String, action: String, id: String, details: Map<String, Any?>) =
		sessions.audit("person", actor, action, "dcs", id, details)
}

@RestController
@RequestMapping("/api")
class DecisionWriteController(private val writes: DecisionWriteService, private val people: PersonService) {

	/** A new proposal. The body has the same fields as a change, without `version`. */
	@PostMapping("/decisions")
	@ResponseStatus(HttpStatus.CREATED)
	fun propose(
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: Map<String, Any?>,
	): DecisionDetail {
		people.refuseSessionToken(authorization, "propose a decision")
		return writes.propose(body, Requester.person())
	}

	/** Changes only the fields present in the body; `version` is required. */
	@PatchMapping("/decisions/{id}")
	fun update(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody patch: Map<String, Any?>,
	): DecisionDetail {
		people.refuseSessionToken(authorization, "change a decision")
		return writes.update(id, patch, Requester.person())
	}

	/** Accept (`approved`) or reject (`rejected`) a proposal (data model "approvals"). */
	@PostMapping("/decisions/{id}/approvals")
	fun approve(
		@PathVariable id: String,
		@RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
		@RequestBody body: ApprovalIn,
	): DecisionDetail {
		people.refuseSessionToken(authorization, "accept or reject a decision")
		return writes.approve(id, body, Requester.person())
	}
}
