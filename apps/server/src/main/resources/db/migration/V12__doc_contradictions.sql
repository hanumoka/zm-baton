-- Stage 1, document contradiction candidates (FN-0401 minimum, contract v1 "충돌을 찾아 관계자에게 알린다"
-- > "문서 모순 후보", DEC-0086 1-2, DEC-0095). When a document version is approved, or a decision made
-- here is accepted or replaces another, the server asks the master to compare it with documents and
-- decisions picked by full-text search. The master's run is an ordinary run of an owner's work item
-- (contract "문서 모순 후보" 5: the same claim, generation and reports); this table keeps why it ran, what
-- it compared and what came of it. Candidates become notices of kind `doc_contradiction`.
-- Only adds a table and one column the data model already has (notices.source_run_id); no existing row
-- changes.

create table master_checks (
	-- `mck_<uuidv7>`. Contract v1 has no prefix for a master check; this one keeps it readable.
	id text primary key,
	org_id text not null references organizations (id),
	-- The project of the first subject, for the screens. Null when it has none.
	project_id text references projects (id),
	-- What started it: a document version approved, a decision accepted, a decision accepted that
	-- replaces another, or decisions a zm-docs import changed (one check per import).
	trigger text not null
		check (trigger in ('document_version_approved', 'decision_accepted', 'decision_superseded', 'zm_docs_import')),
	-- What changed: `[{"kind": "document_version" | "decision", "id", "document_id"?, "number"?, "ref"?, "label"}]`.
	subjects jsonb not null,
	-- Exactly what the master was given, so a quoted sentence is checked against the text it saw:
	-- `[{"key": "S1" | "C1", "kind", "id", "version_id"?, "number"?, "ref"?, "label", "title", "linked", "text"}]`.
	material jsonb not null default '[]',
	-- `api`: the server's own API path (on only when its key variable is set). `local`: the owner's task,
	-- run by the owner's local connector with the owner's own subscription. Null when skipped first.
	path text check (path in ('api', 'local')),
	state text not null default 'requested' check (state in ('requested', 'running', 'done', 'failed', 'skipped')),
	work_item_id text references work_items (id),
	-- The run that answers now. When the API path fails, a local run replaces it on the same work item.
	run_id text references runs (id),
	-- The API run that failed before the local run took over.
	api_run_id text references runs (id),
	-- Why it was skipped or failed, in words for a person.
	reason text,
	-- `{"proposed", "kept", "screened"?, "dropped": [{"reason", "left", "right"}], "notice_ids": [...]}`.
	outcome jsonb,
	created_at timestamptz not null default now(),
	finished_at timestamptz
);

-- The daily limit counts the checks that ran or will run today.
create index master_checks_by_day on master_checks (created_at) where state <> 'skipped';

-- The sweeper looks at checks that are not finished.
create index master_checks_open on master_checks (state, created_at) where state in ('requested', 'running');

-- Data model "notices": the master run that found a contradiction candidate.
alter table notices add column source_run_id text references runs (id);
