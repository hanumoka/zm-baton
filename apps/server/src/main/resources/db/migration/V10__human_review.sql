-- Stage 1: picking what people must see (FN-0411, DEC-0102, DEC-0105; contract v1 "사람에게 보낼
-- 것을 고른다"). When a delegated run submits its result the server pins it (the connector's
-- snapshot commit), checks the changed files against the risk path patterns and sends a review
-- request to a person for a risk, or for a sample of the rest; everything else stays 「사람
-- 미확인」. Names follow the data model's review_items, runs, inbox_items, approvals and
-- work_item_links. Columns the data model does not have are marked "beyond the data model".
-- Only adds tables, columns and indexes and widens one check; no existing row changes.

create table review_items (
	-- `rvw_<uuidv7>` (contract v1 "식별자").
	id text primary key,
	org_id text not null references organizations (id),
	project_id text references projects (id),
	work_item_id text references work_items (id),
	run_id text references runs (id),
	-- A directly opened session's file, after the fact (contract "위험 목록을 판정한다" 3). Not raised yet.
	session_id text references agent_sessions (id),
	-- Beyond the data model: who the request goes to (the work item's responsible person). The
	-- daily sample cap counts per recipient and day.
	recipient_user_id text not null,
	target_kind text not null check (target_kind in ('run_snapshot', 'session_file', 'unpinned')),
	-- run_snapshot: the snapshot commit. unpinned: SHA-256 of the run id and its reported paths.
	target_ref text not null,
	-- Where it came from: the run id (or the session id).
	source_key text not null,
	superseded_by text references review_items (id),
	reason text not null check (reason in ('risk_path', 'irreversible', 'deploy', 'ai_undecided', 'ai_disagreement',
	                                       'extra_billing', 'conflict', 'ai_raised', 'sample', 'owner_watch')),
	raised_by text not null check (raised_by in ('rule', 'ai', 'sampler', 'human')),
	-- The patterns and files that matched, or for a sample the draw and the rule's version.
	evidence jsonb not null default '{}',
	state text not null default 'open' check (state in ('open', 'approved', 'rejected', 'superseded')),
	-- Only a person's verdict is stored (data model constraint 6).
	decided_by text,
	decided_at timestamptz,
	note text,
	-- A pattern a person put forward as a risk path candidate when rejecting (contract "사람 검토
	-- 상태와 자동 검사 상태를 따로 남긴다" 4). Nothing adds it on its own.
	propose_risk_path text,
	-- Beyond the data model: what became of that candidate: open, added (to the list) or dismissed.
	proposal_state text check (proposal_state in ('open', 'added', 'dismissed')),
	proposal_decided_at timestamptz,
	created_at timestamptz not null default now(),
	check ((propose_risk_path is null) = (proposal_state is null))
);

-- One open request per source, target and reason (data model "review_items"); closed ones do not count.
create unique index review_items_one_open on review_items (org_id, source_key, target_kind, target_ref, reason)
	where state = 'open';

create index review_items_by_run on review_items (run_id, created_at);

create index review_items_by_work_item on review_items (work_item_id, created_at);

-- The daily sample cap: samples per recipient and day.
create index review_items_samples on review_items (recipient_user_id, created_at) where reason = 'sample';

create index review_items_open_proposals on review_items (project_id) where proposal_state = 'open';

alter table runs
	-- The connector's snapshot commit of the result (refs/zm-baton/snapshots/<run id>): the review target.
	add column result_snapshot_sha text,
	-- Set when the result is submitted and recomputed whenever its review requests change.
	add column human_review_state text check (human_review_state in ('required', 'sampled', 'reviewed', 'not_reviewed')),
	add column auto_check_state text check (auto_check_state in ('passed', 'failed', 'not_run')),
	-- Beyond the data model: how the result was judged: the matched patterns, the list they came
	-- from, the sample draw and rule version, whether the day's cap was reached.
	add column review_basis jsonb;

-- A review open longer than review_promote_after_hours (24) is raised to bucket 1 (contract "항목과 순서" 2).
alter table inbox_items add column promoted_at timestamptz;

create unique index inbox_items_one_open_review on inbox_items (source_id)
	where state = 'open' and kind in ('risk_review', 'sample_review');

-- A verdict on a review request is an approval like any other. Beyond the data model: the target
-- it was given on, e.g. `run_snapshot:<sha>`; `unpinned:` says the target was not pinned.
alter table approvals add column target_ref text;
alter table approvals drop constraint approvals_target_kind;
alter table approvals add constraint approvals_target_kind
	check (target_kind in ('decision', 'document_version', 'review_item'));

-- Data model "work_item_links": `fixes` ties the work item made when a sample of a done work item
-- was rejected to that work item (contract "반려하면 작업이 어떻게 되나" 3).
create table work_item_links (
	from_id text not null references work_items (id),
	to_id text not null references work_items (id),
	kind text not null check (kind in ('blocks', 'implements', 'tests', 'duplicates', 'fixes')),
	source text not null check (source in ('human', 'system', 'ai_inferred')),
	created_at timestamptz not null default now(),
	primary key (from_id, to_id, kind)
);

create index work_item_links_to on work_item_links (to_id);
