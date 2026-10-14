-- Stage 1, first-screen relays: a directly opened Claude Code session's permission requests and
-- multiple-choice questions reach the first screen, a person answers there, and the session's hook
-- takes the answer back to the executor (contract v1 "권한 요청과 선택형 질문을 첫 화면으로 중계한다",
-- "첫 화면 「나를 기다리는 것」" and "알림과 집중 모드"). Columns follow the data model's users and
-- inbox_items; those beyond it are marked.

-- The person's own settings (data model "users"). Stage 1 has one person, usr_owner.
create table users (
	id text primary key,
	display_name text not null,
	status text not null default 'active' check (status in ('active', 'inactive')),
	-- Focus mode: while on, only conflicts with my live work alert at once, and new permission
	-- relays go straight to the terminal (contract "알림과 집중 모드" 4).
	focus_on boolean not null default false,
	focus_changed_at timestamptz,
	digest_times text[] not null default '{09:00,17:00}',
	-- Pushed forward by the first screen while its tab is visible; a question is relayed only before it.
	first_screen_visible_until timestamptz,
	created_at timestamptz not null default now()
);

insert into users (id, display_name) values ('usr_owner', 'owner');

alter table inbox_items
	-- A new id for every hook call (contract relay 2.1). Unique when present.
	add column relay_id text unique,
	-- SHA-256 of the tool name and the whole tool input, serialised in a fixed order (relay 2.2).
	add column input_fingerprint text,
	-- The last server time at which an answer can be given and taken. Null: not relayed, so it is
	-- answered in the session's terminal.
	add column relay_deadline timestamptz,
	-- Beyond the data model: the local_seq the hook gave the relay, so a later `resumed` of the
	-- session closes it and an earlier one that arrives late does not.
	add column relay_seq bigint,
	-- Beyond the data model: why the relay did not start or how it ended. secret_suspected,
	-- too_large, focus_mode, not_visible, deadline, session_ended, resumed, person.
	add column relay_reason text,
	add column answer jsonb,
	add column answered_by text,
	add column answered_at timestamptz,
	add column delivered_at timestamptz;

-- The sweeper looks for relays whose deadline passed.
create index inbox_items_relay_deadline on inbox_items (relay_deadline)
	where state in ('open', 'answered') and relay_deadline is not null;
