import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { InboxDetail } from './inbox/InboxDetail';
import { InboxList } from './inbox/InboxList';
import { groupAside, inboxGroups, kindOf, RELAY_OFF_NOTE, rowTitle } from './inbox/inboxView';
import {
  canAnswer,
  choose,
  permissionAnswer,
  permissionView,
  questionAnswer,
  questionsOf,
  relayStatus,
  relayTitle,
  secondsLeft,
} from './inbox/relayView';
import { groupByBucket, visibleItems, waitingItems } from './inboxModel';
import { sessionsApi, type InboxItem, type SessionView } from './sessionsApi';
import { FocusItem } from './shell/Sidebar';

const T0 = Date.parse('2026-10-14T01:00:00Z');
const iso = (seconds: number) => new Date(T0 + seconds * 1000).toISOString();
const SES = 'ses_0123abcd-0000-7000-8000-00000000ad56';

// A relayed permission request as the server lists it (RelayService.start, PersonService.inbox).
const permission = (over: Partial<InboxItem> = {}): InboxItem => ({
  id: 'inb_p',
  bucket: 1,
  kind: 'permission_request',
  source_kind: 'ses',
  source_id: SES,
  summary: 'ses_0123…ad56(claude_code, main) · Bash: rm -rf build && npm run build',
  detail: {
    tool_name: 'Bash',
    command: 'rm -rf build && npm run build\necho done',
    description: 'Rebuild the web app',
    input: { command: 'rm -rf build && npm run build\necho done', description: 'Rebuild the web app' },
    work_location: { repo_key: 'git:0123', remote: 'https://github.com/hanumoka/zm-baton.git', branch: 'main', path: 'apps/web', in_repo: true },
    permission_mode: 'default',
  },
  wait_kind: null,
  repeat_count: 0,
  waiting_since: iso(-18),
  notify: 'immediate',
  state: 'open',
  relay_deadline: iso(42),
  relay_reason: null,
  answer: null,
  ...over,
});

const question = (over: Partial<InboxItem> = {}): InboxItem => ({
  ...permission(),
  id: 'inb_q',
  bucket: 2,
  kind: 'session_question',
  summary: 'ses_0123…ad56(claude_code, main) · Which color?',
  detail: {
    tool_name: 'AskUserQuestion',
    questions: [
      { question: 'Which color?', header: 'Color', multiSelect: false, options: [{ label: 'Red', description: 'warm' }, { label: 'Blue', description: 'cool' }] },
      { question: 'Which sizes?', header: 'Size', multiSelect: true, options: [{ label: 'S' }, { label: 'M' }, { label: 'L' }] },
    ],
  },
  ...over,
});

const live: SessionView = {
  id: SES,
  executor: 'claude_code',
  executor_version: null,
  device_name: 'owner-pc',
  repository: { remote: 'https://github.com/hanumoka/zm-baton.git', prefix: '', branch: 'main' },
  repo_key: 'git:0123',
  current_work: '첫 화면 중계를 만드는 중',
  shared: true,
  current_work_at: iso(-600),
  state: 'live',
  created_at: iso(-3600),
  last_seen_at: iso(-5),
  touched_files: [],
  conflicted_files: [],
};

const detail = (item: InboxItem) =>
  renderToStaticMarkup(<InboxDetail item={item} sessions={[live]} now={T0} onJudged={() => {}} onError={() => {}} />);

