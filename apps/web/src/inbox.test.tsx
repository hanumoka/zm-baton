import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { Inbox } from './inbox/Inbox';
import { InboxDetail, ReasonForm } from './inbox/InboxDetail';
import { InboxList } from './inbox/InboxList';
import {
  detailTitle,
  groupNote,
  inboxGroups,
  itemSessions,
  judgement,
  kindOf,
  listState,
  readView,
  rowOrder,
  rowTitle,
  sendJudgement,
  writeView,
  type ListState,
} from './inbox/inboxView';
import { inboxHref, sessionHref, waited } from './inboxModel';
import { sessionsApi, type InboxItem, type SessionView } from './sessionsApi';
import { screenFromHash } from './shell/screens';
import { nextInPlace } from './ui/useNextWhenGone';

const T0 = Date.parse('2026-10-14T00:00:00Z');
const iso = (minutes: number) => new Date(T0 + minutes * 60_000).toISOString();

const LIVE = 'ses_0123abcd-0000-7000-8000-00000000ad56';
const GONE = 'ses_0123abcd-0000-7000-8000-000000000383';

// A file conflict between two sessions, as the server raises it (SessionService.checkConflicts).
const conflict = (over: Partial<InboxItem> = {}): InboxItem => ({
  id: 'inb_c',
  bucket: 3,
  kind: 'notice',
  source_kind: 'ntc',
  source_id: 'ntc_c',
  summary: '두 세션이 같은 파일을 고침: .session/context.md',
  detail: {
    path: '.session/context.md',
    source: 'file_touch',
    repo_key: 'git:0123456789ab',
    sessions: [
      { session_id: LIVE, executor: 'claude_code', branch: 'main' },
      { session_id: GONE, executor: 'claude_code', branch: 'work/x', touched_at: iso(-30) },
    ],
  },
  wait_kind: null,
  repeat_count: 0,
  waiting_since: iso(0),
  notify: 'immediate',
  ...over,
});

// A session that waits in its terminal, as the server raises it (SessionService.refreshWait).
const waiting = (over: Partial<InboxItem> = {}): InboxItem => ({
  id: 'inb_w',
  bucket: 2,
  kind: 'session_waiting',
  source_kind: 'ses',
  source_id: LIVE,
  summary: 'ses_0123…ad56(claude_code, main) · 다음 지시를 기다림',
  detail: { wait_kind: 'idle_prompt', message: null, branch: 'main' },
  wait_kind: 'idle_prompt',
  repeat_count: 2,
  waiting_since: iso(-5),
  notify: 'digest',
  ...over,
});

const session = (over: Partial<SessionView> = {}): SessionView => ({
  id: LIVE,
  executor: 'claude_code',
  executor_version: null,
  device_name: 'owner-pc',
  repository: { remote: 'https://github.com/hanumoka/zm-docs.git', prefix: '', branch: 'main' },
  repo_key: 'git:0123456789ab',
  current_work: '결정 문서 셋 쓰는 중',
  shared: true,
  current_work_at: iso(-60),
  state: 'live',
  created_at: iso(-120),
  last_seen_at: iso(-1),
  touched_files: ['.session/context.md'],
  conflicted_files: ['.session/context.md'],
  ...over,
});

function list(state: ListState, items: InboxItem[] = [], selected: string | null = null, sessions: SessionView[] | null = [session()]) {
  return renderToStaticMarkup(
    <InboxList
      state={state}
      groups={inboxGroups(items)}
      head={{ base: '기다리는 것', count: items.length, narrowed: false }}
      selected={selected}
      sessions={sessions}
      now={T0 + 90 * 60_000}
      onSelect={() => {}}
      onPick={() => {}}
      onOpen={() => {}}
      onReload={() => {}}
    />,
  );
}

const detail = (item: InboxItem, sessions: SessionView[] | null = [session()]) =>
  renderToStaticMarkup(<InboxDetail item={item} sessions={sessions} now={T0 + 90 * 60_000} onJudged={() => {}} onError={() => {}} />);

