import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { RunEvent } from './api';
import { reportStatus, undecidedRules } from './runState';
import { HandoffForm, RunList, TOOL_SETS } from './work/Handoff';
import { WorkDetail } from './work/WorkDetail';
import type { ItemRun, Repository, WorkItem, WorkItemDetail } from './workApi';
import { historyLine } from './workModel';

const item = (over: Partial<WorkItem> = {}): WorkItem => ({
  id: 'wrk_a',
  title: '작업 하나',
  stage: 'ready',
  wait_reasons: [],
  generation: 0,
  created_at: '2026-10-14T00:00:00Z',
  version: 3,
  external_ref: null,
  project: null,
  source_of_truth: 'product',
  executor_kind: 'human',
  next_action: null,
  wait_note: null,
  opened_on: '2026-10-14',
  due_on: null,
  closed_at: null,
  updated_at: '2026-10-14T01:00:00Z',
  ...over,
});

const detail = (over: Partial<WorkItemDetail> = {}, itemOver: Partial<WorkItem> = {}): WorkItemDetail => ({
  item: item(itemOver),
  responsible_user_id: 'usr_owner',
  executor_id: 'usr_owner',
  acceptance: null,
  source_ref: null,
  git_ref: null,
  live_run: false,
  read_only_reason: null,
  moves: [],
  history: [],
  delegation: { allowed: true, reason: null },
  ...over,
});

const repo: Repository = {
  device_id: 'dev_a',
  device_name: 'home-pc',
  repo_key: 'git:abc',
  name: 'zm-baton',
  remote: 'https://github.com/hanumoka/zm-baton',
  updated_at: '2026-10-14T00:00:00Z',
};

const run = (over: Partial<ItemRun> = {}): ItemRun => ({
  id: 'run_1',
  work_item_id: 'wrk_a',
  state: 'ended',
  end_reason: 'submitted',
  executor: 'claude_code',
  generation: 1,
  device_id: 'dev_a',
  late_reports: false,
  created_at: '2026-10-14T02:00:00Z',
  last_event_at: '2026-10-14T02:05:00Z',
  claimed_at: '2026-10-14T02:00:01Z',
  started_at: '2026-10-14T02:00:02Z',
  ended_at: '2026-10-14T02:05:00Z',
  last_activity_at: '2026-10-14T02:04:59Z',
  delegation: {
    id: 'dlg_1',
    device_id: 'dev_a',
    device_name: 'home-pc',
    repo_key: 'git:abc',
    repo_name: 'zm-baton',
    base_branch: 'main',
    allowed_tools: ['Read', 'Glob', 'Grep', 'Edit', 'Write'],
    expires_at: '2026-10-14T10:00:00Z',
    revoked_at: null,
  },
  workspace: { repo_key: 'git:abc', base_commit: '0123456789abcdef0123456789abcdef01234567', branch: 'zm-baton/run_1', base_ref: 'main' },
  git_changes: { files: ['README.md', 'docs/a.md'], base_overlap: [], base_branch_overlap: ['README.md'] },
  instruction: 'zm-baton이 작업 하나를 맡긴다.',
  ...over,
});

const ev = (seq: number, kind: string, payload: Record<string, unknown> = {}): RunEvent => ({
  event_id: `evt_${seq}`,
  generation: 1,
  seq,
  kind,
  payload,
  occurred_at: null,
  received_at: '2026-10-14T02:01:00Z',
});

const noop = async () => {};
const NOW = Date.parse('2026-10-14T02:06:00Z');

