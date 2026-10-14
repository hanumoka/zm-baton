-- Stage 1, third slice, third part: AI accounts (FN-0501), what a run used and its limits
-- (FN-0409, G39.4), and the provider's limit and extra-billing signals (contract v1 "질문과 과금
-- 신호", billing-signals). Names follow the data model's ai_accounts, runs and usage_signals.
-- Columns the data model does not have are marked "beyond the data model".
-- No secret is stored anywhere: an account says where the device keeps what the executor signs
-- in with (its Claude Code login, or the name of an environment variable), never a value.
-- Nothing is dropped and no existing row changes; one check constraint is widened.

create table ai_accounts (
	id text primary key,
	org_id text not null references organizations (id),
	-- The owner of a personal subscription; null for an organisation's account.
	owner_user_id text,
	provider text not null check (provider in ('anthropic')),
	auth_kind text not null check (auth_kind in ('personal_subscription', 'org_account', 'api_key')),
	-- Beyond the data model: the name a person reads, e.g. "이 PC의 Claude Code 로그인".
	name text not null check (char_length(name) between 1 and 100),
	-- Purpose and affiliation (DEC-0077 1). `affiliation` is beyond the data model.
	purpose text check (char_length(purpose) <= 300),
	affiliation text not null check (affiliation in ('personal', 'organization')),
	-- Beyond the data model: the data handling terms that come with the account (FN-0501).
	data_terms text check (char_length(data_terms) <= 500),
	-- Beyond the data model: where the device keeps what the executor signs in with.
	-- `device_login` is the official CLI's own login on the device; `env:ANTHROPIC_API_KEY` is that
	-- environment variable of the connector on the device. A name, never a value.
	auth_source text not null check (auth_source in ('device_login', 'env:ANTHROPIC_API_KEY')),
	-- Beyond the data model: the device that holds it; null means the device a run is handed to.
	device_id text references devices (id),
	central_allowed boolean not null default false,
	status text not null default 'ready' check (status in ('ready', 'needs_login', 'limit_reached', 'disabled')),
	-- Beyond the data model from here on: the usage limits (G39.4). Null means no limit.
	run_turn_limit integer check (run_turn_limit between 1 and 10000),
	run_cost_limit_usd numeric(10, 2) check (run_cost_limit_usd > 0),
	run_minute_limit integer check (run_minute_limit between 1 and 1440),
	daily_cost_limit_usd numeric(10, 2) check (daily_cost_limit_usd > 0),
	-- When the provider's own limit stopped the account (`limit_reached`), the time it resets.
	limit_resets_at timestamptz,
	-- A confirmed extra-billing signal stopped the account until a person lets it run again (G39.5).
	billing_stopped_at timestamptz,
	version bigint not null default 0,
	created_at timestamptz not null default now(),
	updated_at timestamptz not null default now()
);

-- The one person's Claude Code login on the device a run is handed to: what stage 1 used until now,
-- so handing work over keeps working without a first step. The limits start generous (docs/stage1/accounts.md).
insert into ai_accounts (id, org_id, owner_user_id, provider, auth_kind, name, purpose, affiliation, auth_source,
                         run_turn_limit, run_cost_limit_usd, run_minute_limit, daily_cost_limit_usd)
values ('acc_owner_login', 'org_owner', 'usr_owner', 'anthropic', 'personal_subscription', '이 PC의 Claude Code 로그인',
        '소유자 본인의 작업', 'personal', 'device_login', 200, 30.00, 60, 200.00);

alter table delegations
	add constraint delegations_account_id_fkey foreign key (account_id) references ai_accounts (id);

alter table runs
	add column account_id text references ai_accounts (id),
	-- What the adapter actually used (DEC-0095 5): it removes the other ways from the executor's environment.
	add column auth_method text check (auth_method in ('subscription_login', 'api_key', 'cloud_account')),
	add column billing_location text
		check (billing_location in ('subscription_limit', 'extra_usage', 'separate_credit', 'api', 'unknown')),
	add column billing_evidence jsonb,
	add column billing_detection text check (billing_detection in ('not_detected', 'detected', 'unavailable')),
	-- Beyond the data model: the limits the run got at its claim (`max_turns`, `max_budget_usd`, `max_minutes`, `budget_from`).
	add column limits jsonb,
	-- Beyond the data model: what the executor said the run used (cost estimate, tokens, turns, time), and when.
	add column usage jsonb,
	add column usage_at timestamptz,
	-- Beyond the data model: the limit or signal that stopped the run, if one did.
	add column stopped_by text check (stopped_by in ('run_turn_limit', 'run_cost_limit', 'run_minute_limit', 'daily_cost_limit',
	                                                  'usage_burst', 'provider_limit', 'extra_billing'));

create index runs_usage_by_account on runs (account_id, usage_at) where usage_at is not null;

-- The data model's usage_signals: each usage report and limit signal an executor gave, by account.
create table usage_signals (
	id text primary key,
	run_id text not null references runs (id),
	account_id text references ai_accounts (id),
	kind text not null check (kind in ('tokens', 'limit_ratio', 'cost_estimate', 'extra_usage_detected', 'unknown')),
	payload jsonb not null,
	received_at timestamptz not null default now()
);

create index usage_signals_by_account on usage_signals (account_id, received_at);
create index usage_signals_by_run on usage_signals (run_id, received_at);

-- Beyond the data model: a notice that a limit or a billing signal stopped work (contract "첫 화면"
-- 5번 묶음 「나머지 알림」). The data model's notices hold only conflicts and contradictions.
alter table notices drop constraint notices_kind_check;
alter table notices add constraint notices_kind_check
	check (kind in ('work_conflict', 'doc_contradiction', 'usage_alert'));
