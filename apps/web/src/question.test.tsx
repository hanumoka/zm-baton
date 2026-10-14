import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { InboxDetail } from './inbox/InboxDetail';
import { kindOf, rowTitle } from './inbox/inboxView';
import { QuestionBody } from './question/QuestionDetail';
import { answerBody, askBody, askerLabel, deliveryLabel, kindLabel, lastAnswer, roleLabel, stateLabel } from './question/questionModel';
import { AskForm, QuestionList } from './question/Questions';
import type { Question, QuestionDetail } from './questionsApi';
import type { InboxItem } from './sessionsApi';
import { HandoffForm, RunList } from './work/Handoff';
import type { ItemRun, WorkItemDetail } from './workApi';
import { historyLine } from './workModel';

/* Questions and renegotiation (FN-0210) on the first screen, in the work detail and in the run list. */

const T0 = Date.parse('2026-10-14T03:00:00Z');
const iso = (minutes: number) => new Date(T0 + minutes * 60_000).toISOString();

const question = (over: Partial<Question> = {}): Question => ({
  id: 'qst_1',
  work_item_id: 'wrk_a',
  run_id: null,
  origin: 'person',
  kind: 'interpretation',
  blocking: true,
  blocked_step: null,
  independent_steps: null,
  body: '보존 기간은 30일인가 90일인가?',
  options: [],
  asked_by_kind: 'person',
  asked_by_id: 'usr_owner',
  routed_to_kind: 'person',
  routed_to_id: 'usr_owner',
  routed_role: 'work_owner',
  due_at: iso(60 * 23),
  overdue: false,
  remind_count: 0,
  answer: null,
  answered_by_kind: null,
  answered_by_id: null,
  answered_at: null,
  needs_doc_change: false,
  doc_change_ref: null,
  decision_ref: null,
  approval_ref: null,
  evidence_refs: [],
  delivery: null,
  delivered_at: null,
  delivery_run_id: null,
  state: 'awaiting_answer',
  close_reason: null,
  created_at: iso(-60),
  inbox_item_id: 'inb_q',
  ...over,
});

const item = (over: Partial<InboxItem> = {}): InboxItem => ({
  id: 'inb_q',
  bucket: 2,
  kind: 'run_question',
  source_kind: 'qst',
  source_id: 'qst_1',
  summary: '질문 작업: 보존 기간은 30일인가 90일인가?',
  detail: { work_item_id: 'wrk_a', title: '질문 작업', body: '보존 기간은 30일인가 90일인가?', kind: 'interpretation', blocking: true, from_run: false },
  wait_kind: null,
  repeat_count: 0,
  waiting_since: iso(-60),
  notify: 'immediate',
  ...over,
});

const detail = (q: Question = question()): QuestionDetail => ({
  question: q,
  work_item: { id: 'wrk_a', title: '질문 작업', external_ref: null, stage: 'in_progress', version: 4, project: null },
  routing_history: [{ at: iso(-60), event: 'routed', to: 'usr_owner', role: 'work_owner' }],
  can_answer: q.state === 'awaiting_answer',
  can_confirm_doc_change: q.state === 'awaiting_doc_change',
  can_withdraw: q.state !== 'resolved',
});

const body = (d: QuestionDetail, i: InboxItem = item()) =>
  renderToStaticMarkup(<QuestionBody detail={d} item={i} now={T0} onDone={() => {}} onError={() => {}} />);