describe('relay state', () => {
  it('counts the seconds left and answers only while open, relayed and before the deadline', () => {
    expect(secondsLeft(permission(), T0)).toBe(42);
    expect(secondsLeft(permission({ relay_deadline: iso(-3) }), T0)).toBe(0);
    expect(secondsLeft(permission({ relay_deadline: null }), T0)).toBeNull();
    expect(canAnswer(permission(), T0)).toBe(true);
    expect(canAnswer(permission({ relay_deadline: iso(-1) }), T0)).toBe(false);
    expect(canAnswer(permission({ relay_deadline: null, relay_reason: 'secret_suspected' }), T0)).toBe(false);
    expect(canAnswer(permission({ state: 'answered' }), T0)).toBe(false);
  });

  it('says 허용 or 거절 only once the answer reached the hook, and always that the terminal may have answered first', () => {
    const answered = relayStatus(permission({ state: 'answered', answer: { decision: 'allow' } }), T0);
    expect(answered.label).toBe('전달하는 중');
    expect(answered.label).not.toContain('허용');
    const delivered = relayStatus(permission({ state: 'delivered', answer: { decision: 'allow' } }), T0);
    expect(delivered.label).toBe('전달됨: 허용');
    expect(delivered.note).toContain('터미널에서 먼저 답했으면 쓰이지 않았을 수 있다');
    const closed = relayStatus(permission({ state: 'closed', delivered_at: iso(5), answer: { decision: 'deny', reason: '아니다' } }), T0);
    expect(closed.label).toBe('전달됨: 거절(이유: 아니다)');
    expect(relayStatus(question({ state: 'delivered', answer: { answers: { 'Which color?': ['Blue'], 'Which sizes?': ['S', 'L'] } } }), T0).label).toBe(
      '전달됨: Blue / S, L',
    );
  });

  it('names how a relay ended, never as if the answer had been used', () => {
    const cases: [Partial<InboxItem>, string][] = [
      [{}, '42초 남음'],
      [{ relay_deadline: iso(-1) }, '이미 터미널로 넘어감'],
      [{ state: 'moved_to_terminal', relay_reason: 'deadline' }, '이미 터미널로 넘어감'],
      [{ state: 'moved_to_terminal', relay_reason: 'person' }, '터미널로 넘김'],
      [{ state: 'undelivered', answer: { decision: 'allow' } }, '전달 안 됨'],
      [{ state: 'closed', relay_reason: 'session_ended' }, '세션이 끝나 닫힘'],
      [{ state: 'closed', relay_reason: 'resumed' }, '세션이 다음으로 움직여 닫힘'],
      [{ relay_deadline: null, relay_reason: 'secret_suspected', detail: null }, '터미널에서 묻는 중'],
      [{ relay_deadline: null, relay_reason: 'too_large', detail: null }, '터미널에서 묻는 중'],
    ];
    for (const [over, label] of cases) expect(relayStatus(permission(over), T0).label, JSON.stringify(over)).toBe(label);
    expect(relayStatus(permission({ state: 'undelivered' }), T0).tone).toBe('fail');
    expect(relayStatus(question({ relay_deadline: null, relay_reason: 'not_visible' }), T0).note).toContain('첫 화면이 보이지 않아');
  });
});

describe('what a person judges', () => {
  it('shows the command in full, the executor note and where the session works', () => {
    const p = permissionView(permission())!;
    expect(p.command).toBe('rm -rf build && npm run build\necho done');
    expect(p.input).toBeNull(); // a shell command alone needs no second copy
    expect(p.location).toEqual({ remote: 'https://github.com/hanumoka/zm-baton.git', repoKey: 'git:0123', branch: 'main', path: 'apps/web', inRepo: true });
    const write = permissionView(permission({ detail: { tool_name: 'Write', file_path: 'docs/a.md', input: { file_path: 'docs/a.md', content: 'hello' } } }))!;
    expect(write.filePath).toBe('docs/a.md');
    expect(write.input).toContain('"content": "hello"');
    expect(relayTitle(permission())).toBe('Bash: rm -rf build && npm run build');
    expect(relayTitle(question())).toBe('Which color?');
    expect(relayTitle(permission({ relay_deadline: null, relay_reason: 'secret_suspected', detail: null }))).toBe('비밀값이 섞여 터미널에서 확인');
    expect(kindOf(permission())).toEqual({ mark: 'lock', label: '권한 요청' });
    expect(rowTitle(question(), null)).toBe('Which color?');
  });

  it('builds the answers: a reason only with a deny, one option per single question, every question answered', () => {
    expect(permissionAnswer('allow', '무시된다')).toEqual({ decision: 'allow' });
    expect(permissionAnswer('deny', '  ')).toEqual({ decision: 'deny' });
    expect(permissionAnswer('deny', ' 빌드 폴더는 지우지 않는다 ')).toEqual({ decision: 'deny', reason: '빌드 폴더는 지우지 않는다' });
    const qs = questionsOf(question());
    let c = choose({}, qs[0], 'Red');
    c = choose(c, qs[0], 'Blue');
    expect(c['Which color?']).toEqual(['Blue']);
    expect(questionAnswer(qs, c)).toEqual({ missing: 'Which sizes?' });
    c = choose(choose(choose(c, qs[1], 'S'), qs[1], 'L'), qs[1], 'S');
    expect(questionAnswer(qs, c)).toEqual({ answer: { answers: { 'Which color?': ['Blue'], 'Which sizes?': ['L'] } } });
  });
});

