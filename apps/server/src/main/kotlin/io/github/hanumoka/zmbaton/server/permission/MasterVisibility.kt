package io.github.hanumoka.zmbaton.server.permission

/** A master's copied prompt/result is readable only when every source is still readable. */
object MasterVisibility {
	// Subjects use document_version/document_id; material uses document/id. Both retain decisions.
	const val REFERENCES = """
		select case when item ->> 'kind' in ('document', 'document_version') then 'doc'
		            when item ->> 'kind' = 'decision' then 'dcs' else 'unknown' end as kind,
		       case when item ->> 'kind' = 'document_version' then item ->> 'document_id' else item ->> 'id' end as id
		  from jsonb_array_elements(mc.material || mc.subjects) item
	"""

	fun filter(viewer: Actor): SqlFilter {
		val docs = PermissionRules.filter(viewer, ScopeColumns.DOCUMENTS)
		val decisions = PermissionRules.filter(viewer, ScopeColumns.DECISIONS)
		return SqlFilter(
			"""not exists (
			  with visible_sources as materialized (
			    select 'doc' as kind, d.id from documents d where ${docs.sql}
			    union all select 'dcs', d.id from decisions d where ${decisions.sql}
			  )
			  select 1 from master_checks mc cross join lateral ($REFERENCES) source
			  left join visible_sources v on v.kind = source.kind and v.id = source.id
			  where mc.work_item_id = w.id and v.id is null
			)""".trimIndent(), docs.params + decisions.params,
		)
	}
}
