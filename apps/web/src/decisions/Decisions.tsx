import { useRef, useState, type KeyboardEvent } from 'react';
import { Sheet } from '../shell/Sheet';
import { useNarrow } from '../shell/useNarrow';
import { EmptyState } from '../ui/EmptyState';
import { RowSkeleton } from '../ui/Group';
import { focusRow } from '../ui/rowKeys';
import { useAddress } from '../ui/useAddress';
import { useSettled } from '../ui/useSettled';
import { useSheet } from '../ui/useSheet';
import { useShownRow } from '../ui/useShownRow';
import { usePoll } from '../usePoll';
import { ApiError, workApi, type Decision, type DecisionDetail, type Project, type WorkItem } from '../workApi';
import { DecisionChips } from './DecisionChips';
import { DecisionDetailView } from './DecisionDetailView';
import { DecisionList } from './DecisionList';
import {
  bucketOf,
  filterDecisions,
  groupByStatus,
  hasChosenFilters,
  jumpTo,
  listState,
  mentioning,
  pastShown,
  readView,
  rowOrder,
  statusOptions,
  writeView,
  type DecisionFilters,
} from './decisionView';

type Props = {
  /** A failed read, for the problem list at the top of the page. */
  onError: (e: unknown) => void;
  onRead: () => void;
  /** The row under the command line where this screen draws its filter chips. */
  chipHost: HTMLElement | null;
  /** Words typed in the command line: number, question and decision. */
  query: string;
  onClearQuery: () => void;
};

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * Decision records (FN-0604), in the work screen's grammar: the list and the detail side by side
 * (DESIGN.md 「화면 배치」 3), the list grouped by status. Filters and the selected decision live
 * in the address. In stage 1 the records come from zm-docs and change in Git there (FN-0605), so
 * this screen only reads them.
 */
export function Decisions({ onError, onRead, chipHost, query, onClearQuery }: Props) {
  const narrow = useNarrow();
  const [view, setView, pushView] = useAddress('decisions', readView, writeView);
  /** The last list the server returned, with the server-side filters it answered. */
  const [loaded, setLoaded] = useState<{ key: string; list: Decision[] } | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [detail, setDetail] = useState<{ decision: DecisionDetail; related: WorkItem[] } | null>(null);
  const sheet = useSheet();
  const [refresh, setRefresh] = useState(0);
  const q = useSettled(query.trim(), 200);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const root = useRef<HTMLElement>(null);
  const { selected } = view;
  const serverKey = `${view.projectId}|${q}`;

  usePoll(
    async (alive, signal) => {
      const [list, projectList] = await Promise.all([
        workApi.decisions({ projectId: view.projectId || undefined, q: q || undefined }, signal),
        workApi.projects(signal),
      ]);
      if (!alive()) return;
      setLoaded({ key: serverKey, list });
      setProjects(projectList);
      setListError(null);
      onRead();
    },
    15000,
    [view.projectId, q, refresh],
    (e) => {
      setListError(message(e));
      onError(e);
    },
  );

  const showDetail = !narrow || sheet.open;
  usePoll(
    async (alive, signal) => {
      if (selected === null || !showDetail) return;
      const d = await workApi.decision(selected, signal);
      const ref = d.decision.external_ref;
      // The server keeps no link from a decision to work; work that writes its number is the closest real one.
      const related = ref ? mentioning(await workApi.workItems({ open: false, q: ref, limit: 50 }, signal).catch(() => []), ref) : [];
      if (alive()) setDetail({ decision: d, related });
    },
    15000,
    [selected, showDetail, refresh],
    (e) => {
      // An address that names a decision the server no longer has: drop the selection, not an alarm every 15 seconds.
      if (e instanceof ApiError && e.status === 404) setView((v) => (v.selected === selected ? { ...v, selected: null } : v));
      else onError(e);
    },
  );

  const items = loaded?.list ?? null;
  const visible = items ? filterDecisions(items, view.status) : [];
  const groups = groupByStatus(visible, pastShown(view));
  const order = rowOrder(groups);
  const state = listState({ items, visible: visible.length, error: listError, filters: view, query: q, projects });
  const narrowed = hasChosenFilters(view) || q !== '';
  const current = selected === null ? null : (items?.find((d) => d.id === selected) ?? (detail?.decision.decision.id === selected ? detail.decision.decision : null));

  useShownRow({
    fill: !narrow,
    follow: !narrow,
    selected,
    order,
    filterKey: `${serverKey}|${view.status}|${pastShown(view)}`,
    settled: loaded?.key === serverKey,
    choose: (id) => setView((v) => ({ ...v, selected: id })),
  });

  function select(id: string) {
    setView((v) => (v.selected === id ? v : { ...v, selected: id }));
  }

  /** Enter on a row, or a tap on a narrow screen: the detail takes the focus, or opens as a sheet. */
  function open(id: string) {
    select(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  /** A supersession link: a step in the browser's history (Back returns), the focus on the other question. */
  function jump(id: string) {
    pushView(jumpTo(view, id, items));
    requestAnimationFrame(() => titleRef.current?.focus());
  }

  const setFilters = (f: Partial<DecisionFilters>) => setView((v) => ({ ...v, ...f }));

  function clearFilters() {
    setView((v) => ({ ...v, projectId: '', status: '' }));
    onClearQuery();
  }

  /** Esc in the wide detail goes back to the selected row. */
  function onDetailKey(e: KeyboardEvent<HTMLElement>) {
    if (e.key !== 'Escape' || e.defaultPrevented) return;
    e.preventDefault();
    focusRow(root.current, selected);
  }

  const detailPane = current ? (
    <DecisionDetailView
      decision={current}
      detail={detail?.decision ?? null}
      related={detail?.decision.decision.id === current.id ? detail.related : null}
      onJump={jump}
      titleRef={titleRef}
      inSheet={narrow}
    />
  ) : selected !== null ? (
    <RowSkeleton rows={3} />
  ) : state.kind === 'rows' ? (
    <EmptyState kind="empty" title="고른 결정이 없다">
      목록에서 결정을 고르면 여기에 보인다.
    </EmptyState>
  ) : null; // an empty list already says why in its own pane

  return (
    <main className="panes dc" ref={root}>
      <h1 className="sr">결정</h1>
      <DecisionChips
        host={chipHost}
        filters={view}
        onFilters={setFilters}
        projects={projects}
        statuses={statusOptions(items ?? [])}
        pastCount={(items ?? []).filter((d) => bucketOf(d.status) === 'past').length}
      />
      <DecisionList
        state={state}
        groups={groups}
        head={{ base: '결정', count: items === null ? null : visible.length, narrowed }}
        selected={selected}
        onSelect={select}
        onPick={narrow ? open : select}
        onOpen={open}
        onShowPast={() => setFilters({ showPast: true })}
        onClearFilters={clearFilters}
        onReload={() => setRefresh((r) => r + 1)}
      />
      {!narrow && (
        <section className="pane-detail" aria-label="결정 상세" onKeyDown={onDetailKey}>
          {detailPane}
        </section>
      )}
      {narrow && sheet.open && (
        <Sheet title={current?.external_ref ?? '결정 상세'} onClose={sheet.hide}>
          {detailPane}
        </Sheet>
      )}
    </main>
  );
}
