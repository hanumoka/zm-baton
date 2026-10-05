-- 1단계 설치·호환 시험용 핵심 테이블 셋.
-- 이름과 상태 값은 협업 계약 v1, 열은 데이터 모델의 work_items·runs·run_events를 따른다.
-- 시험 범위 밖의 열(업무 기준, 위임, 과금 위치 등)은 1단계 작업에서 더한다.

create table work_items (
	id text primary key,
	title text not null,
	stage text not null
		check (stage in ('draft', 'plan_review', 'ready', 'in_progress', 'result_review', 'done', 'cancelled')),
	wait_reasons text[] not null default '{}',
	responsible_user_id text not null,
	executor_kind text check (executor_kind in ('human', 'manager', 'shared_ai')),
	executor_id text,
	generation bigint not null default 0,
	version bigint not null default 0,
	created_at timestamptz not null default now(),
	updated_at timestamptz not null default now()
);

create table runs (
	id text primary key,
	work_item_id text not null references work_items (id),
	generation bigint,
	state text not null
		check (state in ('requested', 'claimed', 'running', 'stopping', 'ended')),
	end_reason text
		check (end_reason in ('submitted', 'failed', 'cancelled', 'replaced', 'lost')),
	executor text not null,
	executor_session_id text,
	device_id text,
	comparison_group text,
	lease_expires_at timestamptz,
	last_event_at timestamptz,
	late_reports boolean not null default false,
	created_at timestamptz not null default now(),
	started_at timestamptz,
	ended_at timestamptz,
	check ((state = 'ended') = (end_reason is not null))
);

-- 한 작업에 살아 있는 실행 시도는 하나다. 비교 실행(comparison_group)만 예외다.
create unique index runs_one_live_per_work_item
	on runs (work_item_id)
	where state in ('requested', 'claimed', 'running', 'stopping')
		and comparison_group is null;

create table run_events (
	event_id text primary key,
	run_id text not null references runs (id),
	generation bigint not null,
	seq bigint not null,
	kind text not null
		check (kind in ('thought', 'action', 'action_result', 'answer', 'question', 'error', 'usage', 'lifecycle')),
	payload jsonb not null,
	occurred_at timestamptz,
	received_at timestamptz not null default now(),
	unique (run_id, seq)
);