describe('words', () => {
  it('names kinds, roles, askers, states and how an answer reached the executor', () => {
    expect(kindLabel('approval')).toBe('승인 요청');
    expect(roleLabel('document_owner')).toBe('문서 소유자');
    expect(askerLabel(question())).toBe('usr_owner');
    expect(askerLabel(question({ asked_by_kind: 'manager', run_id: 'run_1' }))).toBe('AI 실행');
    expect(stateLabel(question())).toBe('답변 대기');
    expect(stateLabel(question({ state: 'answered', run_id: 'run_1' }))).toBe('답함, 다음 실행에 넣기 전');
    expect(stateLabel(question({ state: 'awaiting_doc_change' }))).toBe('답함, 문서 반영 대기');
    expect(stateLabel(question({ state: 'resolved', close_reason: 'withdrawn' }))).toBe('거둠');
    expect(stateLabel(question({ state: 'resolved', close_reason: 'work_cancelled' }))).toBe('작업 취소로 닫힘');
    expect(stateLabel(question({ state: 'resolved', close_reason: 'answered' }))).toBe('해결');
    expect(deliveryLabel(question({ delivery: 'resume' }))).toBe('같은 세션을 이어 답을 넣었다');
    expect(deliveryLabel(question())).toBeNull();
  });

  it('sends a question or an answer only with words, and record numbers only when written', () => {
    expect(askBody('  ', 'fact', true)).toBeNull();
    expect(askBody(' 몇 일? ', 'fact', false)).toEqual({ body: '몇 일?', kind: 'fact', blocking: false, asked_by: 'person' });
    expect(askBody('어느 쪽?', 'interpretation', true, 'run_1')).toEqual({ body: '어느 쪽?', kind: 'interpretation', blocking: true, asked_by: 'run', run_id: 'run_1' });
    expect(answerBody('', false)).toBeNull();
    expect(answerBody('90일', false, { decision: ' dcs_1 ', doc: '' })).toEqual({ answer: '90일', decision_ref: 'dcs_1' });
    expect(answerBody('바꾼다', true)).toEqual({ answer: '바꾼다', needs_doc_change: true });
    expect(lastAnswer([{ kind: 'answer', payload: { text: '첫 답' } }, { kind: 'action', payload: {} }, { kind: 'answer', payload: { text: '마지막 답' } }])).toBe('마지막 답');
    expect(lastAnswer([])).toBe('');
  });

  it('writes question steps into the work item history', () => {
    const h = (action: string, details: Record<string, unknown>) => historyLine({ action, actor_kind: 'person', actor_id: 'usr_owner', details, at: iso(0) });
    expect(h('question.asked', { asked_by: 'run', body: '어느 쪽?' })).toBe('AI 실행의 질문을 남김 — 어느 쪽?');
    expect(h('question.answered', { answer: '왼쪽' })).toBe('질문에 답함 — 왼쪽');
    expect(h('question.closed_with_work', { count: 2 })).toBe('작업 취소로 질문 2개를 닫음');
  });
});

describe('the first screen', () => {
  it('shows a question in bucket 2 by what it asks, and loads it in its own detail', () => {
    expect(kindOf(item())).toEqual({ mark: 'ai', label: '질문' });
    expect(kindOf(item({ detail: { from_run: true } }))).toEqual({ mark: 'ai', label: '실행의 질문' });
    expect(rowTitle(item(), null)).toBe('보존 기간은 30일인가 90일인가?');
    const html = renderToStaticMarkup(<InboxDetail item={item()} sessions={[]} now={T0} onJudged={() => {}} onError={() => {}} />);
    expect(html).toContain('skel-list');
  });

  it('says what is asked, why it came to me, when it is due, and offers the answer', () => {
    const html = body(detail());
    expect(html).toContain('사람이 남긴 질문에 답한다');
    expect(html).toContain('보존 기간은 30일인가 90일인가?');
    expect(html).toContain('「해석」 질문이라 작업 책임자에게 왔다(질문한 쪽: usr_owner)');
    expect(html).toContain('기한이 지나도 답이나 승인으로 치지 않고 다시 알린다');
    expect(html).toContain('답이 있어야 다음으로 간다');
    expect(html).toMatch(/<button type="submit" class="btn btn-primary" disabled="">답 보내기<\/button>/);
    expect(html).toContain('질문 거두기');
    expect(html).toContain('이 답 때문에 요구·정책·계약 문서를 바꿔야 한다');
    expect(html).toContain('href="#work?item=wrk_a"');
    expect(html).toContain('usr_owner에게 보냄(작업 책임자)');
  });

  it('marks an overdue question and a run question, and shows a settled one without the form', () => {
    const late = body(detail(question({ overdue: false, remind_count: 2, asked_by_kind: 'manager', run_id: 'run_1' })));
    expect(late).toContain('AI 실행이 남긴 질문에 답한다');
    expect(late).toContain('기한 지남, 다시 알림 2회');
    expect(late).toContain('답한 뒤 작업 상세에서 「이어서 맡기기」를 누르면 답이 다음 실행의 지시에 들어간다');
    const settled = body(detail(question({ state: 'resolved', close_reason: 'answered', answer: '90일' })));
    expect(settled).not.toContain('답 보내기');
    expect(settled).toContain('이미 해결 상태다');
  });
});

