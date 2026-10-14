import { useRef, useState } from 'react';
import { shortId } from '../inboxModel';
import { sessionsApi, type InboxItem, type SessionView } from '../sessionsApi';
import { useNarrow } from '../shell/useNarrow';
import { DetailPane } from '../ui/DetailPane';
import { EmptyState } from '../ui/EmptyState';
import { focusRow } from '../ui/rowKeys';
import { useAddress } from '../ui/useAddress';
import { useSheet } from '../ui/useSheet';
import { useShownRow } from '../ui/useShownRow';
import { usePoll } from '../usePoll';
import { SessionChips } from './SessionChips';
import { SessionDetail } from './SessionDetail';
import { SessionList } from './SessionList';
import { filterSessions, groupSessions, hasChosenFilters, listState, readView, repoOptions, rowOrder, writeView, type SessionFilters } from './sessionView';

type Props = {
  /** A failed read, for the problem list at the top of the page. */
  onError: (e: unknown) => void;
  onRead: () => void;
  /** The row under the command line where this screen draws its filter chips. */
  chipHost: HTMLElement | null;
  /** Words typed in the command line: session id, work and branch. */
  query: string;
  onClearQuery: () => void;
  /** What waits for me (the shell's poll), to link a conflicting file to its item. */
  inbox: InboxItem[] | null;
  now: number;
};

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * Registered AI sessions and what each works on (contract 「직접 연 AI 세션을 서버에 등록한다」 11),
 * in the work screen's grammar: the list and the detail side by side (DESIGN.md 「화면 배치」 3),
 * live sessions first. Filters and the selected session live in the address.
 */
export function Sessions({ onError, onRead, chipHost, query, onClearQuery, inbox, now }: Props) {
  const narrow = useNarrow();
  const [view, setView] = useAddress('sessions', readView, writeView);
  /** The last list the server returned, with whether it holds ended sessions. */
  const [loaded, setLoaded] = useState<{ ended: boolean; list: SessionView[] } | null>(null);
  const [endedExist, setEndedExist] = useState<boolean | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const sheet = useSheet(view.selected);
  const [refresh, setRefresh] = useState(0);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const root = useRef<HTMLElement>(null);
  const q = query.trim();
  const { selected } = view;

  usePoll(
    async (alive, signal) => {
      const list = await sessionsApi.sessions(view.ended, signal);
      // No live session and nothing filtered: ask once whether any other exists, to tell 「none」 from 「all ended」.
      const others = !view.ended && list.length === 0 ? (await sessionsApi.sessions(true, signal, 1)).length > 0 : null;
      if (!alive()) return;
      setLoaded({ ended: view.ended, list });
      setEndedExist(others);
      setListError(null);
      onRead();
    },
    3000,
    [view.ended, refresh],
    (e) => {
      setListError(message(e));
      onError(e);
    },
  );

  const items = loaded?.list ?? null;
  const repos = repoOptions(items ?? [], view.repo);
  const visible = items ? filterSessions(items, view, q) : [];
  const groups = groupSessions(visible, view.ended);
  const order = rowOrder(groups);
  const state = listState({ items, visible: visible.length, error: listError, filters: view, query: q, endedExist, repos });
  const narrowed = hasChosenFilters(view) || q !== '';
  const current = selected === null ? null : (items?.find((s) => s.id === selected) ?? null);
  const choose = (id: string | null) => setView((v) => (v.selected === id ? v : { ...v, selected: id }));

  // Wide, the detail is always beside the list: the first row, or the first one a filter change leaves.
  useShownRow({
    fill: !narrow,
    follow: !narrow,
    selected,
    order,
    filterKey: `${view.repo}|${view.ended}|${q}`,
    settled: loaded?.ended === view.ended,
    choose,
  });

  /** Enter on a row, or a tap on a narrow screen: the detail takes the focus, or opens as a sheet. */
  function open(id: string) {
    choose(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  const setFilters = (f: Partial<SessionFilters>) => setView((v) => ({ ...v, ...f }));

  function clearFilters() {
    setFilters({ repo: '' });
    onClearQuery();
  }

  const detail = current ? (
    <SessionDetail session={current} inbox={inbox} now={now} titleRef={titleRef} inSheet={narrow} />
  ) : selected !== null && items !== null ? (
    <EmptyState
      kind="empty"
      title="이 세션은 지금 목록에 없다"
      action={
        !view.ended ? (
          <button type="button" className="btn" onClick={() => setFilters({ ended: true })}>
            끝난 세션 보기
          </button>
        ) : undefined
      }
    >
      {view.ended ? '서버가 이 세션 번호를 돌려주지 않았다. 지워졌거나 번호가 틀렸다.' : '끝났거나, 등록을 껐거나, 만료된 세션일 수 있다.'} 세션 번호{' '}
      <code>{shortId(selected)}</code>
    </EmptyState>
  ) : state.kind === 'rows' ? (
    <EmptyState kind="empty" title="고른 세션이 없다">
      목록에서 세션을 고르면 여기에 보인다.
    </EmptyState>
  ) : null; // an empty list already says why in its own pane

  return (
    <main className="panes ss" ref={root}>
      <h1 className="sr">세션 현황</h1>
      <SessionChips host={chipHost} filters={view} onFilters={setFilters} repos={repos} />
      <SessionList
        state={state}
        groups={groups}
        head={{ base: view.ended ? '모든 세션' : '살아 있는 세션', count: items === null ? null : visible.length, narrowed }}
        selected={selected}
        now={now}
        onSelect={choose}
        onPick={narrow ? open : choose}
        onOpen={open}
        onShowEnded={() => setFilters({ ended: true })}
        onClearFilters={clearFilters}
        onReload={() => setRefresh((r) => r + 1)}
      />
      <DetailPane
        narrow={narrow}
        open={sheet.open}
        label="세션 상세"
        sheetTitle={current ? shortId(current.id) : '세션 상세'}
        onClose={sheet.hide}
        onBack={() => focusRow(root.current, selected)}
      >
        {detail}
      </DetailPane>
    </main>
  );
}
