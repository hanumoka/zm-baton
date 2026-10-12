import { useEffect, useRef, useState } from 'react';
import { App as RunTest } from './App';
import { BUCKETS, newImmediate } from './inboxModel';
import { Inbox } from './Inbox';
import { clear, fail, lines, type Problems, type Source } from './problems';
import { Sessions } from './Sessions';
import { sessionsApi, type InboxItem, type SessionView } from './sessionsApi';
import { usePoll } from './usePoll';

type Tab = 'inbox' | 'sessions' | 'runs';
const TABS: { id: Tab; label: string }[] = [
  { id: 'inbox', label: '나를 기다리는 것' },
  { id: 'sessions', label: '세션 현황' },
  { id: 'runs', label: '실행 시험' },
];

function tabFromHash(): Tab {
  const h = window.location.hash.replace('#', '');
  return (TABS.find((t) => t.id === h)?.id ?? 'inbox') as Tab;
}

/**
 * The Web screen. The first screen polls even while another tab is open, because browser
 * notifications come from it (contract "알림과 집중 모드" 3: notifications need the page open).
 */
export function Shell() {
  const [tab, setTab] = useState<Tab>(tabFromHash);
  const [items, setItems] = useState<InboxItem[]>([]);
  const [sessions, setSessions] = useState<SessionView[]>([]);
  const [includeEnded, setIncludeEnded] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  const [problems, setProblems] = useState<Problems>({});
  const [permission, setPermission] = useState(() => (typeof Notification === 'undefined' ? 'unsupported' : Notification.permission));
  const seen = useRef<Set<string> | null>(null);
  const [refresh, setRefresh] = useState(0);
  const failed = (source: Source) => (e: unknown) => setProblems((p) => fail(p, source, e));
  const cleared = (source: Source) => setProblems((p) => clear(p, source));

  useEffect(() => {
    const onHash = () => setTab(tabFromHash());
    window.addEventListener('hashchange', onHash);
    const clock = setInterval(() => setNow(Date.now()), 15_000);
    return () => {
      window.removeEventListener('hashchange', onHash);
      clearInterval(clock);
    };
  }, []);

  usePoll(
    async (alive, signal) => {
      const list = await sessionsApi.inbox(signal);
      if (!alive()) return;
      for (const item of newImmediate(seen.current, list)) notify(item);
      seen.current = new Set(list.map((i) => i.id));
      setItems(list);
      cleared('inbox');
    },
    3000,
    [refresh],
    failed('inbox'),
  );

  usePoll(
    async (alive, signal) => {
      if (tab !== 'sessions') {
        cleared('sessions'); // not shown, not read: an old failure says nothing now
        return;
      }
      const list = await sessionsApi.sessions(includeEnded, signal);
      if (!alive()) return;
      setSessions(list);
      cleared('sessions');
    },
    3000,
    [tab, includeEnded, refresh],
    failed('sessions'),
  );

  function go(t: Tab) {
    window.location.hash = t;
    setTab(t);
  }

  async function askPermission() {
    if (typeof Notification === 'undefined') return;
    setPermission(await Notification.requestPermission());
  }

  return (
    <div className="shell">
      <nav className="tabs" aria-label="화면">
        <strong>zm-baton</strong>
        {TABS.map((t) => (
          <button key={t.id} className={t.id === tab ? 'selected' : undefined} aria-current={t.id === tab ? 'page' : undefined} onClick={() => go(t.id)}>
            {t.label}
            {t.id === 'inbox' && items.length > 0 && <span className="count">{items.length}</span>}
          </button>
        ))}
        <span className="spacer" />
        {permission === 'default' && <button onClick={askPermission}>브라우저 알림 켜기</button>}
        {permission === 'denied' && <small>브라우저 알림이 막혀 있다</small>}
        {permission === 'granted' && <small>브라우저 알림 켜짐</small>}
      </nav>
      <ProblemList problems={problems} onDismiss={() => setProblems({})} />
      {tab === 'inbox' && (
        <Inbox
          items={items}
          now={now}
          onChanged={() => {
            cleared('judge');
            setRefresh((r) => r + 1);
          }}
          onError={failed('judge')}
        />
      )}
      {tab === 'sessions' && <Sessions sessions={sessions} includeEnded={includeEnded} onIncludeEnded={setIncludeEnded} now={now} />}
      {tab === 'runs' && <RunTest />}
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
