import { useEffect, useRef, useState } from 'react';
import { App as RunTest } from './App';
import { Decisions } from './decisions/Decisions';
import { Documents } from './documents/Documents';
import { BUCKETS, newImmediate } from './inboxModel';
import { Inbox } from './inbox/Inbox';
import { Kit } from './kit/Kit';
import { RiskRules } from './review/RiskRules';
import { clear, fail, lines, type Problems, type Source } from './problems';
import { Sessions } from './sessions/Sessions';
import { sessionsApi, type InboxItem, type SessionView } from './sessionsApi';
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
export function Shell() {
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
    };
    window.addEventListener('hashchange', onHash);
    const clock = setInterval(() => setNow(Date.now()), 15_000);
    return () => {
      window.removeEventListener('hashchange', onHash);
      clearInterval(clock);
    };
  }, []);

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

  usePoll(
    async (alive, signal) => {
      const list = await sessionsApi.inbox(signal);
      if (!alive()) return;
      for (const item of newImmediate(seen.current, list)) notify(item);
      seen.current = new Set(list.map((i) => i.id));
      setItems(list);
      setInboxError(null);
      cleared('inbox');
    },
    3000,
    [refresh],
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

  async function askPermission() {
    if (typeof Notification === 'undefined') return;
    setPermission(await Notification.requestPermission());
  }

  const counts = { inbox: items?.length ?? 0, sessions: (sessions ?? []).filter((s) => s.state === 'live').length };
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
        <Sidebar current={screen} counts={counts} open={sideOpen} onToggle={toggleSide} permission={permission} onAskPermission={askPermission} />
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
          {screen === 'runs' && <RunTest />}
          {screen === 'kit' && <Kit />}
        </div>
      </div>
      <TabBar current={screen} counts={counts} permission={permission} onAskPermission={askPermission} />
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

/** One browser notification per new immediate item (DEC-0092 5: conflicts and what blocks work). */
function notify(item: InboxItem) {
  if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;
  const label = BUCKETS.find((b) => b.bucket === item.bucket)?.label ?? '나를 기다리는 것';
  new Notification(`zm-baton · ${label}`, { body: item.summary, tag: item.id });
}