describe('the work detail', () => {
  it('lists questions with who asks, who answers, where each stands and what to do next', () => {
    const html = renderToStaticMarkup(
      <QuestionList
        questions={[
          question(),
          question({ id: 'qst_2', state: 'answered', run_id: 'run_1', asked_by_kind: 'manager', answer: '90일이다', inbox_item_id: null, decision_ref: 'dcs_9' }),
          question({ id: 'qst_3', state: 'awaiting_doc_change', answer: '바꾼다', inbox_item_id: null }),
          question({ id: 'qst_4', state: 'resolved', close_reason: 'answered', delivery: 'new_session', inbox_item_id: null }),
        ]}
        onDocChange={async () => {}}
      />,
    );
    expect(html).toContain('<strong class="is-wait">답변 대기</strong>');
    expect(html).toContain('묻는 쪽 usr_owner, 답하는 쪽 작업 책임자 <code>usr_owner</code>');
    expect(html).toContain('href="#inbox?item=inb_q"');
    expect(html).toContain('묻는 쪽 AI 실행');
    expect(html).toContain('「이어서 맡기기」를 누르면 이 답이 다음 실행의 지시에 들어간다');
    expect(html).toContain('<code>dcs_9</code>');
    expect(html).toContain('문서 반영 기록');
    expect(html).toContain('새 세션의 지시에 답을 넣었다');
    expect(renderToStaticMarkup(<QuestionList questions={[]} onDocChange={async () => {}} />)).toContain('아직 남은 질문이 없다');
  });

  const work = (over: Partial<WorkItemDetail> = {}): WorkItemDetail => ({
    item: {
      id: 'wrk_a', title: '질문 작업', stage: 'in_progress', wait_reasons: [], generation: 1, created_at: iso(0), version: 4, external_ref: null,
      project: null, source_of_truth: 'product', executor_kind: 'manager', next_action: null, wait_note: null, opened_on: null, due_on: null,
      closed_at: null, updated_at: iso(0),
    },
    responsible_user_id: 'usr_owner', executor_id: 'mgr_owner', acceptance: null, source_ref: null, git_ref: null, live_run: false,
    read_only_reason: null, moves: [], history: [],
    delegation: { allowed: true, reason: null, mode: 'resume', needs_note: false, answers_ready: 2,
      resume_from: { run_id: 'run_1', end_reason: 'submitted', device_id: 'dev_a', repo_key: 'git:abc', base_branch: 'main', allowed_tools: ['Read'] } },
    question: { allowed: true, reason: null },
    ...over,
  });

  it('offers a new question, or says why not with the server reason', () => {
    expect(renderToStaticMarkup(<AskForm detail={work()} onAsk={async () => {}} />)).toMatch(/<button type="button" class="btn">.*질문 남기기<\/button>/);
    const locked = renderToStaticMarkup(<AskForm detail={work({ question: { allowed: false, reason: '완료된 작업에는 질문을 남기지 않는다' } })} onAsk={async () => {}} />);
    expect(locked).toContain('disabled=""');
    expect(locked).toContain('완료된 작업에는 질문을 남기지 않는다');
  });

  it('says the answers go with the next run when continuing', () => {
    const html = renderToStaticMarkup(<HandoffForm detail={work()} repos={[]} onDelegate={async () => {}} defaultOpen />);
    expect(html).toContain('답이 온 질문 2개가 이 실행의 지시에 들어간다');
    const closed = renderToStaticMarkup(<HandoffForm detail={work()} repos={[]} onDelegate={async () => {}} />);
    expect(closed).toContain('답을 넣어 이어서 맡기기');
  });

  const run: ItemRun = {
    id: 'run_1', work_item_id: 'wrk_a', state: 'ended', end_reason: 'submitted', executor: 'claude_code', generation: 1, device_id: 'dev_a',
    late_reports: false, created_at: iso(-10), last_event_at: iso(-5), claimed_at: iso(-10), started_at: iso(-10), ended_at: iso(-5),
    last_activity_at: iso(-5), workspace: null, git_changes: null, instruction: null,
    delegation: { id: 'dlg_1', device_id: 'dev_a', device_name: 'pc', repo_key: 'git:abc', repo_name: 'demo', base_branch: 'main',
      allowed_tools: ['Read'], expires_at: iso(60), revoked_at: null },
  };

  it('lets a person record what an ended run asked, only while the work item takes questions', () => {
    const list = (onAsk?: () => Promise<void>) =>
      renderToStaticMarkup(<RunList runs={[run]} events={[]} now={T0} outcomeUnknown={false} problem={null} onWithdraw={async () => {}} onAsk={onAsk} />);
    expect(list(async () => {})).toContain('질문으로 남기기');
    expect(list()).not.toContain('질문으로 남기기');
  });
});
