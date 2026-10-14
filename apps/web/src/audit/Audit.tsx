import { useMemo, useRef, useState } from 'react';
import { auditApi, type AuditEntry, type AuditFacets, type RetentionView } from '../auditApi';
import { useNarrow } from '../shell/useNarrow';
import { DetailPane } from '../ui/DetailPane';
import { EmptyState } from '../ui/EmptyState';
import { RowSkeleton } from '../ui/Group';
import { focusRow } from '../ui/rowKeys';
import { useAddress } from '../ui/useAddress';
import { useSheet } from '../ui/useSheet';
import { useShownRow } from '../ui/useShownRow';
import { usePoll } from '../usePoll';
import { ApiError } from '../workApi';
import { AuditDetail, RetentionPane } from './AuditDetail';
import { AuditChips, AuditList } from './AuditList';
import { groupByDay, listState, MAX_PAGES, NO_FILTERS, PAGE, periodAfter, readView, rowOrder, writeView, type AuditFilters } from './auditView';

type Props = {
  onError: (e: unknown) => void;
  onRead: () => void;
  chipHost: HTMLElement | null;
};

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * The audit log (FN-1404), in the other screens' grammar: the list and the detail side by side, the
 * list grouped by day, newest first. Chips narrow it by who acted, on what kind of record and since
 * when; the filters, the selected row and the open pane live in the address. The head's button opens
 * the retention settings (read only) and the export download in the detail pane.
 */
export function Audit({ onError, onRead, chipHost }: Props) {
  const narrow = useNarrow();
  const [view, setView] = useAddress('audit', readView, writeView);
  const [loaded, setLoaded] = useState<{ key: string; list: AuditEntry[] } | null>(null);
  const [facets, setFacets] = useState<AuditFacets | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [pages, setPages] = useState(1);
  const [single, setSingle] = useState<AuditEntry | null>(null);
  const [retention, setRetention] = useState<RetentionView | null>(null);
  const [retentionError, setRetentionError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const sheet = useSheet(view.selected);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const root = useRef<HTMLElement>(null);
  const { selected } = view;
  const filters: AuditFilters = { actor: view.actor, target: view.target, period: view.period, targetId: view.targetId };
  const filterKey = `${filters.actor}|${filters.target}|${filters.period}|${filters.targetId}`;
  // The period starts at a fixed time for one choice, so polling does not move it.
  const afterValue = useMemo(() => periodAfter(view.period, Date.now()), [view.period]);

  usePoll(
    async (alive, signal) => {
      const params = { actorClass: filters.actor, targetKind: filters.target, targetId: filters.targetId, after: afterValue };
      const [list, counts] = await Promise.all([auditApi.list({ ...params, limit: PAGE * pages }, signal), auditApi.facets(params, signal)]);
      if (!alive()) return;
      setLoaded({ key: `${filterKey}|${pages}`, list });
      setFacets(counts);
      setListError(null);
      onRead();
    },
    15000,
    [filterKey, afterValue, pages, refresh],
    (e) => {
      setListError(message(e));
      onError(e);
    },
  );

  const items = loaded !== null && loaded.key.startsWith(`${filterKey}|`) ? loaded.list : null;
  const groups = groupByDay(items ?? [], Date.now());
  const order = rowOrder(groups);
  const state = listState(items, listError, filters);
  const inList = selected === null ? null : (items?.find((e) => e.id === selected) ?? null);
  const current = inList ?? (single?.id === selected ? single : null);
  const retentionOpen = view.pane === 'retention';
  const showDetail = !retentionOpen && (!narrow || sheet.open);

  // A row opened from a link but not on the page read: read it by its id.
  usePoll(
    async (alive, signal) => {
      if (selected === null || inList !== null || items === null || !showDetail) return;
      const one = await auditApi.one(selected, signal);
      if (alive()) setSingle(one);
    },
    60000,
    [selected, inList === null, items === null, showDetail],
    (e) => {
      if (e instanceof ApiError && e.status === 404) setView((v) => (v.selected === selected ? { ...v, selected: null } : v));
      else onError(e);
    },
  );

  usePoll(
    async (alive, signal) => {
      if (!retentionOpen) return;
      const r = await auditApi.retention(signal);
      if (!alive()) return;
      setRetention(r);
      setRetentionError(null);
    },
    60000,
    [retentionOpen],
    (e) => setRetentionError(message(e)),
  );

  useShownRow({
    fill: !narrow && !retentionOpen,
    follow: !narrow,
    selected,
    order,
    filterKey,
    settled: items !== null,
    choose: (id) => setView((v) => ({ ...v, selected: id })),
  });

  function setFilters(f: Partial<AuditFilters>) {
    setPages(1);
    setView((v) => ({ ...v, ...f }));
  }

  function select(id: string) {
    setView((v) => (v.selected === id && v.pane === '' ? v : { ...v, selected: id, pane: '' }));
  }

  function open(id: string) {
    select(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  function toggleRetention() {
    if (retentionOpen) {
      setView((v) => ({ ...v, pane: '' }));
      if (narrow) sheet.hide();
      return;
    }
    setView((v) => ({ ...v, pane: 'retention' }));
    if (narrow && !sheet.open) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  const full = items !== null && items.length >= PAGE * pages;
  const more = !full ? null : pages < MAX_PAGES ? 'more' : 'cap';
  const body = retentionOpen ? (
    <RetentionPane view={retention} error={retentionError} titleRef={titleRef} inSheet={narrow} />
  ) : current ? (
    <AuditDetail
      entry={current}
      onTarget={(targetId) => {
        setFilters({ targetId, target: current.target_kind ?? '' });
        if (narrow) sheet.hide();
      }}
      titleRef={titleRef}
      inSheet={narrow}
    />
  ) : selected !== null ? (
    <RowSkeleton rows={3} />
  ) : state.kind === 'rows' ? (
    <EmptyState kind="empty" title="고른 기록이 없다">
      목록에서 기록을 고르면 여기에 보인다.
    </EmptyState>
  ) : null;

  return (
    <main className="panes au" ref={root}>
      <h1 className="sr">감사 기록</h1>
      <AuditChips host={chipHost} filters={filters} onFilters={setFilters} facets={facets} />
      <AuditList
        state={state}
        groups={groups}
        head={{ base: '감사 기록', count: items === null ? null : items.length, narrowed: false }}
        selected={retentionOpen ? null : selected}
        onSelect={select}
        onPick={narrow ? open : select}
        onOpen={open}
        onRetention={toggleRetention}
        retentionOpen={retentionOpen}
        onClearFilters={() => setFilters(NO_FILTERS)}
        onReload={() => setRefresh((r) => r + 1)}
        more={more}
        onMore={() => setPages((p) => Math.min(p + 1, MAX_PAGES))}
      />
      <DetailPane
        narrow={narrow}
        open={sheet.open}
        label={retentionOpen ? '보존 규칙과 내보내기' : '감사 기록 상세'}
        sheetTitle={retentionOpen ? '보존 규칙과 내보내기' : '감사 기록'}
        onClose={() => {
          sheet.hide();
          if (retentionOpen) setView((v) => ({ ...v, pane: '' }));
        }}
        onBack={() => focusRow(root.current, selected)}
      >
        {body}
      </DetailPane>
    </main>
  );
}
