-- Stage 1: questions and renegotiation (FN-0210; contract v1 "질문과 과금 신호", question path).
-- A question about a work item goes to the person responsible for the source of the question
-- (the work item's owner, a document's owner, the approver); while it waits the work item carries
-- `waiting_answer` and the person's first screen shows it in bucket 2. The answer, the document
-- change, decision or approval it led to and how it reached the executor are kept with it. No
-- answer is never an answer: past its due time it is raised again, never approved.
-- Names follow the data model's questions; columns it does not have are marked "beyond the data
-- model". Only adds a table and indexes; no existing row changes.

create table questions (
	-- `qst_<uuidv7>` (contract v1 "식별자").
	id text primary key,
	org_id text not null references organizations (id),
	work_item_id text not null references work_items (id),
	-- The step that stopped, in the asker's words.
	blocked_step text,
	-- The run that asked, or the run a person recorded the question from; null for a person's own question.
	run_id text references runs (id),
	executor_session_id text,
	-- tool, permission_stop, readiness_check (data model); beyond the data model: run_answer (a person
	-- recorded what a run's final answer asked) and person (a person asked).
	origin text not null check (origin in ('tool', 'permission_stop', 'readiness_check', 'run_answer', 'person')),
	-- 해석 / 실행 문제 / 정책 예외 / 사실 확인 / 승인 요청 (question path "질문 기록에 남길 칸").
	kind text not null check (kind in ('interpretation', 'execution', 'policy_exception', 'fact', 'approval')),
	blocking boolean not null default true,
	independent_steps text,
	body text not null check (char_length(body) between 1 and 4000),
	options jsonb not null default '[]',
	context_refs jsonb not null default '[]',
	original_action jsonb,
	visibility text not null default 'org',
	asked_by_kind text not null check (asked_by_kind in ('person', 'manager', 'shared_ai')),
	asked_by_id text not null,
	routed_to_kind text not null check (routed_to_kind in ('person', 'manager', 'shared_ai')),
	routed_to_id text not null,
	-- Beyond the data model: why it went there, e.g. `work_owner`, `document_owner`, `approver`.
	routed_role text not null,
	-- Each routing and each reminder, oldest first.
	routing_history jsonb not null default '[]',
	due_at timestamptz not null,
	answer text check (answer is null or char_length(answer) between 1 and 4000),
	answered_by_kind text check (answered_by_kind in ('person', 'manager', 'shared_ai')),
	answered_by_id text,
	evidence_refs jsonb not null default '[]',
	answered_at timestamptz,
	-- The document change the answer led to: a document version (`dvr_`) or document (`doc_`).
	doc_change_ref text,
	-- Beyond the data model: the answer needs a document change before the question is settled.
	needs_doc_change boolean not null default false,
	-- Beyond the data model: the decision (`dcs_`) or approval (`apv_`) the answer led to.
	decision_ref text,
	approval_ref text,
	delivery text check (delivery in ('live_input', 'resume', 'new_session')),
	delivered_at timestamptz,
	-- Beyond the data model: the continuing run that carries the answer to the executor.
	delivery_run_id text references runs (id),
	state text not null default 'awaiting_answer'
		check (state in ('open', 'awaiting_answer', 'answered', 'awaiting_doc_change', 'resolved')),
	close_reason text check (close_reason in ('answered', 'withdrawn', 'work_cancelled')),
	-- The run and the executor's tool call id, when an executor asked through a tool.
	idempotency_key text unique,
	-- Beyond the data model: how often it was raised again after its due time, and when last.
	remind_count integer not null default 0,
	reminded_at timestamptz,
	created_at timestamptz not null default now(),
	check ((state = 'resolved') = (close_reason is not null))
);

create index questions_by_work_item on questions (work_item_id, created_at);

create index questions_waiting on questions (due_at) where state = 'awaiting_answer';

create index questions_by_run on questions (run_id) where run_id is not null;

-- One open first-screen item per question.
create unique index inbox_items_one_open_question on inbox_items (source_id)
	where state = 'open' and kind = 'run_question';
