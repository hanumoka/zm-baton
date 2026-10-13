import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { executorLabel, inboxHref, sessionHref } from './inboxModel';
import type { InboxItem, SessionView } from './sessionsApi';
import { SessionChipRow } from './sessions/SessionChips';
import { SessionDetail } from './sessions/SessionDetail';
import { SessionList } from './sessions/SessionList';
import { Sessions } from './sessions/Sessions';
import {
  conditions,
  conflictItem,
  filterSessions,
  groupSessions,
  listState,
  matches,
  NO_FILTERS,
  readView,
  repoOptions,
  rowOrder,
  workLine,
  writeView,
  type ListState,
  type SessionsView,
} from './sessions/sessionView';
import { screenFromHash } from './shell/screens';

const T0 = Date.parse('2026-10-14T00:00:00Z');
const iso = (minutes: number) => new Date(T0 + minutes * 60_000).toISOString();

const session = (over: Partial<SessionView> = {}): SessionView => ({
  id: 'ses_0123abcd-0000-7000-8000-00000000ad56',
  executor: 'claude_code',
  executor_version: null,
  device_name: 'owner-pc',
  repository: { remote: 'https://github.com/hanumoka/zm-docs.git', prefix: '', branch: 'main' },
  repo_key: 'git:docs',
  current_work: '결정 문서 셋 쓰는 중',
  shared: true,
  current_work_at: iso(-30),
  state: 'live',
  created_at: iso(-120),
  last_seen_at: iso(-2),
  touched_files: ['docs/a.md', 'docs/b.md'],
  conflicted_files: ['docs/a.md'],
  ...over,
});

const live = session();
const quiet = session({ id: 'ses_quiet', shared: false, current_work: null, current_work_at: null, conflicted_files: [], last_seen_at: iso(-1) });
const baton = session({
  id: 'ses_baton',
  repository: { remote: 'git@github.com:hanumoka/zm-baton.git', prefix: 'apps/web/', branch: 'work/web-sessions-screen' },
  repo_key: 'git:baton',
  current_work: '세션 화면을 다시 짓는다',
  conflicted_files: [],
  last_seen_at: iso(-10),
});
const ended = session({ id: 'ses_ended', state: 'ended', conflicted_files: [], last_seen_at: iso(-300) });
const expired = session({ id: 'ses_expired', state: 'expired', conflicted_files: [], last_seen_at: iso(-200) });
const all = [live, quiet, baton, ended, expired];

const view = (over: Partial<SessionsView> = {}): SessionsView => ({ ...NO_FILTERS, selected: null, ...over });

// The conflict on docs/a.md as the first screen has it.
const notice: InboxItem = {
  id: 'inb_c',
  bucket: 3,
  kind: 'notice',
  source_kind: 'ntc',
  source_id: 'ntc_c',
  summary: '두 세션이 같은 파일을 고침: docs/a.md',
  detail: { path: 'docs/a.md', sessions: [{ session_id: live.id, executor: 'claude_code', branch: 'main' }, { session_id: 'ses_other', executor: 'codex' }] },
  wait_kind: null,
  repeat_count: 0,
  waiting_since: iso(-5),
  notify: 'immediate',
};

function list(state: ListState, items: SessionView[] = [], showEnded = false, selected: string | null = null) {
  return renderToStaticMarkup(
    <SessionList
      state={state}
      groups={groupSessions(items, showEnded)}
      head={{ base: '살아 있는 세션', count: items.length, narrowed: false }}
      selected={selected}
      now={T0}
      onSelect={() => {}}
      onPick={() => {}}
      onOpen={() => {}}
      onShowEnded={() => {}}
      onClearFilters={() => {}}
      onReload={() => {}}
    />,
  );
}

