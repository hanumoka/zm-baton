-- Stage 1, second slice: work items a person moves through their stages, the projects they
-- belong to, and decision records. Work items and decisions can come from a Git repository
-- (zm-docs `registry/tasks.json` and `docs/governance/decisions/`). While Git is their source
-- of truth (G20.1) the server keeps a copy that only an import changes.
-- Names follow contract v1 ("작업") and the data model (organizations, projects, work_items,
-- decisions). Columns the data model does not have are marked "beyond the data model".

create table organizations (
	id text primary key,
	name text not null,
	-- Organisation settings (data model "organizations"). `workflow.skipped_stages` lists the
	-- stages this organisation does not use; the server passes through them (contract "단계").
	settings jsonb not null default '{}',
	created_at timestamptz not null default now()
);

-- Stage 1 has one organisation of one person (FN-0104), like the one person usr_owner.
-- A one-person organisation starts without the two review stages; a person can turn them on.
insert into organizations (id, name, settings)
values ('org_owner', 'owner', '{"workflow": {"skipped_stages": ["plan_review", "result_review"]}}');

create table projects (
	id text primary key,
	org_id text not null references organizations (id),
	-- Beyond the data model: the project's number where it came from, e.g. `PRJ-009` in zm-docs.
	external_ref text unique,
	name text not null,
	kind text,
	settings jsonb not null default '{}',
	created_at timestamptz not null default now(),
	updated_at timestamptz not null default now()
);

alter table work_items
	add column project_id text references projects (id),
	add column acceptance text,
	-- Beyond the data model from here on.
	-- Where the work item came from, e.g. `TSK-021` in zm-docs. Unique when present.
	add column external_ref text,
	-- `git`: the copy of a work item whose source of truth is a Git file; only an import changes it.
	add column source_of_truth text not null default 'product' check (source_of_truth in ('product', 'git')),
	-- `{"repo_key": "git:<root commit>", "path": "registry/tasks.json"}`. Never an absolute path.
	add column git_ref jsonb,
	add column next_action text,
	-- A document the work item points at (zm-docs `source`): a path from the repository root.
	add column source_ref text,
	-- What a person wrote for the wait reason `waiting_other` (zm-docs `blocker`).
	add column wait_note text,
	add column opened_on date,
	add column due_on date,
	add column closed_at timestamptz;

create unique index work_items_external_ref on work_items (external_ref) where external_ref is not null;

create index work_items_by_stage on work_items (responsible_user_id, stage, updated_at);

create table decisions (
	id text primary key,
	org_id text not null references organizations (id),
	-- Beyond the data model: `DEC-0106` in zm-docs.
	external_ref text unique,
	-- The question the decision answers. zm-docs keeps it in the front matter `title`.
	question text not null,
	-- zm-docs values (docs/governance/decisions/README.md).
	status text not null check (status in ('proposed', 'accepted', 'rejected', 'superseded')),
	-- Beyond the data model: the day it was decided.
	decided_on date,
	-- The body of the section headed "결정", when there is one.
	choice text,
	-- Beyond the data model: every second-level section as `[{"heading": ..., "body": ...}]`.
	-- Kept instead of separate options, rationale and review-condition columns, because the
	-- section names differ between documents (for example "판단 기준과 검토한 선택지").
	sections jsonb not null default '[]',
	supersedes_id text references decisions (id),
	superseded_by_id text references decisions (id),
	source_of_truth text not null default 'git' check (source_of_truth in ('product', 'git')),
	git_ref jsonb,
	-- SHA-256 of the imported file, so an unchanged file is not rewritten.
	body_sha256 text,
	created_at timestamptz not null default now(),
	updated_at timestamptz not null default now()
);

-- Beyond the data model: a zm-docs decision can name several projects.
create table decision_projects (
	decision_id text not null references decisions (id) on delete cascade,
	project_id text not null references projects (id),
	primary key (decision_id, project_id)
);

create index decisions_by_day on decisions (decided_on desc, external_ref desc);
