package io.github.hanumoka.zmbaton.server.permission

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/** Reads structured references in copied evidence. Human-written prose is never parsed. */
@Component
class RecordVisibility(private val jdbc: JdbcClient, private val facts: PermissionFacts, private val json: ObjectMapper) {

	/** Request-local memoization: permissions are never cached across requests or users. */
	fun reader(viewer: Actor): Reader = Reader(viewer)

	inner class Reader(private val viewer: Actor) {
		private val cache = mutableMapOf<String, Boolean>()
		private val resolving = mutableSetOf<String>()
		private val mapType = object : TypeReference<Map<String, Any?>>() {}

		fun allows(value: Any?): Boolean = when (value) {
			is String -> reference(value)
			is Map<*, *> -> value.values.all { allows(it) }
			is Iterable<*> -> value.all { allows(it) }
			else -> true
		}

		private fun reference(id: String): Boolean {
			val kind = id.substringBefore('_')
			if (kind !in KINDS || !ID.matches(id)) return true
			cache[id]?.let { return it }
			// Checks and their notices can point back to each other. The outer traversal still
			// checks every leaf; do not memoize these composite nodes while a cycle is open.
			if (!resolving.add(id)) return true
			val visible = try {
				when (kind) {
					"wrk" -> see(facts.workItem(id))
					"dcs" -> see(facts.decision(id))
					"doc" -> see(facts.document(id))
					"qst" -> see(facts.question(id))
					"rvw" -> see(facts.review(id))
					"run" -> see(facts.run(id))
					"mnt" -> see(facts.note(id))
					"dvr" -> parent("document_versions", "doc_id", id)
					"dlg" -> parent("delegations", "work_item_id", id)
					"inp" -> parent("run_inputs", "run_id", id)
					"apv" -> parent("approvals", "target_id", id)
					"mck" -> evidence("master_checks", id)
					"ntc" -> evidence("notices", id)
					else -> false
				}
			} finally {
				resolving.remove(id)
			}
			if (kind !in setOf("mck", "ntc")) cache[id] = visible
			return visible
		}

		private fun see(target: Target?): Boolean = target != null && PermissionRules.canSee(viewer, target)

		private fun parent(table: String, column: String, id: String): Boolean =
			jdbc.sql("select $column from $table where id = :id").param("id", id).query(String::class.java)
				.optional().orElse(null)?.let { allows(it) } == true

		private fun evidence(table: String, id: String): Boolean {
			val raw = jdbc.sql("select row_to_json(t)::text from $table t where id = :id").param("id", id)
				.query(String::class.java).optional().orElse(null) ?: return false
			val row = json.readValue(raw, mapType)
			if (row["org_id"] != null && row["org_id"] != viewer.orgId) return false
			return allows(row.filterKeys { it != "id" })
		}
	}

	companion object {
		private val KINDS = setOf("wrk", "dcs", "doc", "qst", "rvw", "run", "mnt", "dvr", "dlg", "inp", "apv", "mck", "ntc")
		// Values such as run_snapshot and doc_contradiction are enum values, not references.
		private val ID = Regex("^[a-z]{3}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
	}
}