describe('groups', () => {
  it('puts live sessions first, newest signal first, and the rest only with ended sessions shown', () => {
    expect(groupSessions(all, false).map((g) => [g.label, g.items.map((s) => s.id)])).toEqual([['살아 있음', ['ses_quiet', live.id, 'ses_baton']]]);
    expect(groupSessions(all, true).map((g) => [g.label, g.items.map((s) => s.id)])).toEqual([
      ['살아 있음', ['ses_quiet', live.id, 'ses_baton']],
      ['그 밖', ['ses_expired', 'ses_ended']],
    ]);
    expect(rowOrder(groupSessions(all, true))).toHaveLength(5);
  });

  it('draws the group heads with counts, what 그 밖 holds, and an empty group with its line', () => {
    const html = list({ kind: 'rows' }, [ended], true);
    expect(html).toMatch(/살아 있음 <span class="n num">0<\/span>/);
    expect(html).toContain('살아 있는 세션이 없다');
    expect(html).toMatch(/그 밖 <span class="n num">1<\/span><span class="grp-r">끝남, 등록 끔, 만료<\/span>/);
    expect(html).toContain('살아 있는지로 묶음');
  });
});

describe('rows', () => {
  it('shows the executor and what the session works on, and a conflict as a shape and a word', () => {
    const html = list({ kind: 'rows' }, [live], false, live.id);
    expect(html).toContain('<span class="lrow-state">Claude Code</span><span class="lrow-title">결정 문서 셋 쓰는 중</span>');
    expect(html).toMatch(/<span class="ss-ovl"><svg class="ico"[^>]*>.*?<\/svg>겹침 <span class="num">1<\/span><\/span>/);
    expect(html).toMatch(new RegExp(`data-row="${live.id}" aria-current="true" tabindex="0"`));
  });

  it('says a session does not share its work, or has not sent it yet, instead of an empty title', () => {
    expect(workLine(quiet)).toEqual({ text: '하는 일을 공유하지 않는다', shown: false });
    expect(workLine(session({ current_work: null }))).toEqual({ text: '하는 일을 아직 올리지 않았다', shown: false });
    expect(list({ kind: 'rows' }, [quiet])).toContain('<span class="lrow-title ss-none">하는 일을 공유하지 않는다</span>');
  });

  it('puts the repository, branch, last signal and device on the second line, and the state when not live', () => {
    const html = list({ kind: 'rows' }, [live, expired], true);
    expect(html).toContain(
      '<span class="lrow-proj">hanumoka/zm-docs</span><span class="ss-branch">main</span><span class="num">마지막 신호 2분 전</span><span>owner-pc</span>',
    );
    expect(html).toContain('<span class="ss-state">만료</span>');
    expect(html).not.toContain(' · ');
  });

  it('names the executors as products', () => {
    expect(executorLabel('claude_code')).toBe('Claude Code');
    expect(executorLabel('codex')).toBe('Codex');
    expect(executorLabel('new_cli')).toBe('new_cli');
  });
});

