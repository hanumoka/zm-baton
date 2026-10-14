-- Stage 1, third slice, second part: stopping, resuming and steering a delegated run, and the
-- server ending a run whose connector stopped renewing its lease (contract v1 "실행 권리 청구"
-- 1·5·6, "실행이 끊겼을 때", "실행마다 별도 작업 트리" 6). Names follow the data model's runs.
-- Columns and tables the data model does not have are marked "beyond the data model".
-- Nothing is dropped and no existing row changes.

-- A run made for an `in_progress` work item says why it continues and which run it continues
-- (contract "실행 권리 청구" 1-2, data model runs.resumed_from_run_id and runs.continue_reason).
alter table runs
	add column resumed_from_run_id text references runs (id),
	add column continue_reason text
		check (continue_reason in ('answer_arrived', 'result_rejected', 'resume_after_cut', 'replacement'));

-- Beyond the data model: a short instruction a person sends to a running executor (contract
-- "어댑터가 알려야 하는 능력" 실행 중 입력; question path "실행 중 입력은 시험을 통과한 실행기에만
-- 쓴다"). `pending` until the connector takes it, `sent` once written to the executor, `delivered`
-- once the executor took it into its conversation, `undelivered` when the run ended first.
create table run_inputs (
	id text primary key,
	run_id text not null references runs (id),
	body text not null check (char_length(body) between 1 and 2000),
	state text not null default 'pending' check (state in ('pending', 'sent', 'delivered', 'undelivered')),
	created_by text not null,
	created_at timestamptz not null default now(),
	sent_at timestamptz,
	delivered_at timestamptz
);

create index run_inputs_by_run on run_inputs (run_id, created_at);

-- The sweep that ends runs whose lease lapsed reads only live runs.
create index runs_live_by_lease on runs (lease_expires_at) where state in ('claimed', 'running', 'stopping');
