import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { groupByBucket, newImmediate, repoName, shortId, since } from './inboxModel';
import { Inbox } from './inbox/Inbox';
import { clear, fail, lines, type Problems } from './problems';
import { SessionDetail } from './sessions/SessionDetail';
import { SessionList } from './sessions/SessionList';
import { groupSessions, type ListState } from './sessions/sessionView';
import type { InboxItem, SessionView } from './sessionsApi';
import { ProblemList } from './Shell';

describe('problems', () => {
  it('a source clears only its own failure', () => {
    let p: Problems = {};
    p = fail(p, 'sessions', new Error('HTTP 503'));
    p = fail(p, 'inbox', new Error('HTTP 500'));
    p = clear(p, 'inbox'); // the first-screen poll works again
    expect(lines(p)).toEqual(['「세션 현황」을 읽지 못했다: HTTP 503']);
    p = fail(p, 'judge', 'HTTP 409');
    p = clear(p, 'sessions');
    expect(lines(p)).toEqual(['알림 처리를 서버에 보내지 못했다: HTTP 409']);
    expect(clear(p, 'inbox')).toBe(p);
  });

  it('shows one line per source and a button to close, and nothing when all is well', () => {
    expect(renderToStaticMarkup(<ProblemList problems={{}} onDismiss={() => {}} />)).toBe('');
    const html = renderToStaticMarkup(<ProblemList problems={{ inbox: 'a', sessions: 'b' }} onDismiss={() => {}} />);
    expect(html).toContain('role="alert"');
    expect(html.match(/<li>/g)).toHaveLength(2);
    expect(html).toContain('<button>닫기</button>');
  });
});

const T0 = Date.parse('2026-10-11T00:00:00Z');

const item = (over: Partial<InboxItem> = {}): InboxItem => ({
  id: 'inb_a',
  bucket: 3,
  kind: 'notice',
  source_kind: 'ntc',
  source_id: 'ntc_a',
  summary: '두 세션이 같은 파일을 고침: docs/a.md',
  detail: { path: 'docs/a.md', sessions: [{ session_id: 'ses_0123456789abcdef', executor: 'claude_code', branch: 'main' }] },
  wait_kind: null,
  repeat_count: 0,
  waiting_since: new Date(T0).toISOString(),
  notify: 'immediate',
  ...over,
});

describe('groupByBucket', () => {
  it('keeps the five buckets in order and the longest wait first', () => {
    const groups = groupByBucket([
      item({ id: 'inb_new', waiting_since: new Date(T0 + 60_000).toISOString() }),
      item({ id: 'inb_old' }),
      item({ id: 'inb_wait', bucket: 2, kind: 'session_waiting' }),
    ]);
    expect([...groups.keys()]).toEqual([1, 2, 3, 4, 5]);
    expect(groups.get(3)!.map((i) => i.id)).toEqual(['inb_old', 'inb_new']);
    expect(groups.get(2)!.map((i) => i.id)).toEqual(['inb_wait']);
    expect(groups.get(1)).toEqual([]);
  });
});

describe('newImmediate', () => {
  it('raises nothing on the first poll, then only new immediate items', () => {
    const first = [item({ id: 'inb_1' })];
    expect(newImmediate(null, first)).toEqual([]);
    const seen = new Set(first.map((i) => i.id));
    const next = [...first, item({ id: 'inb_2' }), item({ id: 'inb_3', notify: 'digest' })];
    expect(newImmediate(seen, next).map((i) => i.id)).toEqual(['inb_2']);
  });
});

describe('labels', () => {
  it('tells waits, repositories and ids apart', () => {
    expect(since(new Date(T0).toISOString(), T0 + 30_000)).toBe('방금');
    expect(since(new Date(T0).toISOString(), T0 + 5 * 60_000)).toBe('5분 전');
    expect(since(new Date(T0).toISOString(), T0 + 3 * 3_600_000)).toBe('3시간 전');
    expect(repoName({ repository: { remote: 'https://github.com/hanumoka/zm-docs.git' }, repo_key: 'git:abc' })).toBe('hanumoka/zm-docs');
    expect(repoName({ repository: { remote: 'github.com:hanumoka/zm-baton.git' }, repo_key: null })).toBe('hanumoka/zm-baton');
    expect(repoName({ repository: null, repo_key: 'git:0123456789abcdef0123' })).toBe('git:0123456789ab');
    expect(shortId('ses_0123456789abcdef')).toBe('ses_0123…cdef');
  });
});

describe('Inbox', () => {
  it('shows all five buckets, the conflict file and the waiting session, and how long each has waited', () => {
    const html = renderToStaticMarkup(
      <Inbox
        items={[item(), item({ id: 'inb_w', bucket: 2, kind: 'session_waiting', source_kind: 'ses', notify: 'digest', detail: { branch: 'work/x' } })]}
        error={null}
        sessions={[]}
        now={T0 + 120_000}
        onChanged={() => {}}
        onError={() => {}}
        onReload={() => {}}
      />,
    );
    for (const label of ['권한 요청', '멈춘 AI의 질문', '내 작업과 겹친 충돌', '위험 목록 검토', '표본 검토']) {
      expect(html).toContain(label);
    }
    expect(html).toContain('docs/a.md');
    expect(html).toContain('work/x');
    expect(html).toContain('2분째');
    expect(html).toContain('바로 알림');
    expect(html).toContain('묶어 알림');
  });
});

describe('Sessions', () => {
  const session = (over: Partial<SessionView> = {}): SessionView => ({
    id: 'ses_0123456789abcdef',
    executor: 'claude_code',
    executor_version: '2.1.296',
    device_name: 'owner-pc',
    repository: { remote: 'https://github.com/hanumoka/zm-docs.git', prefix: '', branch: 'main' },
    repo_key: 'git:abc',
    current_work: '결정 문서 셋 쓰는 중',
    shared: true,
    current_work_at: new Date(T0).toISOString(),
    state: 'live',
    created_at: new Date(T0).toISOString(),
    last_seen_at: new Date(T0).toISOString(),
    touched_files: ['docs/a.md', 'docs/b.md'],
    conflicted_files: ['docs/a.md'],
    ...over,
  });

  const sessionList = (state: ListState, sessions: SessionView[] = []) =>
    renderToStaticMarkup(
      <SessionList
        state={state}
        groups={groupSessions(sessions, false)}
        head={{ base: '살아 있는 세션', count: sessions.length, narrowed: false }}
        selected={null}
        now={T0}
        onSelect={() => {}}
        onPick={() => {}}
        onOpen={() => {}}
        onShowEnded={() => {}}
        onClearFilters={() => {}}
        onReload={() => {}}
      />,
    );

  it('marks conflicted files and hides unshared work', () => {
    const html = sessionList({ kind: 'rows' }, [session(), session({ id: 'ses_ffffffffffffffff', shared: false, current_work: null, touched_files: [], conflicted_files: [] })]);
    expect(html).toContain('결정 문서 셋 쓰는 중');
    expect(html).toContain('하는 일을 공유하지 않는다');
    expect(html).toContain('겹침');
    expect(html).toContain('hanumoka/zm-docs');
    const detail = renderToStaticMarkup(<SessionDetail session={session()} inbox={[]} now={T0} />);
    expect(detail).toContain('<code>docs/<wbr/>a.md</code>');
    expect(detail).toContain('겹침');
  });

  it('says so when nothing is registered', () => {
    expect(sessionList({ kind: 'unused' })).toContain('등록된 세션이 없다');
  });
});