describe('filters and search', () => {
  it('offers only the repositories the listed sessions are in, most sessions first, and keeps a chosen one', () => {
    expect(repoOptions(all, '')).toEqual([
      { id: 'git:docs', label: 'hanumoka/zm-docs', count: 4 },
      { id: 'git:baton', label: 'hanumoka/zm-baton', count: 1 },
    ]);
    expect(repoOptions([], 'git:gone').map((r) => r.id)).toEqual(['git:gone']);
    expect(repoOptions([session({ repository: null, repo_key: null })], '')[0]).toEqual({ id: 'none', label: '저장소 없음', count: 1 });
  });

  it('finds by session id, by shared work and by branch, never by work that is not shared', () => {
    expect(matches(live, 'AD56')).toBe(true);
    expect(matches(live, '결정 문서')).toBe(true);
    expect(matches(baton, 'web-sessions')).toBe(true);
    expect(matches(session({ shared: false, current_work: '비밀' }), '비밀')).toBe(false);
    expect(matches(live, '없는 말')).toBe(false);
  });

  it('applies the repository, the search and live-or-not on the client', () => {
    expect(filterSessions(all, NO_FILTERS, '').map((s) => s.id)).toEqual([live.id, 'ses_quiet', 'ses_baton']);
    expect(filterSessions(all, { repo: 'git:baton', ended: false }, '').map((s) => s.id)).toEqual(['ses_baton']);
    expect(filterSessions(all, { repo: '', ended: true }, 'ses_e').map((s) => s.id)).toEqual(['ses_ended', 'ses_expired']);
    expect(conditions({ repo: 'git:baton', ended: false }, ' web ', repoOptions(all, ''))).toEqual(['저장소 hanumoka/zm-baton', '찾는 말 「web」', '끝난 세션 숨김']);
  });

  it('draws the repository chip with counts and the ended chip, and no search box of its own', () => {
    const html = renderToStaticMarkup(<SessionChipRow filters={NO_FILTERS} onFilters={() => {}} repos={repoOptions(all, '')} />);
    expect(html).toContain('aria-label="세션 거르기"');
    expect(html).toContain('<option value="git:docs">hanumoka/zm-docs (4)</option>');
    expect(html).toContain('끝난 세션 보기');
    expect(html).not.toContain('type="search"');
  });
});

describe('empty states', () => {
  const base = { items: [] as SessionView[] | null, visible: 0, error: null as string | null, filters: NO_FILTERS, query: '', endedExist: null as boolean | null, repos: [] };

  it('tells loading, a failed read, nothing registered, nothing live, filtered to nothing and rows apart', () => {
    expect(listState({ ...base, items: null })).toEqual({ kind: 'loading' });
    expect(listState({ ...base, items: null, error: 'HTTP 503' })).toEqual({ kind: 'error', message: 'HTTP 503' });
    expect(listState(base)).toEqual({ kind: 'unused' });
    expect(listState({ ...base, endedExist: true })).toEqual({ kind: 'no-live' });
    expect(listState({ ...base, filters: { repo: '', ended: true }, endedExist: true })).toEqual({ kind: 'unused' });
    expect(listState({ ...base, query: 'ad56' })).toEqual({ kind: 'filtered', conditions: ['찾는 말 「ad56」', '끝난 세션 숨김'] });
    expect(listState({ ...base, items: [live], visible: 1, error: 'HTTP 503' })).toEqual({ kind: 'rows' });
  });

  it('says how a session gets registered, what is filtered, what failed, and offers ended sessions when none is live', () => {
    const unused = list({ kind: 'unused' });
    expect(unused).toContain('empty-state is-unused');
    expect(unused).toContain('등록된 세션이 없다');
    expect(unused).toContain('<code>zm-baton session register</code>');

    const noLive = list({ kind: 'no-live' });
    expect(noLive).toContain('지금 살아 있는 세션이 없다');
    expect(noLive).toContain('끝난 세션 보기');

    const filtered = list({ kind: 'filtered', conditions: ['저장소 hanumoka/zm-baton'] });
    expect(filtered).toContain('거른 조건에 맞는 세션이 없다');
    expect(filtered).toContain('걸러 본 조건: 저장소 hanumoka/zm-baton');
    expect(filtered).toContain('거르기 풀기');

    const error = list({ kind: 'error', message: 'HTTP 503' });
    expect(error).toContain('empty-state is-error');
    expect(error).toContain('세션 목록을 읽지 못했다');
    expect(error).toContain('다시 읽기');

    expect(list({ kind: 'loading' })).toContain('aria-busy="true"');
  });
});