describe('the list', () => {
  it('keeps a relay that ended after the ones still waiting, and out of the count', () => {
    const ended = permission({ id: 'inb_old', state: 'moved_to_terminal', waiting_since: iso(-900) });
    const waiting = permission({ id: 'inb_new' });
    expect(groupByBucket([ended, waiting]).get(1)!.map((i) => i.id)).toEqual(['inb_new', 'inb_old']);
    expect(waitingItems([ended, waiting, permission({ id: 'inb_a', state: 'answered' })]).map((i) => i.id)).toEqual(['inb_new']);
  });

  it('leaves out the terminal wait that says the same as a relayed permission request', () => {
    const wait: InboxItem = { ...permission(), id: 'inb_w', bucket: 2, kind: 'session_waiting', wait_kind: 'permission_prompt', state: 'open', relay_deadline: undefined };
    expect(visibleItems([permission(), wait]).map((i) => i.id)).toEqual(['inb_p']);
    expect(visibleItems([permission({ state: 'delivered' }), wait]).map((i) => i.id)).toEqual(['inb_p', 'inb_w']);
    expect(visibleItems([permission({ source_id: 'ses_other' }), wait]).map((i) => i.id)).toEqual(['inb_p', 'inb_w']);
  });

  it('puts relays that ended under their own line, out of the bucket count and without an alert word', () => {
    const html = renderToStaticMarkup(
      <InboxList
        state={{ kind: 'rows' }}
        groups={inboxGroups([permission(), permission({ id: 'inb_d', state: 'delivered', answer: { decision: 'allow' } })])}
        head={{ base: '기다리는 것', count: 1, narrowed: false }}
        selected={null}
        sessions={[live]}
        now={T0}
        onSelect={() => {}}
        onPick={() => {}}
        onOpen={() => {}}
        onReload={() => {}}
      />,
    );
    expect(html).toMatch(/권한 요청 <span class="n num">1<\/span>/);
    expect(html).toMatch(/<h4 class="sub">방금 끝난 중계 <span class="n num">1<\/span><\/h4>/);
    expect(html.split('data-row="inb_d"')[1]).not.toContain('바로 알림');
  });

  it('says in bucket 1 that relays are off and why, and then no focus mode note about relays', () => {
    const [b1, b2, b3] = inboxGroups([]);
    expect(groupAside(b1, 0, true, false)).toBe(RELAY_OFF_NOTE);
    expect(RELAY_OFF_NOTE).toBe('꺼져 있다(사람 인증이 생기기 전에는 기본 꺼짐)');
    expect(groupAside(b2, 0, true, false)).toBe('기다리는 세션이 없다');
    expect(groupAside(b3, 1, true, false)).toBeUndefined();
    expect(groupAside(b1, 0, false, null)).toBe('권한 요청이 없다'); // before the server said
    expect(groupAside(b1, 2, true, true)).toBe('집중 모드: 새 요청은 터미널에서');
    const html = renderToStaticMarkup(
      <InboxList
        state={{ kind: 'rows' }}
        groups={inboxGroups([])}
        head={{ base: '기다리는 것', count: 0, narrowed: false }}
        selected={null}
        sessions={[live]}
        now={T0}
        relayOn={false}
        onSelect={() => {}}
        onPick={() => {}}
        onOpen={() => {}}
        onReload={() => {}}
      />,
    );
    expect(html).toContain('<span class="grp-r">꺼져 있다(사람 인증이 생기기 전에는 기본 꺼짐)</span>');
  });

  it('shows the seconds left on the row, and the focus mode note in the relaying buckets', () => {
    const html = renderToStaticMarkup(
      <InboxList
        state={{ kind: 'rows' }}
        groups={inboxGroups([permission()])}
        head={{ base: '기다리는 것', count: 1, narrowed: false }}
        selected="inb_p"
        sessions={[live]}
        now={T0}
        focusOn
        onSelect={() => {}}
        onPick={() => {}}
        onOpen={() => {}}
        onReload={() => {}}
      />,
    );
    expect(html).toContain('Bash: rm -rf build &amp;&amp; npm run build');
    expect(html).toContain('42초 남음');
    expect(html).toContain('집중 모드: 새 요청은 터미널에서');
    expect(html).toContain('집중 모드: 새 질문은 터미널에서');
  });
});