describe('handing a work item to an AI', () => {
  it('locks the button and shows the server reason when the work item cannot be handed over', () => {
    const reason = '정본이 Git 파일(registry/tasks.json)이다. 원본에서 고치고 다시 가져온다. AI가 실행하면 단계가 바뀌므로 이 복사본은 맡길 수 없다';
    const html = renderToStaticMarkup(<HandoffForm detail={detail({ delegation: { allowed: false, reason } })} repos={[repo]} onDelegate={noop} />);
    expect(html).toMatch(/<button type="button" class="btn" disabled="" aria-describedby="([^"]+)">.*AI에게 맡기기<\/button>/);
    const described = html.match(/aria-describedby="([^"]+)"/)![1];
    expect(html).toContain(`id="${described}"`);
    expect(html).toContain(reason);
  });

  it('says so when the server does not know handing over yet', () => {
    const old = detail();
    delete old.delegation;
    const html = renderToStaticMarkup(<HandoffForm detail={old} repos={null} onDelegate={noop} />);
    expect(html).toContain('disabled=""');
    expect(html).toContain('서버를 새 판으로 올린다');
  });

  it('offers the button when it can be handed over, and the tool sets never include Bash', () => {
    const html = renderToStaticMarkup(<HandoffForm detail={detail()} repos={[repo]} onDelegate={noop} />);
    expect(html).toMatch(/<button type="button" class="btn">.*AI에게 맡기기<\/button>/);
    expect(html).not.toContain('disabled');
    expect(TOOL_SETS[0].key).toBe('read');
    expect(TOOL_SETS.flatMap((t) => t.tools)).not.toContain('Bash');
  });

  it('shows the work item made here with the handoff block, and a Git copy with its lock', () => {
    const actions = { move: noop, update: noop, addWait: noop, removeWait: noop, delegate: noop, withdraw: noop };
    const here = renderToStaticMarkup(<WorkDetail item={item()} detail={detail()} projects={[]} skipped={[]} actions={actions} error={null} />);
    expect(here).toContain('<h3>AI에게 맡기기</h3>');
    expect(here).toContain('<h3>실행 기록</h3>');
    const copy = detail(
      { read_only_reason: '정본이 Git 파일이다', delegation: { allowed: false, reason: '정본이 Git 파일이다. 맡길 수 없다' } },
      { source_of_truth: 'git' },
    );
    const html = renderToStaticMarkup(<WorkDetail item={copy.item} detail={copy} projects={[]} skipped={[]} actions={actions} error={null} />);
    expect(html).toContain('정본이 Git 파일이다. 맡길 수 없다');
  });
});

describe('the run list', () => {
  const list = (runs: ItemRun[] | null, events: RunEvent[] = [], outcomeUnknown = false, problem: string | null = null) =>
    renderToStaticMarkup(<RunList runs={runs} events={events} now={NOW} outcomeUnknown={outcomeUnknown} problem={problem} onWithdraw={noop} />);

  it('says when nothing has run yet, and when the server cannot be read', () => {
    expect(list([])).toContain('아직 AI가 실행한 적이 없다.');
    expect(list(null, [], false, 'HTTP 500')).toContain('실행 기록을 읽지 못했다. HTTP 500');
  });

  it('shows an ended run with its branch, changed files, overlaps and that zm-baton does not merge', () => {
    const html = list([run()]);
    expect(html).toContain('<strong data-testid="run-status">완료</strong>');
    expect(html).toContain('<code>zm-baton/run_1</code>');
    expect(html).toContain('<code>0123456789</code>');
    expect(html).toContain('<code>README.md</code><small class="hr-flag">원래 브랜치에서도 바뀜</small>');
    expect(html).toContain('<code>docs/a.md</code></li>');
    expect(html).toContain('1단계에서는 zm-baton이 합치지 않는다. 이 브랜치를 Git으로 직접 합치거나 버린다.');
    expect(html).toContain('<dd>zm-baton<span class="hr-dim">home-pc</span></dd>');
    expect(html).toContain('Read, Glob, Grep, Edit, Write');
    expect(html).toContain('AI에게 넘긴 지시');
  });

  it('lists the newest run first and gives only it the events', () => {
    const older = run({ id: 'run_old', end_reason: 'failed', created_at: '2026-10-14T01:00:00Z' });
    const html = list([older, run({ id: 'run_new' })]);
    expect(html.indexOf('run_new')).toBeLessThan(html.indexOf('run_old'));
    expect(html).toContain('<strong data-testid="run-status">오류</strong>');
  });

  it('shows the recent activity of a running run, and its last measured change list', () => {
    const live = run({ state: 'running', end_reason: null, ended_at: null, git_changes: { files: ['a.txt'], base_overlap: ['a.txt'] } });
    const events = [
      ev(1, 'lifecycle', { phase: 'started' }),
      ev(2, 'action', { name: 'Edit' }),
      ev(3, 'git_changes', { files: ['a.txt'] }),
      ev(4, 'answer', { text: '고치는 중' }),
    ];
    const html = list([live], events);
    expect(html).toContain('<strong data-testid="run-status">작업 중</strong>');
    expect(html).toContain('최근 활동');
    expect(html).toContain('도구 호출: Edit');
    expect(html).toContain('고치는 중');
    expect(html).not.toContain('바뀐 파일 목록'); // the change list is not shown as activity
    expect(html).toContain('원래 폴더에도 고치는 중');
    expect(html).toContain('(실행 중 마지막으로 잰 것)');
    expect(html).not.toContain('합치지 않는다'); // only once it ended
  });

  it('offers taking back a run no connector has claimed, and says where to look', () => {
    const waiting = run({ state: 'requested', end_reason: null, claimed_at: null, started_at: null, ended_at: null, workspace: null, git_changes: null });
    const html = list([waiting]);
    expect(html).toContain('<strong data-testid="run-status">청구 대기</strong>');
    expect(html).toContain('맡긴 일 거두기');
    expect(html).toContain('<code>zm-baton agent</code>');
  });

  it('explains 결과 확인 필요 on a run cut before its result, with the last error', () => {
    const cut = run({ end_reason: 'failed', git_changes: { files: [] } });
    const html = list([cut], [ev(1, 'error', { text: 'run timeout reached; executor stopped' })], true);
    expect(html).toContain('run timeout reached; executor stopped');
    expect(html).toContain('「결과 확인 필요」');
    expect(html).toContain('바뀐 파일 <span class="num">0</span>개');
  });
});

