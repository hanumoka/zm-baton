import { useEffect, useRef, useState } from 'react';
import { Accounts } from './accounts/Accounts';
import { LOCAL_FALLBACK } from './auth/AuthGate';
import type { AuthState } from './authApi';
import { Audit } from './audit/Audit';
import { App as RunTest } from './App';
import { Decisions } from './decisions/Decisions';
import { Documents } from './documents/Documents';
import { BUCKETS, inboxHref, newImmediate, waitingItems } from './inboxModel';
import { Inbox } from './inbox/Inbox';
import { isRelay, relayed } from './inbox/relayView';
import { Kit } from './kit/Kit';
import { Me } from './me/Me';
import { Org } from './org/Org';
import { RiskRules } from './review/RiskRules';
import { clear, fail, lines, type Problems, type Source } from './problems';
import { Sessions } from './sessions/Sessions';
import { sessionsApi, type InboxItem, type MeView, type SessionView } from './sessionsApi';
import { CommandBar, type Find } from './shell/CommandBar';
import { isCommand, screenFromHash, type ScreenId } from './shell/screens';
import { readSideOpen, writeSideOpen } from './shell/sidePref';
import { Sidebar, type Permission } from './shell/Sidebar';
import { TabBar } from './shell/TabBar';
import { usePoll } from './usePoll';
import { Work } from './work/Work';

const currentHash = () => (typeof window === 'undefined' ? '' : window.location.hash);

/** What the command line says on a screen that finds through it; the words mean what the server's `q` matches. */
export const FINDS: Partial<Record<ScreenId, Pick<Find, 'placeholder' | 'label'>>> = {
  work: { placeholder: '작업 찾기, 화면 이동은 > 로 시작', label: '작업 찾기(제목·번호·다음 행동) 또는 화면 이동' },
  decisions: { placeholder: '결정 찾기, 화면 이동은 > 로 시작', label: '결정 찾기(번호·질문·결정) 또는 화면 이동' },
  documents: { placeholder: '문서 찾기, 화면 이동은 > 로 시작', label: '문서 찾기(제목·Git 경로) 또는 화면 이동' },
  sessions: { placeholder: '세션 찾기, 화면 이동은 > 로 시작', label: '세션 찾기(세션 번호·하는 일·브랜치) 또는 화면 이동' },
};

/**
 * The Web screen: a sidebar on the left (a bottom tab bar on narrow screens), the command line
 * on top of the content, and the screen under it. The first screen polls even while another
 * screen is open, because browser notifications come from it (contract "알림과 집중 모드" 3:
 * notifications need the page open). Live sessions are read everywhere too, for the sidebar count
 * and for the first screen to say what a related session works on; the session screen reads its own list.
 * The header's height is measured into `--h-tool`, so a screen can keep a pane in view under it
 * and the folded sidebar can start its items below it (their tooltips then never cover the chips).
 */
