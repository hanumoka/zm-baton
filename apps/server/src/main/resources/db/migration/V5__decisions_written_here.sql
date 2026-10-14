-- Stage 1: decision records written in zm-baton (FN-0604, G46.3) and the approvals that adopt or
-- reject them (data model "approvals"). A decision copied from zm-docs keeps `source_of_truth = git`
-- and stays read-only here; one made here has `source_of_truth = product` and is proposed, edited
-- while proposed, accepted or rejected by its approver, and replaced by a later decision.
-- Only adds columns, tables and a sequence; existing rows keep their values (`version` 0,
-- empty options and roles).

alter table decisions
	-- Beyond the data model: the number people call a decision made here, shown as `DCS-0001`.
	-- Copies from Git keep their own number in `external_ref` (`DEC-0106`) and have none here.
	add column number integer,
	-- Beyond the data model: the version a person saw; every change here checks and raises it.
	add column version bigint not null default 0,
	-- FN-0604 fields for decisions made here. A Git copy keeps its text in `sections` instead,
	-- because zm-docs names its sections differently from file to file (V3).
	add column context text,
	-- `[{"key": "A", "title": "...", "body": "..."}]`, in the order they were written.
	add column options jsonb not null default '[]',
	-- The key of the option chosen, when the decision chose one of `options`.
	add column chosen_option text,
	add column rationale text,
	add column review_conditions text,
	-- Decision roles (DACI): `{"driver": "...", "approver": "...", "contributors": [...], "informed": [...]}`.
	add column roles jsonb not null default '{}';

-- Numbers are never reused; a failed insert can leave a gap, as a dropped zm-docs number does.
create sequence decision_numbers;

create unique index decisions_number on decisions (org_id, number) where number is not null;

alter table decisions
	add constraint decisions_product_has_number check (source_of_truth = 'git' or number is not null);

-- Beyond the data model: the work a decision made here is about, as a record rather than a number
-- written in the work item's text.
create table decision_work_items (
	decision_id text not null references decisions (id) on delete cascade,
	work_item_id text not null references work_items (id),
	created_at timestamptz not null default now(),
	primary key (decision_id, work_item_id)
);

create index decision_work_items_by_work on decision_work_items (work_item_id);

-- Data model "approvals". One row per verdict; nothing updates or deletes a row.
create table approvals (
	-- `apv_<uuidv7>`. Contract v1 has no prefix for approvals; this one keeps them readable.
	id text primary key,
	target_kind text not null,
	target_id text not null,
	-- The target's version the verdict was given on.
	target_version bigint not null,
	-- Which rule chose the approver, e.g. `stage1.owner_approves`.
	policy_ref text not null,
	approver_kind text not null check (approver_kind in ('human', 'ai_reviewer')),
	approver_id text not null,
	-- An AI reviewer's session (DEC-0064). Null for a person.
	session_ref text,
	decision text not null check (decision in ('approved', 'rejected', 'revoked')),
	-- Beyond the data model: why, in the approver's words.
	note text,
	decided_at timestamptz not null default now(),
	created_at timestamptz not null default now(),
	constraint approvals_target_kind check (target_kind in ('decision'))
);

create index approvals_by_target on approvals (target_kind, target_id, decided_at);
