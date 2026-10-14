-- Stage 1, third slice: a person hands a work item to an AI. The server keeps the delegation
-- (contract v1 "위임"), builds the run's instruction from the work item, and records where the
-- run worked: the repository, base commit and the run's own branch ("실행마다 별도 작업 트리" 8).
-- Reports gain the kind `git_changes` ("보고 한 건의 모양", protocol_version 2).
-- Names follow the data model's delegations and runs. Columns the data model does not have are
-- marked "beyond the data model". Nothing is dropped; one check constraint is widened.

-- Beyond the data model: the repositories a device's local connector may run in. The device keeps
-- the absolute path; the server gets the repository key, a display name and the cleaned remote.
-- `removed_at` keeps the row when a person removes the repository from the device.
create table device_repositories (
	device_id text not null references devices (id),
	repo_key text not null,
	name text not null,
	remote text,
	created_at timestamptz not null default now(),
	updated_at timestamptz not null default now(),
	removed_at timestamptz,
	primary key (device_id, repo_key)
);

create table delegations (
	id text primary key,
	work_item_id text not null references work_items (id),
	granted_by_user_id text not null,
	grantee_kind text not null check (grantee_kind in ('manager', 'shared_ai')),
	grantee_id text not null,
	device_id text references devices (id),
	-- AI account reference (data model "ai_accounts"). Null until AI accounts can be registered.
	account_id text,
	-- {"executor", "repo_key", "base_branch", "allowed_tools"}. Never an absolute path.
	scope jsonb not null,
	call_path jsonb not null default '[]',
	expires_at timestamptz not null,
	revoked_at timestamptz,
	created_at timestamptz not null default now()
);

create index delegations_by_work_item on delegations (work_item_id, created_at);

alter table runs
	add column delegation_id text references delegations (id),
	add column claimed_at timestamptz,
	-- {"repo_key", "base_commit", "branch", "base_ref"}: the run's own worktree. Never an absolute path.
	add column workspace jsonb,
	add column last_activity_at timestamptz,
	-- Beyond the data model: the instruction the server built from the work item for this run.
	add column instruction text;

-- The kinds of contract v1 protocol_version 2: the seven activities, lifecycle and git_changes.
alter table run_events drop constraint run_events_kind_check;
alter table run_events add constraint run_events_kind_check
	check (kind in ('thought', 'action', 'action_result', 'answer', 'question', 'error', 'usage', 'lifecycle', 'git_changes'));
