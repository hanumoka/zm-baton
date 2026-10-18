package io.github.hanumoka.zmbaton.server

import io.github.hanumoka.zmbaton.server.permission.PermissionFacts
import io.github.hanumoka.zmbaton.server.permission.PermissionRules
import io.github.hanumoka.zmbaton.server.permission.ScopeColumns
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * An export is a readable snapshot, not a database backup. Begin with the same visibility rule as
 * the list/detail APIs, then omit dependent records as well: a run's instruction and a document
 * version must not outlive the access check of their parent. Structured JSON references count too
 * (master comparisons and audit details keep copies of the referenced material).
 *
 * All collections are loaded in ExportService's repeatable-read transaction before this runs.
 * The conservative rule drops an entire dependent row; it never presents a partial execution or
 * approval as complete. The same identifier may occur in nested arrays/maps. Human-written text
 * is not parsed or rewritten. Unshared session summaries are separately removed by ExportService.
 */
@Component
class ExportScope(private val jdbc: JdbcClient, private val facts: PermissionFacts) {

	fun apply(exporter: String, tables: Map<String, List<LinkedHashMap<String, Any?>>>): Map<String, List<LinkedHashMap<String, Any?>>> {
		val actor = facts.person(exporter)
		val hidden = mutableSetOf<String>()
		for ((table, alias, columns) in listOf(
			Triple("work_items", "w", ScopeColumns.WORK_ITEMS),
			Triple("decisions", "d", ScopeColumns.DECISIONS),
			Triple("documents", "d", ScopeColumns.DOCUMENTS),
		)) {
			val scope = PermissionRules.filter(actor, columns)
			// A nullable executor/project makes SQL UNKNOWN, which is also not readable.
			val stmt = jdbc.sql("select $alias.id from $table $alias where (${scope.sql}) is not true")
			scope.params.forEach { (key, value) -> stmt.param(key, value) }
			hidden += stmt.query(String::class.java).list().filterNotNull()
		}
		val people = jdbc.sql("select user_id from memberships where org_id = :org").param("org", actor.orgId)
			.query(String::class.java).list().toSet()
		fun foreignOrPersonal(table: String, row: Map<String, Any?>): Boolean =
			(row["org_id"] != null && row["org_id"] != actor.orgId) ||
				(table == "organizations" && row["id"] != actor.orgId) ||
				(table == "users" && row["id"] !in people) ||
				(table == "inbox_items" && row["user_id"] != exporter)

		for (row in tables["questions"].orEmpty()) {
			val id = row["id"] as String
			if (facts.question(id)?.let { PermissionRules.canSee(actor, it) } != true) hidden += id
		}
		for (row in tables["review_items"].orEmpty()) {
			val id = row["id"] as String
			if (facts.review(id)?.let { PermissionRules.canSee(actor, it) } != true) hidden += id
		}

		for ((table, rows) in tables) for (row in rows) {
			if (foreignOrPersonal(table, row)) (row["id"] as? String)?.let(hidden::add)
		}
		// A parent may appear later in the fixed collection order (e.g. a document version's approval).
		// Continue until no newly hidden parent can invalidate another record.
		do {
			var changed = false
			for (rows in tables.values) for (row in rows) {
				val id = row["id"] as? String ?: continue
				if (id !in hidden && referencesHidden(row, hidden)) changed = hidden.add(id) || changed
			}
		} while (changed)
		return tables.mapValues { (table, rows) -> rows.filterNot { foreignOrPersonal(table, it) || referencesHidden(it, hidden) } }
	}

	private fun referencesHidden(value: Any?, hidden: Set<String>): Boolean = when (value) {
		is String -> value in hidden
		is Map<*, *> -> value.values.any { referencesHidden(it, hidden) }
		is Iterable<*> -> value.any { referencesHidden(it, hidden) }
		else -> false
	}
}