describe('buckets', () => {
  it('keeps the five buckets in the contract order, each with the longest wait first, and every bucket even empty', () => {
    const groups = inboxGroups([conflict({ id: 'new', waiting_since: iso(10) }), conflict({ id: 'old', waiting_since: iso(-10) }), waiting()]);
    expect(groups.map((g) => [g.bucket, g.label, g.items.map((i) => i.id)])).toEqual([
      [1, '권한 요청', []],
      [2, '멈춘 AI의 질문', ['inb_w']],
      [3, '내 작업과 겹친 충돌', ['old', 'new']],
      [4, '위험 목록 검토', []],
      [5, '표본 검토와 나머지 알림', []],
    ]);
    expect(rowOrder(groups)).toEqual(['inb_w', 'old', 'new']);
  });

  it('says a bucket the server does not fill yet is not relayed, and a clear bucket that it is clear', () => {
    const notes = inboxGroups([]).map(groupNote);
    expect(notes).toEqual(['아직 중계하지 않는다', '기다리는 세션이 없다', '겹친 파일이 없다', '서버가 아직 만들지 않는다', '서버가 아직 만들지 않는다']);
  });

  it('draws an empty bucket as one line in its head, not a box, and a full one with its rows', () => {
    const html = list({ kind: 'rows' }, [conflict()]);
    expect(html).toMatch(/권한 요청 <span class="n num">0<\/span><span class="grp-r">아직 중계하지 않는다<\/span>/);
    expect(html).toMatch(/내 작업과 겹친 충돌 <span class="n num">1<\/span><\/h3>/);
    expect(html).not.toContain('empty-state');
    expect(html).toContain('급한 순서로 묶음');
  });
});

describe('rows', () => {
  it('tells the kind by a shape and a word and puts the conflicting file in the title', () => {
    expect(kindOf(conflict())).toEqual({ mark: 'conflict', label: '충돌' });
    expect(kindOf(waiting())).toEqual({ mark: 'wait', label: '다음 지시' });
    expect(kindOf(waiting({ wait_kind: 'permission_prompt' }))).toEqual({ mark: 'wait', label: '권한 확인' });
    expect(kindOf(waiting({ wait_kind: 'something_new', detail: null }))).toEqual({ mark: 'wait', label: '입력 기다림' });
    const html = list({ kind: 'rows' }, [conflict()], 'inb_c');
    expect(html).toContain('<span class="lrow-state">충돌</span><span class="lrow-title ib-file">.session/context.md</span>');
    expect(html).toMatch(/data-row="inb_c" data-kind="notice" aria-current="true" tabindex="0"/);
  });

  it('titles a waiting session by the words it sent, else what it works on, else what it waits for', () => {
    const sessions = [session()];
    expect(rowTitle(waiting({ wait_kind: 'permission_prompt', detail: { message: 'Claude needs your permission to use Bash' } }), sessions)).toBe(
      'Claude needs your permission to use Bash',
    );
    expect(rowTitle(waiting(), sessions)).toBe('결정 문서 셋 쓰는 중');
    expect(rowTitle(waiting(), [session({ shared: false, current_work: null })])).toBe('AI 세션이 할 일을 마치고 다음 지시를 기다린다');
    expect(rowTitle(waiting(), null)).toBe('AI 세션이 할 일을 마치고 다음 지시를 기다린다');
  });

  it('puts how long it waited, how it alerts, repeats and the sessions with their branch on the second line', () => {
    const html = list({ kind: 'rows' }, [conflict(), waiting()]);
    expect(html).toContain('<span class="ib-age num">1시간째</span><span class="ib-ntf is-now">바로 알림</span>');
    expect(html).toContain('<code>ses_0123…ad56</code><span>main</span>');
    expect(html).toContain('<code>ses_0123…0383</code><span>work/x</span>');
    expect(html).toContain('<span class="ib-ntf">묶어 알림</span><span class="ib-rep num">반복 2회</span>');
    expect(html).not.toContain(' · ');
  });

  it('reads the sessions an item names from its evidence or its source', () => {
    expect(itemSessions(conflict()).map((s) => [s.id, s.branch, s.touchedAt])).toEqual([
      [LIVE, 'main', null],
      [GONE, 'work/x', iso(-30)],
    ]);
    expect(itemSessions(waiting())).toEqual([{ id: LIVE, executor: null, branch: 'main', touchedAt: null }]);
    expect(itemSessions(conflict({ detail: { sessions: 'broken' } }))).toEqual([]);
  });

  it('counts the wait up to now', () => {
    expect(waited(iso(0), T0 + 30_000)).toBe('방금');
    expect(waited(iso(0), T0 + 5 * 60_000)).toBe('5분째');
    expect(waited(iso(0), T0 + 3 * 3_600_000)).toBe('3시간째');
    expect(waited(iso(0), T0 + 50 * 3_600_000)).toBe('2일째');
  });
});