describe('the detail', () => {
  it('a waiting permission request: the whole command, what the executor said, where, and allow, deny or terminal', () => {
    const html = detail(permission());
    expect(html).toContain('AI 세션이 Bash 권한을 묻는다');
    expect(html).toContain('<pre class="ib-cmd"><code>rm -rf build &amp;&amp; npm run build\necho done</code></pre>');
    expect(html).toContain('Rebuild the web app');
    expect(html).toContain('apps/web');
    expect(html).toContain('42초 남음');
    expect(html).toMatch(/<button type="button" class="btn btn-primary">허용<small>/);
    expect(html).toContain('>거절<small>');
    expect(html).toContain('터미널에서 답하기');
    expect(html).toContain('터미널에도 같은 질문이 떠 있다. 먼저 답한 쪽이 쓰인다.');
    expect(html).toContain('조직의 거부 규칙은 넘지 못한다');
  });

  it('a delivered answer: no buttons, 전달됨 with the reminder', () => {
    const html = detail(permission({ state: 'delivered', answer: { decision: 'allow' }, delivered_at: iso(2) }));
    expect(html).toContain('전달됨: 허용');
    expect(html).toContain('터미널에서 먼저 답했으면 쓰이지 않았을 수 있다');
    expect(html).not.toContain('>허용<small>');
  });

  it('past the deadline or not relayed: what happened and the way to the session, no answer buttons', () => {
    const late = detail(permission({ relay_deadline: iso(-1) }));
    expect(late).toContain('이미 터미널로 넘어감');
    expect(late).not.toContain('>허용<small>');
    expect(late).toContain('세션 현황에서 그 세션 열기');
    const secret = detail(permission({ relay_deadline: null, relay_reason: 'secret_suspected', detail: null, summary: 'ses_0123…ad56 · 비밀값이 섞여 터미널에서 확인' }));
    expect(secret).toContain('비밀값이 섞인 권한 요청은 터미널에서 확인한다');
    expect(secret).toContain('비밀값으로 보이는 것이 있어 내용을 서버로 보내지 않았다');
    expect(secret).not.toContain('ib-cmd');
    expect(detail(permission({ state: 'undelivered', answer: { decision: 'allow' } }))).toContain('전달 안 됨');
  });

  it('a relayed question: its options to choose, the send button locked until every question is answered', () => {
    const html = detail(question());
    expect(html).toContain('AI 세션이 선택형 질문을 묻는다');
    expect(html).toContain('type="radio"');
    expect(html).toContain('type="checkbox"');
    expect(html).toContain('<span class="ib-opt-l">Blue</span><span class="ib-opt-d">cool</span>');
    expect(html).toMatch(/<button type="button" class="btn btn-primary" disabled="">답 보내기/);
    expect(html).toContain('「Which color?」의 답을 골라야 보낼 수 있다');
    expect(html).toContain('터미널로 넘기기');
  });

  it('a question that was not relayed shows what it asks, read only', () => {
    const html = detail(question({ relay_deadline: null, relay_reason: 'not_visible' }));
    expect(html).toContain('묻는 것');
    expect(html).not.toContain('type="radio"');
    expect(html).toContain('첫 화면이 보이지 않아');
  });
});

describe('focus mode and the server calls', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('the toggle says what pressing it does, and hides until the server said', () => {
    expect(renderToStaticMarkup(<FocusItem focus={false} onToggle={() => {}} rail={false} />)).toContain('집중 모드 켜기');
    const on = renderToStaticMarkup(<FocusItem focus onToggle={() => {}} rail={false} />);
    expect(on).toContain('집중 모드 끄기');
    expect(on).toContain('aria-pressed="true"');
    expect(renderToStaticMarkup(<FocusItem focus={null} onToggle={() => {}} rail={false} />)).toBe('');
  });

  it('a late answer comes back with the server reason', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ message: '이미 터미널로 넘어갔다. 기한이 지났다' }), { status: 409 })));
    await expect(sessionsApi.answerRelay('inb_p', { decision: 'allow' })).rejects.toThrow('HTTP 409: 이미 터미널로 넘어갔다. 기한이 지났다');
  });

  it('sends the answer and the visibility to the right places', async () => {
    const fetch = vi.fn(async () => new Response(JSON.stringify({ id: 'inb_q', state: 'answered' }), { status: 200 }));
    vi.stubGlobal('fetch', fetch);
    await sessionsApi.answerRelay('inb_q', { answers: { 'Which color?': ['Blue'] } });
    await sessionsApi.setFirstScreen(true);
    await sessionsApi.relayToTerminal('inb_q');
    const calls = fetch.mock.calls as unknown as [string, RequestInit][];
    expect(calls.map(([path, init]) => `${init.method} ${path} ${init.body ?? ''}`)).toEqual([
      'POST /api/inbox/inb_q/answer {"answers":{"Which color?":["Blue"]}}',
      'PUT /api/me/first-screen {"visible":true}',
      'POST /api/inbox/inb_q/to-terminal ',
    ]);
  });
});