describe('report status with the claim time', () => {
  const live = (over: Partial<ItemRun> = {}) => run({ state: 'claimed', end_reason: null, last_event_at: null, last_activity_at: null, ...over });

  it('has no response when no activity came within 60 seconds of the claim', () => {
    expect(reportStatus(live(), [], Date.parse('2026-10-14T02:00:30Z'))).toBe('first_event_pending');
    expect(reportStatus(live(), [], Date.parse('2026-10-14T02:01:30Z'))).toBe('no_response');
    // The connector's own started report is not activity.
    expect(reportStatus(live({ last_event_at: '2026-10-14T02:00:02Z' }), [ev(1, 'lifecycle')], Date.parse('2026-10-14T02:01:30Z'))).toBe('no_response');
    expect(undecidedRules(live(), []).map((u) => u.rule)).toEqual(['입력 필요']);
  });

  it('judges an error by the last activity, not by a change list sent after it', () => {
    const r = live({ last_activity_at: '2026-10-14T02:05:00Z' });
    expect(reportStatus(r, [ev(1, 'error', { text: 'x' }), ev(2, 'git_changes', { files: [] })], NOW)).toBe('error');
  });
});

describe('history lines', () => {
  it('says what a handoff did and that a run moved the stage', () => {
    expect(historyLine({ action: 'work_item.delegated', actor_kind: 'person', actor_id: 'usr_owner', details: { allowed_tools: ['Read', 'Edit'], base_branch: 'main' }, at: '' })).toBe(
      'AI에게 맡김 (도구 Read, Edit, 기준 main)',
    );
    expect(historyLine({ action: 'work_item.delegation_withdrawn', actor_kind: 'person', actor_id: 'usr_owner', details: {}, at: '' })).toBe('맡긴 일을 거둠');
    expect(
      historyLine({ action: 'work_item.stage_changed', actor_kind: 'system', actor_id: 'run_1', details: { from: 'in_progress', path: ['result_review', 'done'] }, at: '' }),
    ).toBe('수행 → 결과 검토 → 완료 (AI 실행이 옮김)');
  });
});