describe('empty states', () => {
  it('tells loading, a failed read, nothing waiting and rows apart', () => {
    expect(listState(null, null)).toEqual({ kind: 'loading' });
    expect(listState(null, 'HTTP 503')).toEqual({ kind: 'error', message: 'HTTP 503' });
    expect(listState([], null)).toEqual({ kind: 'empty' });
    // A failure after a good read keeps the rows; the problem list at the top says what failed.
    expect(listState([conflict()], 'HTTP 503')).toEqual({ kind: 'rows' });
  });

  it('says nothing waits now and what would come here, or what failed and how to read again', () => {
    const empty = list({ kind: 'empty' });
    expect(empty).toContain('empty-state is-empty');
    expect(empty).toContain('지금 나를 기다리는 것이 없다');
    expect(empty).toContain('터미널에서 입력을 기다리거나, 두 세션이 같은 파일을 고치면 여기에 쌓인다');
    expect(empty).toContain('href="#sessions"');

    const error = list({ kind: 'error', message: 'HTTP 503: database is not reachable' });
    expect(error).toContain('empty-state is-error');
    expect(error).toContain('나를 기다리는 것을 읽지 못했다');
    expect(error).toContain('HTTP 503: database is not reachable');
    expect(error).toContain('다시 읽기');

    expect(list({ kind: 'loading' })).toContain('aria-busy="true"');
  });
});

describe('detail', () => {
  it('shows a conflict: what waits, why it is mine, the file in monospace and the three judgements', () => {
    const html = detail(conflict());
    expect(detailTitle(conflict())).toBe('두 세션이 같은 파일을 고쳤다');
    expect(html).toContain('<nav class="dt-crumb" aria-label="위치"><span>나를 기다리는 것</span><span aria-hidden="true">/</span><span>내 작업과 겹친 충돌</span></nav>');
    expect(html).toContain('<h3>겹친 파일</h3><p class="ib-path"><code>.session/context.md</code></p>');
    expect(html).toContain('<h3>왜 나를 기다리나</h3>');
    expect(html).toContain('<h3>할 수 있는 일</h3>');
    for (const label of ['확인', '충돌 아님', '해결']) expect(html).toContain(`>${label}<small>`);
    expect(html).toContain('class="btn btn-primary"');
    // The reason form opens only after 「충돌 아님」.
    expect(html).not.toContain('충돌이 아닌 이유');
    expect(html).toContain('aria-expanded="false"');
  });

  it('links the related sessions to the session screen, a session that is not live with ended sessions shown', () => {
    const html = detail(conflict());
    expect(html).toContain('관련 세션 <span class="num">2</span>');
    expect(html).toContain(`href="#sessions?item=${LIVE}"`);
    expect(html).toContain(`href="#sessions?ended=1&amp;item=${GONE}"`);
    expect(html).toContain('결정 문서 셋 쓰는 중');
    expect(html).toContain('지금은 살아 있지 않다');
    expect(html).toContain('이 파일을 고친 때 2시간 전');
  });

  it('answers a waiting session in its terminal: the action place says so and opens the session', () => {
    const html = detail(waiting({ wait_kind: 'permission_prompt', detail: { wait_kind: 'permission_prompt', message: 'Claude needs your permission to use Bash', branch: 'main' } }));
    expect(html).toContain('AI 세션이 터미널에서 권한 확인을 기다린다');
    expect(html).toContain('<h3>실행기가 보낸 문구</h3><p class="dt-text">Claude needs your permission to use Bash</p>');
    expect(html).toContain('그 세션의 터미널에서 이어 입력하면 저절로 닫힌다');
    expect(html).toContain(`href="#sessions?item=${LIVE}"`);
    expect(html).toContain('세션 현황에서 그 세션 열기');
    expect(html).not.toContain('충돌 아님');
  });

  it('locks 「충돌 아님으로 남기기」 with its reason until a reason is written', () => {
    const blank = renderToStaticMarkup(<ReasonForm reason="  " onReason={() => {}} busy={false} onSubmit={() => {}} onCancel={() => {}} />);
    expect(blank).toMatch(/<button type="submit" class="btn btn-primary" disabled="">충돌 아님으로 남기기<\/button>/);
    expect(blank).toContain('이유를 적어야 보낼 수 있다');
    const written = renderToStaticMarkup(<ReasonForm reason="다른 절을 고친다" onReason={() => {}} busy={false} onSubmit={() => {}} onCancel={() => {}} />);
    expect(written).toMatch(/<button type="submit" class="btn btn-primary">충돌 아님으로 남기기<\/button>/);
    expect(written).not.toContain('이유를 적어야');
  });
});

