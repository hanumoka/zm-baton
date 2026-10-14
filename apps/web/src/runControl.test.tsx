import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { RunEvent } from './api';
import { HandoffForm, RunList } from './work/Handoff';
import type { ItemRun, Repository, WorkItem, WorkItemDetail } from './workApi';
import { historyLine } from './workModel';

/* Stopping, steering and continuing a run (FN-0410) and the readiness check (FN-0406) in the run list. */

const item = (over: Partial<WorkItem> = {}): WorkItem => ({
  id: 'wrk_a',
  title: '작업 하나',
  stage: 'in_progress',
  wait_reasons: [],
  generation: 1,
  created_at: '2026-10-14T00:00:00Z',
  version: 5,
  external_ref: null,
  project: null,
  source_of_truth: 'product',
  executor_kind: 'manager',
  next_action: null,
  wait_note: null,
  opened_on: '2026-10-14',
  due_on: null,
  closed_at: null,
  updated_at: '2026-10-14T01:00:00Z',
  ...over,
});

const detail = (delegation: WorkItemDetail['delegation'], itemOver: Partial<WorkItem> = {}): WorkItemDetail => ({
  item: item(itemOver),
  responsible_user_id: 'usr_owner',
  executor_id: 'mgr_owner',
  acceptance: null,
  source_ref: null,
  git_ref: null,
  live_run: false,
  read_only_reason: null,
  moves: [],
  history: [],
  delegation,
});

const repos: Repository[] = [
  { device_id: 'dev_a', device_name: 'home-pc', repo_key: 'git:abc', name: 'zm-baton', remote: null, updated_at: '2026-10-14T00:00:00Z' },
  { device_id: 'dev_b', device_name: 'work-pc', repo_key: 'git:abc', name: 'zm-baton', remote: null, updated_at: '2026-10-14T00:00:00Z' },
];

const previous = { run_id: 'run_prev', end_reason: 'cancelled', device_id: 'dev_b', repo_key: 'git:abc', base_branch: 'main', allowed_tools: ['Read', 'Glob', 'Grep', 'Edit', 'Write'] };

const run = (over: Partial<ItemRun> = {}): ItemRun => ({
  id: 'run_1',
  work_item_id: 'wrk_a',
  state: 'running',
  end_reason: null,
  executor: 'claude_code',
  generation: 2,
  device_id: 'dev_a',
  late_reports: false,
  created_at: '2026-10-14T02:00:00Z',
  last_event_at: '2026-10-14T02:05:00Z',
  claimed_at: '2026-10-14T02:00:01Z',
  started_at: '2026-10-14T02:00:02Z',
  ended_at: null,
  last_activity_at: '2026-10-14T02:05:00Z',
  delegation: {
    id: 'dlg_1',
    device_id: 'dev_a',
    device_name: 'home-pc',
    repo_key: 'git:abc',
    repo_name: 'zm-baton',
    base_branch: 'main',
    allowed_tools: ['Read', 'Edit'],
    expires_at: '2026-10-14T10:00:00Z',
    revoked_at: null,
  },
  workspace: { repo_key: 'git:abc', base_commit: '0123456789abcdef0123456789abcdef01234567', branch: 'zm-baton/run_1', base_ref: 'main', origin: 'new' },
  git_changes: null,
  instruction: '지시',
  lease_expires_at: '2026-10-14T02:06:30Z',
  lease_lapsed: false,
  lost_at: '2026-10-14T02:11:30Z',
  continue_reason: null,
  resumed_from_run_id: null,
  readiness: null,
  capabilities: { live_input: true, resume: true },
  inputs: [],
  actions: { cancel: { allowed: true, reason: null }, send_input: { allowed: true, reason: null } },
  ...over,
});

const noop = async () => {};
const NOW = Date.parse('2026-10-14T02:06:00Z');
const list = (runs: ItemRun[], events: RunEvent[] = [], outcomeUnknown = false) =>
  renderToStaticMarkup(<RunList runs={runs} events={events} now={NOW} outcomeUnknown={outcomeUnknown} problem={null} onWithdraw={noop} />);

