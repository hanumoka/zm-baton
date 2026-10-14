import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MeView, Notes } from './me/Me';
import { actionLabel, noteProblem, splitWork, targetHref, workLine } from './me/meView';
import { meApi, type ManagerRecord } from './orgApi';
import type { WorkItem } from './workApi';

const work = (over: Partial<WorkItem> = {}): WorkItem => ({
  id: 'wrk_a',
  title: '조직 화면 만들기',
  stage: 'in_progress',
  wait_reasons: [],
  generation: 1,
  created_at: '2026-10-14T00:00:00Z',
  version: 2,
  external_ref: 'TSK-031',
  project: null,
  source_of_truth: 'product',
  executor_kind: 'manager',
  next_action: '스크린샷을 찍는다',
  wait_note: null,
  opened_on: '2026-10-14',
  due_on: null,
  closed_at: null,
  updated_at: '2026-10-14T01:00:00Z',
  visibility: 'org',
  ...over,
});

const record = (over: Partial<ManagerRecord> = {}): ManagerRecord => ({
  person: { id: 'usr_owner', display_name: 'owner', status: 'active' },
  manager_id: 'mgr_owner',
  org: { id: 'org_owner', name: 'owner' },
  roles: ['requester', 'assignee', 'reviewer', 'approver', 'operator', 'master_admin'],
  teams: [],
  status_card: '[zm-baton] 상태 카드 미리보기: 다음 세션이 시작할 때 받는다\n열린 작업 1건:\n- TSK-031 조직 화면 만들기 (수행)',
  work: [work()],
  sessions: [
    {
      id: 'ses_0192aaaa0000bbbb',
      executor: 'claude_code',
      device_name: 'my-pc',
      repo_key: 'git:abc',
      branch: 'work/org-permissions',
      current_work: '비공개로 하는 일',
      shared: false,
      current_work_at: '2026-10-14T01:00:00Z',
      state: 'live',
      last_seen_at: '2026-10-14T01:05:00Z',
    },
  ],
  notes: [{ id: 'mnt_a', body: '내일 백업 복원 시험을 다시 한다', created_at: '2026-10-14T00:00:00Z', updated_at: '2026-10-14T00:30:00Z' }],
  activity: [{ action: 'work_item.delegated', actor_kind: 'person', actor_id: 'usr_owner', target_kind: 'wrk', target_id: 'wrk_a', at: '2026-10-14T00:10:00Z' }],
  ...over,
});

describe('the 나 screen', () => {
  it('draws who I am, the status card, my work, my sessions, my notes and what I did, in that order', () => {
    const html = renderToStaticMarkup(<MeView record={record()} onChanged={() => {}} />);
    const at = (s: string) => html.indexOf(s);
    expect(html).toContain('<code>mgr_owner</code>');
    expect(html).toContain('모든 역할');
    expect(html).toContain('[zm-baton] 상태 카드 미리보기');
    expect(html).toContain('href="#work?item=wrk_a"');
    expect(html).toContain('다음: 스크린샷을 찍는다');
    expect(html).toContain('href="#sessions?item=ses_0192aaaa0000bbbb"');
    // My own summary shows even though it is not shared, marked as mine alone.
    expect(html).toContain('비공개로 하는 일');
    expect(html).toContain('공유 안 함(나만 봄)');
    expect(html).toContain('나만 본다. 조직 운영자도 못 보고, 내보내기에도 들어가지 않는다.');
    expect(html).toContain('내일 백업 복원 시험을 다시 한다');
    expect(html).toContain('AI에게 맡겼다');
    const order = ['상태 카드 미리보기', '내 열린 작업', '연결된 세션', '개인 메모', '작업 기록'].map(at);
    expect(order.every((n) => n >= 0)).toBe(true);
    expect([...order].sort((a, b) => a - b)).toEqual(order);
  });

  it('says what an empty record means', () => {
    const html = renderToStaticMarkup(<MeView record={record({ work: [], sessions: [], notes: [], activity: [] })} onChanged={() => {}} />);
    expect(html).toContain('책임지거나 맡은 열린 작업이 없다');
    expect(html).toContain('zm-baton session register');
    expect(html).toContain('아직 메모가 없다');
  });

  it('locks adding an empty note', () => {
    expect(renderToStaticMarkup(<Notes notes={[]} onChanged={() => {}} />)).toMatch(/<button type="submit" class="btn btn-primary" disabled="">메모 더하기<\/button>/);
    expect(noteProblem('  ')).toBe('메모를 쓴다');
    expect(noteProblem('x'.repeat(2001))).toContain('2,000자');
    expect(noteProblem('메모')).toBeNull();
  });

  it('shows open work first and keeps done or cancelled work behind its count', () => {
    const base = record().work[0];
    const work: WorkItem[] = [{ ...base, id: 'wrk_done', stage: 'done' }, { ...base, id: 'wrk_open', stage: 'in_progress' }, { ...base, id: 'wrk_gone', stage: 'cancelled' }];
    expect(splitWork(work).open.map((w) => w.id)).toEqual(['wrk_open']);
    expect(splitWork(work).closed.map((w) => w.id)).toEqual(['wrk_done', 'wrk_gone']);
    const html = renderToStaticMarkup(<MeView record={record({ work })} onChanged={() => {}} />);
    expect(html).toContain('끝내거나 취소한 작업 2건');
    expect(html.indexOf('wrk_open')).toBeLessThan(html.indexOf('끝내거나 취소한 작업'));
    expect(actionLabel('work_item.imported')).toBe('작업을 가져왔다');
  });

  it('names actions and opens the records they touched', () => {
    expect(actionLabel('manager_note.added')).toBe('개인 메모를 썼다');
    expect(actionLabel('something.new')).toBe('something.new');
    expect(targetHref({ target_kind: 'dcs', target_id: 'dcs_a' })).toBe('#decisions?item=dcs_a');
    expect(targetHref({ target_kind: 'mnt', target_id: 'mnt_a' })).toBeNull();
    expect(workLine({ current_work: null, shared: true })).toEqual({ text: '아직 올리지 않았다', private: false });
  });
});

describe('note requests', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('adds, changes and deletes a note at my record only', async () => {
    const note = { id: 'mnt_b', body: '메모', created_at: '2026-10-14T00:00:00Z', updated_at: '2026-10-14T00:00:00Z' };
    const fetch = vi.fn(async (_path: string, init?: RequestInit) =>
      init?.method === 'DELETE' ? new Response(null, { status: 204 }) : new Response(JSON.stringify(note), { status: init?.method === 'POST' ? 201 : 200 }),
    );
    vi.stubGlobal('fetch', fetch);
    expect(await meApi.addNote('메모')).toEqual(note);
    await meApi.changeNote('mnt_b', '고친 메모');
    await meApi.deleteNote('mnt_b');
    const calls = (fetch.mock.calls as unknown as [string, RequestInit][]).map(([p, i]) => [i.method, p, i.body ? JSON.parse(i.body as string) : null]);
    expect(calls).toEqual([
      ['POST', '/api/me/notes', { body: '메모' }],
      ['PATCH', '/api/me/notes/mnt_b', { body: '고친 메모' }],
      ['DELETE', '/api/me/notes/mnt_b', null],
    ]);
  });

  it('reads my record from its own path', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify(record()), { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    const r = await meApi.record();
    expect(r.manager_id).toBe('mgr_owner');
    expect((fetch.mock.calls[0] as unknown as [string])[0]).toBe('/api/me/record');
  });
});