describe('judgement', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('sends nothing for 「충돌 아님」 without a reason and asks for one', async () => {
    expect(judgement('not_a_conflict', '   ')).toBeNull();
    const send = vi.fn(async () => ({}));
    expect(await sendJudgement(conflict(), 'not_a_conflict', ' ', send)).toBe('needs-reason');
    expect(send).not.toHaveBeenCalled();
  });

  it('sends the notice id, the status and a trimmed reason; 확인 and 해결 send no note', async () => {
    const send = vi.fn(async () => ({}));
    expect(await sendJudgement(conflict(), 'not_a_conflict', '  다른 절을 고친다 ', send)).toBe('sent');
    expect(await sendJudgement(conflict(), 'acknowledged', '', send)).toBe('sent');
    expect(await sendJudgement(conflict(), 'resolved', 'ignored', send)).toBe('sent');
    expect(send.mock.calls).toEqual([
      ['ntc_c', 'not_a_conflict', '다른 절을 고친다'],
      ['ntc_c', 'acknowledged', undefined],
      ['ntc_c', 'resolved', undefined],
    ]);
  });

  it('posts the judgement to the notice status endpoint', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({ id: 'ntc_c', status: 'not_a_conflict' }), { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    await sendJudgement(conflict(), 'not_a_conflict', '다른 절', sessionsApi.judgeNotice);
    const [path, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/notices/ntc_c/status');
    expect(init.method).toBe('POST');
    expect(JSON.parse(String(init.body))).toEqual({ status: 'not_a_conflict', note: '다른 절' });
  });

  it('opens the item that took the place of the one that left: the next, else the one before, else the first', () => {
    expect(nextInPlace(['a', 'b', 'c'], ['a', 'c'], 'b')).toBe('c');
    expect(nextInPlace(['a', 'b', 'c'], ['a', 'b'], 'c')).toBe('b');
    expect(nextInPlace(['a', 'b'], [], 'a')).toBeNull();
    // An address with an item the list never had.
    expect(nextInPlace(['a', 'b'], ['a', 'b'], 'zzz')).toBe('a');
    // The next row also left at the same time: the one after it.
    expect(nextInPlace(['a', 'b', 'c', 'd'], ['a', 'd'], 'b')).toBe('d');
  });
});

describe('address and links', () => {
  it('keeps the selected item in the address', () => {
    expect(writeView({ selected: 'inb_c' })).toBe('#inbox?item=inb_c');
    expect(readView('#inbox?item=inb_c')).toEqual({ selected: 'inb_c' });
    expect(writeView({ selected: null })).toBe('#inbox');
    expect(readView('#inbox')).toEqual({ selected: null });
    expect(readView('#inbox?item=')).toEqual({ selected: null });
    expect(screenFromHash(writeView({ selected: 'inb_c' }))).toBe('inbox');
  });

  it('links to a session on the session screen and back to an item here', () => {
    expect(sessionHref(LIVE, true)).toBe(`#sessions?item=${LIVE}`);
    expect(sessionHref(GONE, false)).toBe(`#sessions?ended=1&item=${GONE}`);
    expect(screenFromHash(sessionHref(GONE, false))).toBe('sessions');
    expect(inboxHref('inb_c')).toBe('#inbox?item=inb_c');
  });
});

describe('first screen', () => {
  it('lays out the list and the detail, with no description paragraph in its head', () => {
    const html = renderToStaticMarkup(
      <Inbox items={[conflict()]} error={null} sessions={null} now={T0} onChanged={() => {}} onError={() => {}} onReload={() => {}} />,
    );
    expect(html).toMatch(/^<main class="panes ib"><h1 class="sr">나를 기다리는 것<\/h1><section class="pane-list"/);
    expect(html).toContain('<section class="pane-detail" aria-label="기다리는 것 상세">');
    expect(html).not.toContain('<header>');
    expect(html).not.toContain('급한 순서로 모은다');
    // Before the first row is picked (or when the address drops it), the detail says what to do.
    expect(html).toContain('고른 항목이 없다');
  });

  it('shows the loading rows before the first read and the failure when that read fails', () => {
    const loading = renderToStaticMarkup(<Inbox items={null} error={null} sessions={null} now={T0} onChanged={() => {}} onError={() => {}} onReload={() => {}} />);
    expect(loading).toContain('aria-busy="true"');
    const failed = renderToStaticMarkup(<Inbox items={null} error="HTTP 500" sessions={null} now={T0} onChanged={() => {}} onError={() => {}} onReload={() => {}} />);
    expect(failed).toContain('HTTP 500');
  });
});