describe('controlling a run in progress', () => {
  it('offers stopping and an instruction to a running run', () => {
    const html = list([run()]);
    expect(html).toMatch(/<button type="button" class="btn">실행 취소<\/button>/);
    expect(html).toContain('실행 중인 AI에게 지시');
    expect(html).toMatch(/<button type="submit" class="btn" disabled="">지시 보내기<\/button>/); // nothing written yet
    expect(html).not.toContain('맡긴 일 거두기');
  });

  it('locks both with the server reasons once a stop was asked for', () => {
    const stopping = run({
      state: 'stopping',
      actions: {
        cancel: { allowed: false, reason: '이미 취소를 요청했다. 연결 프로그램이 실행기를 멈추고 보고하기를 기다린다' },
        send_input: { allowed: false, reason: '취소를 요청한 실행이다' },
      },
    });
    const html = list([stopping]);
    expect(html).toContain('<small class="hr-flag">취소 요청함</small>');
    expect(html).toMatch(/<button type="button" class="btn" disabled="" aria-describedby="[^"]+">실행 취소<\/button>/);
    expect(html).toContain('이미 취소를 요청했다. 연결 프로그램이 실행기를 멈추고 보고하기를 기다린다');
    expect(html).toContain('취소를 요청한 실행이다');
  });

  it('says why an older connector takes no instruction', () => {
    const old = run({
      capabilities: null,
      actions: { cancel: { allowed: true, reason: null }, send_input: { allowed: false, reason: '이 실행의 연결 프로그램은 실행 중 지시를 받지 않는다' } },
    });
    const html = list([old]);
    expect(html).toContain('이 실행의 연결 프로그램은 실행 중 지시를 받지 않는다');
    expect(html).toMatch(/<textarea[^>]*disabled=""/);
  });

  it('lists the instructions sent, and how far each got', () => {
    const html = list([
      run({
        state: 'ended',
        end_reason: 'submitted',
        actions: null,
        inputs: [
          { id: 'inp_1', text: 'README도 고친다', state: 'delivered', created_at: '', sent_at: '', delivered_at: '' },
          { id: 'inp_2', text: '테스트는 그대로 둔다', state: 'undelivered', created_at: '', sent_at: null, delivered_at: null },
        ],
      }),
    ]);
    expect(html).toContain('사람이 보낸 지시');
    expect(html).toContain('README도 고친다</span><small class="hr-dim">실행기가 받음</small>');
    expect(html).toContain('테스트는 그대로 둔다</span><small class="hr-flag">전달하지 못함</small>');
    expect(html).toContain('「이어서 맡기기」의 덧붙일 지시');
    expect(html).not.toContain('실행 취소');
  });

  it('warns when the connector went silent, and says when the server gives up', () => {
    const html = list([run({ lease_lapsed: true })]);
    expect(html).toContain('연결 프로그램의 신호가 끊겼다');
    expect(html).toContain('「연결 끊김」으로 끝낸다');
  });

  it('explains a run the server ended lost', () => {
    const html = list([run({ state: 'ended', end_reason: 'lost', actions: null })], [], true);
    expect(html).toContain('<strong data-testid="run-status">오류</strong>');
    expect(html).toContain('연결 끊김: 연결 프로그램의 보고가 끊겨');
    expect(html).toContain('「결과 확인 필요」');
  });
});