export function Shell({ auth = LOCAL_FALLBACK, onAuthChanged = () => {} }: { auth?: AuthState; onAuthChanged?: () => void } = {}) {
  const [screen, setScreen] = useState<ScreenId>(() => screenFromHash(currentHash()));
  const [sideOpen, setSideOpen] = useState(() => readSideOpen());
  /** What waits for me; null before the first read. */
  const [items, setItems] = useState<InboxItem[] | null>(null);
  const [inboxError, setInboxError] = useState<string | null>(null);
  /** Live sessions; null before the first read. */
  const [sessions, setSessions] = useState<SessionView[] | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const [problems, setProblems] = useState<Problems>({});
  const [permission, setPermission] = useState<Permission>(() => (typeof Notification === 'undefined' ? 'unsupported' : Notification.permission));
  const seen = useRef<Set<string> | null>(null);
  /** The person's settings (focus mode); null until the server answered. */
  const [me, setMe] = useState<MeView | null>(null);
  const focusOn = me?.focus_on ?? false;
  const focusRef = useRef(false);
  focusRef.current = focusOn;
  /** Alerts held back while focus mode is on, shown when it ends (contract 「알림과 집중 모드」 4). */
  const held = useRef<Map<string, InboxItem>>(new Map());
  const content = useRef<HTMLDivElement>(null);
  const app = useRef<HTMLDivElement>(null);
  const tool = useRef<HTMLElement>(null);
  const [chipHost, setChipHost] = useState<HTMLDivElement | null>(null);
  const [findQ, setFindQ] = useState('');
  const [refresh, setRefresh] = useState(0);
  const failed = (source: Source) => (e: unknown) => setProblems((p) => fail(p, source, e));
  const cleared = (source: Source) => setProblems((p) => clear(p, source));

  useEffect(() => {
    const onHash = () => {
      const next = screenFromHash(window.location.hash);
      setScreen(next);
      // A screen that is not shown is not read: its old failure says nothing now.
      if (next !== 'work') cleared('work');
      if (next !== 'decisions') cleared('decisions');
      if (next !== 'documents') cleared('documents');
      if (next !== 'risk') cleared('risk');
      if (next !== 'accounts') cleared('accounts');
      if (next !== 'me') cleared('me');
      if (next !== 'org') cleared('org');
      if (next !== 'audit') cleared('audit');
    };
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  // A relay counts down in seconds; otherwise the clock only moves 「3분째」 along.
  const counting = (items ?? []).some((i) => isRelay(i) && relayed(i) && (i.state ?? 'open') === 'open');
  useEffect(() => {
    setNow(Date.now());
    const clock = setInterval(() => setNow(Date.now()), counting ? 1000 : 15_000);
    return () => clearInterval(clock);
  }, [counting]);

  // The first screen tells the server while its tab is visible, so a question is relayed only then
  // (contract relay 12). A signal lasts 30 seconds on the server and goes every 15 seconds here.
  useEffect(() => {
    if (typeof document === 'undefined') return;
    let told: boolean | null = null;
    const tell = (visible: boolean) => {
      told = visible;
      sessionsApi.setFirstScreen(visible).catch(() => {});
    };
    const tick = () => {
      const visible = screen === 'inbox' && document.visibilityState === 'visible';
      if (visible || told !== false) tell(visible);
    };
    tick();
    const id = setInterval(tick, 15_000);
    document.addEventListener('visibilitychange', tick);
    return () => {
      clearInterval(id);
      document.removeEventListener('visibilitychange', tick);
      if (told) tell(false);
    };
  }, [screen]);

  usePoll(
    async (alive, signal) => {
      const settings = await sessionsApi.me(signal);
      if (alive()) setMe(settings);
    },
    60_000,
    [],
    () => {}, // focus mode stays as last read; the first screen's own poll reports a stopped server
  );

  // Words typed to find on one screen mean nothing on the next.
  useEffect(() => setFindQ(''), [screen]);

  useEffect(() => {
    const header = tool.current;
    const root = app.current;
    if (!header || !root || typeof ResizeObserver === 'undefined') return;
    const measure = () => root.style.setProperty('--h-tool', `${header.offsetHeight}px`);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(header);
    return () => observer.disconnect();
  }, []);

  // While a relay waits for an answer or is on its way, the list reads faster so its state shows soon.
  const relaying = (items ?? []).some((i) => isRelay(i) && relayed(i) && (i.state === 'open' || i.state === 'answered'));
  usePoll(
    async (alive, signal) => {
      const list = await sessionsApi.inbox(signal);
      if (!alive()) return;
      const waiting = waitingItems(list);
      for (const item of newImmediate(seen.current, waiting)) {
        // Focus mode: only a conflict with my live work alerts at once; the rest wait for its end.
        if (focusRef.current && item.bucket !== 3) held.current.set(item.id, item);
        else notify(item);
      }
      seen.current = new Set(waiting.map((i) => i.id));
      for (const id of held.current.keys()) if (!seen.current.has(id)) held.current.delete(id);
      setItems(list);
      setInboxError(null);
      cleared('inbox');
    },
    relaying ? 1000 : 3000,
    [refresh, relaying],
    (e) => {
      setInboxError(e instanceof Error ? e.message : String(e));
      failed('inbox')(e);
    },
  );

  usePoll(
    async (alive, signal) => {
      const list = await sessionsApi.sessions(false, signal);
      if (!alive()) return;
      setSessions(list);
      cleared('sessions');
    },
    screen === 'inbox' ? 5000 : 10_000,
    [screen === 'inbox', refresh],
    failed('sessions'),
  );

  function go(id: ScreenId) {
    window.location.hash = id;
    content.current?.focus();
  }

  function toggleSide() {
    const next = !sideOpen;
    setSideOpen(next);
    writeSideOpen(next);
  }

  async function toggleFocus() {
    try {
      const next = await sessionsApi.setFocus(!focusOn);
      setMe(next);
      if (!next.focus_on) {
        for (const item of held.current.values()) notify(item);
        held.current.clear();
      }
    } catch (e) {
      failed('judge')(e);
    }
  }

  async function askPermission() {
    if (typeof Notification === 'undefined') return;
    setPermission(await Notification.requestPermission());
  }

  const counts = { inbox: items === null ? 0 : waitingItems(items).length, sessions: (sessions ?? []).filter((s) => s.state === 'live').length };
  // The work, decisions, documents and session screens find through the command line; a leading > still opens a screen.
  const finds = FINDS[screen];
  const find: Find | undefined = finds && {
    value: findQ,
    onChange: setFindQ,
    ...finds,
    onSubmit: () => content.current?.querySelector<HTMLElement>('[data-row][tabindex="0"]')?.focus(),
  };

  return (
    <div className="app" ref={app} data-side={sideOpen ? 'open' : 'rail'}>
      {/* The column carries the sidebar's colour to the bottom of a long page; the sidebar itself stays in view. */}
      <div className="side-col">
        <Sidebar
          current={screen}
          counts={counts}
          open={sideOpen}
          onToggle={toggleSide}
          permission={permission}
          onAskPermission={askPermission}
          focus={me?.focus_on ?? null}
          onToggleFocus={() => void toggleFocus()}
        />
      </div>
      <div className="main">
        <header className="tool" ref={tool}>
          <CommandBar onGo={go} find={find} />
          {/* The screen's filter chips go here (DESIGN.md 「화면 배치」 2); empty until a screen fills it. */}
          <div className="chips" id="screen-chips" ref={setChipHost} />
        </header>
        <ProblemList problems={problems} onDismiss={() => setProblems({})} />
        <div className="content" ref={content} tabIndex={-1}>
          {screen === 'inbox' && (
            <Inbox
              items={items}
              error={inboxError}
              sessions={sessions}
              now={now}
              onChanged={() => {
                cleared('judge');
                setRefresh((r) => r + 1);
              }}
              onError={failed('judge')}
              onReload={() => setRefresh((r) => r + 1)}
              focusOn={focusOn}
              relayOn={me === null ? null : me.relay_enabled === true}
            />
          )}
          {screen === 'sessions' && (
            <Sessions
              onError={failed('sessions')}
              onRead={() => cleared('sessions')}
              chipHost={chipHost}
              query={isCommand(findQ) ? '' : findQ}
              onClearQuery={() => setFindQ('')}
              inbox={items}
              now={now}
            />
          )}
          {screen === 'work' && (
            <Work
              onError={failed('work')}
              onRead={() => cleared('work')}
              chipHost={chipHost}
              query={isCommand(findQ) ? '' : findQ}
              onClearQuery={() => setFindQ('')}
            />
          )}
          {screen === 'decisions' && (
            <Decisions
              onError={failed('decisions')}
              onRead={() => cleared('decisions')}
              chipHost={chipHost}
              query={isCommand(findQ) ? '' : findQ}
              onClearQuery={() => setFindQ('')}
            />
          )}
          {screen === 'documents' && (
            <Documents
              onError={failed('documents')}
              onRead={() => cleared('documents')}
              chipHost={chipHost}
              query={isCommand(findQ) ? '' : findQ}
              onClearQuery={() => setFindQ('')}
            />
          )}
          {screen === 'risk' && <RiskRules onError={failed('risk')} onRead={() => cleared('risk')} />}
          {screen === 'me' && <Me onError={failed('me')} onRead={() => cleared('me')} auth={auth} onAuthChanged={onAuthChanged} />}
          {screen === 'org' && <Org onError={failed('org')} onRead={() => cleared('org')} />}
          {screen === 'accounts' && <Accounts onError={failed('accounts')} onRead={() => cleared('accounts')} />}
          {screen === 'audit' && <Audit onError={failed('audit')} onRead={() => cleared('audit')} chipHost={chipHost} />}
          {screen === 'runs' && <RunTest />}
          {screen === 'kit' && <Kit />}
        </div>
      </div>
      <TabBar
        current={screen}
        counts={counts}
        permission={permission}
        onAskPermission={askPermission}
        focus={me?.focus_on ?? null}
        onToggleFocus={() => void toggleFocus()}
      />
    </div>
  );
}

/** What failed, one line per source. Closing hides it until the next failure of any source. */
export function ProblemList({ problems, onDismiss }: { problems: Problems; onDismiss: () => void }) {
  const shown = lines(problems);
  if (shown.length === 0) return null;
  return (
    <div role="alert" className="problems">
      <ul>
        {shown.map((line) => (
          <li key={line}>{line}</li>
        ))}
      </ul>
      <button onClick={onDismiss}>닫기</button>
    </div>
  );
}

/**
 * One browser notification per new immediate item (DEC-0092 5: conflicts and what blocks work,
 * such as a relayed permission request). Clicking it brings the tab forward with the item open.
 */
function notify(item: InboxItem) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
  const label = BUCKETS.find((b) => b.bucket === item.bucket)?.label ?? '나를 기다리는 것';
  const n = new Notification(`zm-baton · ${label}`, { body: item.summary, tag: item.id });
  n.onclick = () => {
    window.focus();
    window.location.hash = inboxHref(item.id);
    n.close();
  };
}
