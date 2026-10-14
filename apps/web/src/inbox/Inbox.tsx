import { useEffect, useRef, useState } from 'react';
import { BUCKETS, visibleItems, waitingItems } from '../inboxModel';
import type { InboxItem, SessionView } from '../sessionsApi';
import { useNarrow } from '../shell/useNarrow';
import { DetailPane } from '../ui/DetailPane';
import { EmptyState } from '../ui/EmptyState';
import { focusRow } from '../ui/rowKeys';
import { useAddress } from '../ui/useAddress';
import { useNextWhenGone } from '../ui/useNextWhenGone';
import { useSheet } from '../ui/useSheet';
import { useShownRow } from '../ui/useShownRow';
import { InboxDetail } from './InboxDetail';
import { InboxList } from './InboxList';
import { inboxGroups, kindOf, listState, readView, rowOrder, writeView } from './inboxView';
import type { Send } from './inboxView';
import type { RelaySend } from './RelayDetail';

type Props = {
  /** What waits for me, polled by the shell (browser notifications come from that poll); null before the first read. */
  items: InboxItem[] | null;
  /** The last failed read, while nothing has been read yet. */
  error: string | null;
  /** Live sessions, polled by the shell for the sidebar count; null before the first read. */
  sessions: SessionView[] | null;
  now: number;
  /** A judgement went through: read the list again at once. */
  onChanged: () => void;
  /** A judgement the server refused, for the problem list at the top of the page. */
  onError: (e: unknown) => void;
  onReload: () => void;
  /** For tests: what sends a judgement. */
  send?: Send;
  /** Focus mode is on (contract 「알림과 집중 모드」): the relaying buckets say so. */
  focusOn?: boolean;
  /** Whether the server relays (ZM_BATON_RELAY); null until it said. */
  relayOn?: boolean | null;
  /** For tests: what sends a relay's answer. */
  relaySend?: RelaySend;
};

/**
 * The first screen, 「나를 기다리는 것」 (FN-0907), in the work screen's grammar: the list and the
 * detail side by side (DESIGN.md 「화면 배치」 3), the list in the contract's five buckets. The
 * selected item lives in the address. When an item leaves the list, because it was judged here or
 * closed elsewhere, the one that took its place opens.
 */
export function Inbox({ items, error, sessions, now, onChanged, onError, onReload, send, focusOn = false, relayOn = null, relaySend }: Props) {
  const narrow = useNarrow();
  const [view, setView] = useAddress('inbox', readView, writeView);
  /** Items judged here, hidden until the server's list no longer has them. */
  const [judged, setJudged] = useState<ReadonlySet<string>>(() => new Set());
  const sheet = useSheet(view.selected);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const root = useRef<HTMLElement>(null);
  /** Where the focus goes once the next item is selected after a judgement. */
  const afterJudge = useRef<'title' | 'row' | null>(null);
  /** What the last review verdict or answer did, until the next one. */
  const [done, setDone] = useState<{ text: string; href: string | null } | null>(null);
  const { selected } = view;

  const visible = items === null ? null : visibleItems(items).filter((i) => !judged.has(i.id));
  const groups = inboxGroups(visible ?? []);
  const order = rowOrder(groups);
  const state = listState(visible, error);
  const current = selected === null ? null : (visible?.find((i) => i.id === selected) ?? null);
  const choose = (id: string | null) => setView((v) => (v.selected === id ? v : { ...v, selected: id }));

  // Forget what the server has stopped sending.
  useEffect(() => {
    if (items === null) return;
    setJudged((j) => {
      const still = [...j].filter((id) => items.some((i) => i.id === id));
      return still.length === j.size ? j : new Set(still);
    });
  }, [items]);

  // Wide, the detail is always beside the list: the first row while nothing is selected.
  useShownRow({ fill: !narrow, follow: false, selected, order, filterKey: '', settled: visible !== null, choose });
  // An item that leaves: the next one opens. An open sheet stays and says the item closed.
  useNextWhenGone({ order, selected, settled: visible !== null, enabled: !(narrow && sheet.open), choose });

  useEffect(() => {
    const to = afterJudge.current;
    if (to === null || selected === null) return;
    afterJudge.current = null;
    if (to === 'title') titleRef.current?.focus();
    else focusRow(root.current, selected);
  }, [selected]);

  /** Enter on a row, or a tap on a narrow screen: the detail takes the focus, or opens as a sheet. */
  function open(id: string) {
    choose(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  function onJudged(id: string) {
    afterJudge.current = narrow ? 'row' : 'title';
    setJudged((j) => new Set(j).add(id));
    if (narrow) sheet.hide();
    onChanged();
  }

  function onDone(id: string, text: string, href: string | null) {
    setDone({ text, href });
    onJudged(id);
  }

  const detail = current ? (
    <InboxDetail
      key={current.id}
      item={current}
      sessions={sessions}
      now={now}
      onJudged={onJudged}
      onDone={onDone}
      onError={onError}
      titleRef={titleRef}
      inSheet={narrow}
      send={send}
      onAnswered={onChanged}
      relaySend={relaySend}
    />
  ) : selected !== null && visible !== null ? (
    <EmptyState kind="empty" title="이 항목은 더는 기다리지 않는다">
      판정됐거나, 세션이 다시 움직여 저절로 닫혔다. 목록에서 다음 항목을 고른다.
    </EmptyState>
  ) : state.kind === 'rows' ? (
    <EmptyState kind="empty" title="고른 항목이 없다">
      목록에서 항목을 고르면 여기에 보인다.
    </EmptyState>
  ) : null; // an empty list already says why in its own pane

  const sheetTitle = current ? (BUCKETS.find((b) => b.bucket === current.bucket)?.label ?? kindOf(current).label) : '나를 기다리는 것';

  return (
    <main className="panes ib" ref={root}>
      <h1 className="sr">나를 기다리는 것</h1>
      <InboxList
        state={state}
        groups={groups}
        head={{ base: '기다리는 것', count: visible === null ? null : waitingItems(visible).length, narrowed: false }}
        selected={selected}
        sessions={sessions}
        now={now}
        focusOn={focusOn}
        relayOn={relayOn}
        onSelect={choose}
        onPick={narrow ? open : choose}
        onOpen={open}
        onReload={onReload}
        done={done}
      />
      <DetailPane narrow={narrow} open={sheet.open} label="기다리는 것 상세" sheetTitle={sheetTitle} onClose={sheet.hide} onBack={() => focusRow(root.current, selected)}>
        {detail}
      </DetailPane>
    </main>
  );
}
