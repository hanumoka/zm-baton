-- Stage 1, first usable slice: directly opened AI sessions register with the server, report what
-- they work on and which files they touch, and the server raises file conflicts and waiting
-- sessions on the first screen ("나를 기다리는 것").
-- Names and states follow contract v1 (protocol_version 2) sections "직접 연 AI 세션을 서버에
-- 등록한다", "충돌을 찾아 관계자에게 알린다" and "첫 화면"; columns follow the data model's
-- devices, agent_sessions, session_events, notices, inbox_items and audit_log.
-- Not here yet: organisations, projects, managers and person authentication. Until person
-- authentication exists the server listens on loopback only and the one person is usr_owner.

create table devices (
	id text primary key,
	user_id text not null,
	name text not null,
	os text,
	created_at timestamptz not null default now(),
	last_seen_at timestamptz,
	revoked_at timestamptz
);

create table agent_sessions (
	id text primary key,
	user_id text not null,
	device_id text not null references devices (id),
	executor text not null check (executor in ('claude_code', 'codex')),
	executor_version text,
	executor_session_id text not null,
	-- Remote URL without credentials, path from the repository root and branch. Never an absolute path.
	repository jsonb,
	-- Identifies the repository across clones and worktrees: "git:<root commit>". Conflicts match on it.
	repo_key text,
	current_work text check (char_length(current_work) <= 300),
	current_work_refs jsonb,
	current_work_at timestamptz,
	-- local_seq of the current_work event applied last: an older event that arrives later is ignored.
	current_work_seq bigint not null default 0,
	share_current_work boolean not null default true,
	-- Only the SHA-256 of the session token is kept, never the token.
	token_hash text not null unique,
	token_expires_at timestamptz not null,
	created_at timestamptz not null default now(),
	last_seen_at timestamptz not null default now(),
	ended_at timestamptz,
	deregistered_at timestamptz,
	max_resumed_seq bigint not null default 0
);

-- One live registration per executor session on a device; resuming that session reuses it.
create unique index agent_sessions_one_live
	on agent_sessions (device_id, executor, executor_session_id)
	where ended_at is null and deregistered_at is null;

create table session_events (
	event_id text primary key,
	session_id text not null references agent_sessions (id),
	local_seq bigint not null,
	kind text not null
		check (kind in ('registered', 'current_work', 'waiting', 'file_touch', 'resumed', 'ended', 'deregistered')),
	payload jsonb not null,
	received_at timestamptz not null default now()
);

create index session_events_by_session on session_events (session_id, local_seq);

-- Conflict check: which sessions touched this file of this repository.
create index session_events_file_touch
	on session_events ((payload ->> 'repo_key'), (payload ->> 'path'), session_id)
	where kind = 'file_touch';

create table notices (
	id text primary key,
	kind text not null check (kind in ('work_conflict', 'doc_contradiction')),
	evidence jsonb not null,
	dedupe_key text not null unique,
	recipients text[] not null,
	status text not null default 'open'
		check (status in ('open', 'acknowledged', 'not_a_conflict', 'resolved')),
	status_by text,
	status_at timestamptz,
	status_note text,
	repeat_count integer not null default 0,
	created_at timestamptz not null default now(),
	last_seen_at timestamptz not null default now()
);

create table inbox_items (
	id text primary key,
	user_id text not null,
	bucket smallint not null check (bucket between 1 and 5),
	kind text not null
		check (kind in ('permission_request', 'run_question', 'session_question', 'session_waiting', 'notice',
		                'risk_review', 'sample_review')),
	source_kind text not null check (source_kind in ('ses', 'qst', 'ntc', 'rvw')),
	source_id text not null,
	summary text not null,
	detail jsonb,
	-- A directly opened session's wait: its kind and the local_seq of its first and newest report
	-- after the session's newest resume. The server recomputes them from session_events each time.
	wait_kind text,
	wait_first_seq bigint,
	wait_seq bigint,
	repeat_count integer not null default 0,
	waiting_since timestamptz not null default now(),
	notify text not null check (notify in ('immediate', 'digest')),
	state text not null default 'open'
		check (state in ('open', 'answered', 'delivered', 'moved_to_terminal', 'undelivered', 'closed')),
	closed_at timestamptz,
	created_at timestamptz not null default now()
);

-- The same kind of wait in one session is one open item; a repeat only counts up.
create unique index inbox_items_one_open_wait
	on inbox_items (source_id, wait_kind)
	where state = 'open' and kind = 'session_waiting';

create unique index inbox_items_one_open_notice
	on inbox_items (source_id)
	where state = 'open' and kind = 'notice';

create index inbox_items_open on inbox_items (user_id, bucket, waiting_since) where state = 'open';

create table audit_log (
	id text primary key,
	actor_kind text not null check (actor_kind in ('person', 'session', 'system')),
	actor_id text not null,
	on_behalf_of_user_id text,
	action text not null,
	target_kind text,
	target_id text,
	details jsonb not null default '{}',
	at timestamptz not null default now()
);