describe('detail', () => {
  const detail = (s: SessionView, inbox: InboxItem[] | null = [notice]) => renderToStaticMarkup(<SessionDetail session={s} inbox={inbox} now={T0} />);

  it('shows the whole session id in monospace, executor and version, device, repository, branch and times', () => {
    const html = detail(baton);
    expect(html).toContain('<h2 id=');
    expect(html).toContain('세션 현황</span><span aria-hidden="true">/</span><code>ses_baton</code>');
    expect(html).toContain('<code class="ss-id">ses_baton</code>');
    expect(html).toContain('판을 알리지 않았다');
    expect(detail(session({ executor_version: '2.1.296' }))).toContain('Claude Code<code>2.1.296</code>');
    expect(html).toContain('<dd>owner-pc</dd>');
    expect(html).toContain('hanumoka/zm-baton<code class="ss-quiet">apps/web/</code>');
    expect(html).toContain('<code>work/web-sessions-screen</code>');
    for (const label of ['하는 일 올림', '등록', '마지막 신호']) expect(html).toContain(`<dt>${label}</dt>`);
    expect(html).toContain('30분 전');
    expect(html).toContain('2시간 전');
  });

  it('links an overlapping file to its item on the first screen, and says when the conflict was only acknowledged', () => {
    const html = detail(live);
    expect(html).toContain('겹친 파일 <span class="num">1</span>');
    expect(html).toContain(`href="${inboxHref('inb_c')}"`);
    expect(html).toContain('나를 기다리는 것에서 판정하기');
    const acknowledged = detail(live, []);
    expect(acknowledged).toContain('확인함으로 남은 충돌이다');
    expect(acknowledged).not.toContain('href="#inbox');
    expect(conflictItem([notice], live.id, 'docs/a.md')?.id).toBe('inb_c');
    expect(conflictItem([notice], 'ses_nobody', 'docs/a.md')).toBeNull();
    expect(conflictItem([notice], live.id, 'docs/b.md')).toBeNull();
  });

  it('lists the files it touched last, the overlapping ones marked, and what the server cannot see', () => {
    const html = detail(live);
    expect(html).toContain('최근 고친 파일 <span class="num">2</span>');
    expect(html).toMatch(/<li class="is-ovl"><code>docs\/<wbr\/>a\.md<\/code><span class="ss-ovl">/);
    expect(html).toContain('<li><code>docs/<wbr/>b.md</code></li>');
    expect(html).toContain('등록하지 않았거나 등록을 끈 세션은 여기 없고 충돌 판정에서도 빠진다. 셸 명령으로 바꾼 파일은 보고되지 않는다.');
    expect(detail(quiet)).toContain('다른 세션과 겹친 파일이 없다.');
    expect(detail(quiet)).toContain('공유하지 않아 보이지 않는다');
  });
});

describe('address and links', () => {
  it('keeps the filters and the selected session in the address and reads the first screen links', () => {
    const v = view({ repo: 'git:baton', ended: true, selected: 'ses_ended' });
    expect(writeView(v)).toBe('#sessions?repo=git%3Abaton&ended=1&item=ses_ended');
    expect(readView(writeView(v))).toEqual(v);
    expect(writeView(view())).toBe('#sessions');
    expect(readView('#sessions')).toEqual(view());
    // The first screen links a session the same way.
    expect(readView(sessionHref('ses_ended', false))).toEqual(view({ ended: true, selected: 'ses_ended' }));
    expect(readView(sessionHref(live.id, true))).toEqual(view({ selected: live.id }));
    expect(screenFromHash(writeView(v))).toBe('sessions');
  });
});

describe('session screen', () => {
  it('lays out the list and the detail, with no description paragraph and no table', () => {
    const html = renderToStaticMarkup(<Sessions onError={() => {}} onRead={() => {}} chipHost={null} query="" onClearQuery={() => {}} inbox={null} now={T0} />);
    expect(html).toMatch(/^<main class="panes ss"><h1 class="sr">세션 현황<\/h1><section class="pane-list"/);
    expect(html).toContain('<section class="pane-detail" aria-label="세션 상세">');
    expect(html).toContain('aria-busy="true"');
    expect(html).not.toContain('<header>');
    expect(html).not.toContain('<table');
  });
});