describe('the readiness check and continuing runs', () => {
  it('shows which readiness check stopped the run, instead of the plain error', () => {
    const stopped = run({
      state: 'ended',
      end_reason: 'failed',
      actions: null,
      workspace: null,
      readiness: {
        ok: false,
        checks: [
          { name: 'executor', ok: true, detail: 'Claude Code 2.1.296' },
          { name: 'base_branch', ok: false, detail: '기준 브랜치 dev이(가) 이 저장소에 없다' },
        ],
      },
    });
    const html = list([stopped], [{ event_id: 'e', generation: 2, seq: 1, kind: 'error', payload: { text: '시작 전 준비 검사에서 멈췄다: x' }, occurred_at: null, received_at: '' }]);
    expect(html).toContain('시작 전 준비 검사에서 멈춰 실행기를 띄우지 않았다');
    expect(html).toContain('<strong>기준</strong> 기준 브랜치 dev이(가) 이 저장소에 없다');
    expect(html).toContain('시작 전 준비 검사 미달');
    expect(html).not.toContain('시작 전 준비 검사에서 멈췄다: x');
  });

  it('says which run a continuing run follows and how its worktree came to be', () => {
    const html = list([run({ continue_reason: 'resume_after_cut', resumed_from_run_id: 'run_prev', workspace: { ...run().workspace!, origin: 'reused' } })]);
    expect(html).toContain('<dt>이어서</dt><dd>끊기거나 멈춘 실행을 이었다<code>run_prev</code></dd>');
    expect(html).toContain('이전 실행의 작업 트리와 세션을 이어 썼다');
  });

  it('labels the button by how the work is handed over', () => {
    const resume = renderToStaticMarkup(<HandoffForm detail={detail({ allowed: true, reason: null, mode: 'resume', resume_from: previous })} repos={repos} onDelegate={noop} />);
    expect(resume).toContain('이어서 맡기기</button>');
    const back = renderToStaticMarkup(
      <HandoffForm detail={detail({ allowed: true, reason: null, mode: 'reject_and_resume', needs_note: true, resume_from: previous }, { stage: 'result_review' })} repos={repos} onDelegate={noop} />,
    );
    expect(back).toContain('돌려보내고 이어서 맡기기</button>');
  });

  it('starts a continuing run with the earlier run, its repository and tools', () => {
    const html = renderToStaticMarkup(
      <HandoffForm detail={detail({ allowed: true, reason: null, mode: 'resume', resume_from: previous })} repos={repos} onDelegate={noop} defaultOpen />,
    );
    expect(html).toContain('이어 갈 실행 <code>run_prev</code><span class="hr-dim">취소됨</span>');
    expect(html).toMatch(/<option value="dev_b\|git:abc" selected="">zm-baton — work-pc<\/option>/);
    expect(html).toContain('<input type="radio" name="hf-tools" checked="" value="edit"/>');
    expect(html).toContain('비우면 이전 실행과 같다(main)');
    expect(html).toContain('덧붙일 지시(선택)');
    expect(html).toContain('Claude Code에 이어서 맡기기');
  });

  it('needs the reason before a result goes back', () => {
    const html = renderToStaticMarkup(
      <HandoffForm detail={detail({ allowed: true, reason: null, mode: 'reject_and_resume', needs_note: true, resume_from: previous }, { stage: 'result_review' })} repos={repos} onDelegate={noop} defaultOpen />,
    );
    expect(html).toContain('돌려보내는 이유와 고칠 점');
    expect(html).toMatch(/<textarea[^>]*required=""/);
    expect(html).toMatch(/<button type="submit" class="btn btn-primary" disabled="">결과를 돌려보내고 이어서 맡기기<\/button>/);
  });
});

describe('history lines of run control', () => {
  const line = (action: string, details: Record<string, unknown> = {}) => historyLine({ action, actor_kind: 'person', actor_id: 'usr_owner', details, at: '' });
  it('says what a person did to a run', () => {
    expect(line('work_item.run_cancel_requested', { run_id: 'run_1' })).toBe('실행 취소를 요청함');
    expect(line('run.input_sent', { text: 'README도' })).toBe('실행 중인 AI에게 지시함 — README도');
    expect(line('run.lost', {})).toContain('「연결 끊김」');
    expect(line('work_item.delegated', { allowed_tools: ['Read'], resumed_from_run_id: 'run_1', note: '테스트도' })).toBe('AI에게 이어서 맡김 (도구 Read) — 테스트도');
  });
});
