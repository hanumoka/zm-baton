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
import { ApiError, workApi, type NewWorkItem, type Project, type Stage, type WorkItem, type WorkItemDetail } from '../workApi';
import { NewWork } from './NewWork';
import { WorkChips } from './WorkChips';
import { WorkDetail, type DetailActions } from './WorkDetail';
import { WorkList } from './WorkList';
import {
  filterItems,
  groupByHolder,
  hasChosenFilters,
  isBlocked,
  listState,
  readView,
  rowOrder,
  stageOptions,
  writeView,
  type WorkFilters,
} from './workView';

type Props = {
  /** A failed read, for the problem list at the top of the page. */
  onError: (e: unknown) => void;
  onRead: () => void;
  /** The row under the command line where this screen draws its filter chips. */
  chipHost: HTMLElement | null;
  /** Words typed in the command line: title, number and next action. */
  query: string;
  onClearQuery: () => void;
};

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * Work items (FN-0201): the list and the detail side by side (DESIGN.md 「화면 배치」 3), the list
 * grouped by who holds the work now. Filters and the selected item live in the address. Items
 * imported from zm-docs are copies whose source of truth stays the Git file; they show here and
 * change only by the next import. Work made here is edited here.
 */
export function Work({ onError, onRead, chipHost, query, onClearQuery }: Props) {
  const narrow = useNarrow();
  const [view, setView] = useAddress('work', readView, writeView);
  /** The last list the server returned, with the server-side filters it answered. */
  const [loaded, setLoaded] = useState<{ key: string; list: WorkItem[] } | null>(null);
  const [closedExist, setClosedExist] = useState<boolean | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  /** The stages the organisation skips; null until the setting has been read. */
  const [skipped, setSkipped] = useState<Stage[] | null>(null);
  const [detail, setDetail] = useState<WorkItemDetail | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [pane, setPane] = useState<'detail' | 'new'>('detail');
  const sheet = useSheet();
  const [refresh, setRefresh] = useState(0);
  const q = useSettled(query.trim(), 200);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const root = useRef<HTMLElement>(null);
  const { selected } = view;
  const serverKey = `${view.projectId}|${view.showClosed}|${q}`;

  usePoll(
    async (alive, signal) => {
      const [list, projectList, workflow] = await Promise.all([
        workApi.workItems({ projectId: view.projectId || undefined, open: !view.showClosed, q: q || undefined }, signal),
        workApi.projects(signal),
        workApi.workflow(signal),
      ]);
      // Nothing open and nothing filtered: ask once whether closed work exists, to tell 「없다」 from 「끝났다」.
      const closed =
        list.length === 0 && !view.showClosed && !view.projectId && !q ? (await workApi.workItems({ open: false, limit: 1 }, signal)).length > 0 : null;
      if (!alive()) return;
      setLoaded({ key: serverKey, list });
      setClosedExist(closed);
      setProjects(projectList);
      setSkipped(workflow.skipped_stages);
      setListError(null);
      onRead();
    },
    5000,
    [view.projectId, view.showClosed, q, refresh],
    (e) => {
      setListError(message(e));
      onError(e);
    },
  );

  const showDetail = pane === 'detail' && (!narrow || sheet.open);
  usePoll(
    async (alive, signal) => {
      if (selected === null || !showDetail) return;
      const d = await workApi.detail(selected, signal);
      if (alive()) setDetail(d);
    },
    5000,
    [selected, showDetail, refresh],
    (e) => {
      // An address that names an item the server no longer has: drop the selection, not an alarm every 5 seconds.
      if (e instanceof ApiError && e.status === 404) setView((v) => (v.selected === selected ? { ...v, selected: null } : v));
      else onError(e);
    },
  );

  const items = loaded?.list ?? null;
  const visible = items ? filterItems(items, view) : [];
  const groups = groupByHolder(visible, view.showClosed);
  const order = rowOrder(groups);
  const state = listState({ items, visible: visible.length, error: listError, filters: view, query: q, closedExist, projects });
  const narrowed = hasChosenFilters(view) || q !== '';
  const current = selected === null ? null : (items?.find((i) => i.id === selected) ?? (detail?.item.id === selected ? detail.item : null));

  // Wide, the detail is always beside the list: the first row, or the first one a filter change leaves.
  useShownRow({
    fill: !narrow && pane === 'detail',
    follow: !narrow,
    selected,
    order,
    filterKey: `${serverKey}|${view.stage}|${view.blocked}`,
    settled: loaded?.key === serverKey,
    choose: (id) => setView((v) => ({ ...v, selected: id })),
  });

  /** Runs one action on the shown item with the version shown; a refusal is shown with its reason. */
  const act = (run: (d: WorkItemDetail) => Promise<WorkItemDetail>) => async () => {
    if (detail === null || detail.item.id !== selected) return;
    try {
      setDetail(await run(detail));
      setActionError(null);
      setRefresh((r) => r + 1);
    } catch (e) {
      setActionError(message(e));
      setRefresh((r) => r + 1); // reload: a 409 usually means someone else changed it
      throw e;
    }
  };
  const swallow = (p: Promise<void>) => p.catch(() => undefined);
  const actions: DetailActions = {
    move: (to, note) => swallow(act((d) => workApi.move(d.item.id, d.item.version, to, note))()),
    update: (fields) => swallow(act((d) => workApi.update(d.item.id, d.item.version, fields))()),
    addWait: (reason, note) => swallow(act((d) => workApi.addWait(d.item.id, d.item.version, reason, note))()),
    removeWait: (reason) => swallow(act((d) => workApi.removeWait(d.item.id, d.item.version, reason))()),
    delegate: (choice) => swallow(act((d) => workApi.delegate(d.item.id, d.item.version, choice))()),
    withdraw: (runId) => swallow(act((d) => workApi.withdraw(runId, d.item.version))()),
  };

  function select(id: string) {
    setView((v) => (v.selected === id ? v : { ...v, selected: id }));
    setActionError(null);
    setPane('detail');
  }

  function closeSheet() {
    sheet.hide();
    setPane('detail');
  }

  /** Enter on a row, or a tap on a narrow screen: the detail takes the focus, or opens as a sheet. */
  function open(id: string) {
    select(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  function startNew() {
    setPane('new');
    if (narrow) sheet.show();
  }

  function cancelNew() {
    if (narrow) closeSheet();
    else setPane('detail');
  }

  async function create(body: NewWorkItem) {
    try {
      const created = await workApi.create(body);
      setRefresh((r) => r + 1);
      select(created.id);
    } catch (e) {
      onError(e);
    }
  }

  const setFilters = (f: Partial<WorkFilters>) =>
    setView((v) => {
      const next = { ...v, ...f };
      if (!next.showClosed && (next.stage === 'done' || next.stage === 'cancelled')) next.stage = '';
      return next;
    });

  function clearFilters() {
    setView((v) => ({ ...v, projectId: '', stage: '', blocked: false }));
    onClearQuery();
  }

  /** Esc in the wide detail goes back to the selected row; inside a field it stays with the field. */
  function onDetailKey(e: KeyboardEvent<HTMLElement>) {
    if (e.key !== 'Escape' || e.defaultPrevented || (e.target as HTMLElement).closest('input, textarea, select')) return;
    e.preventDefault();
    if (pane === 'new') setPane('detail');
    focusRow(root.current, selected);
  }

  const stageFiltered = items ? filterItems(items, { stage: view.stage, blocked: false }) : [];
  const detailPane =
    pane === 'new' ? (
      <NewWork projects={projects} defaultProject={view.projectId} onCreate={create} onCancel={cancelNew} inSheet={narrow} />
    ) : current ? (
      <WorkDetail
        item={current}
        detail={detail}
        projects={projects}
        skipped={skipped ?? []}
        actions={actions}
        error={actionError}
        titleRef={titleRef}
        inSheet={narrow}
      />
    ) : selected !== null ? (
      <RowSkeleton rows={3} />
    ) : state.kind === 'rows' ? (
      <EmptyState kind="empty" title="고른 작업이 없다">
        목록에서 작업을 고르면 여기에 보인다.
      </EmptyState>
    ) : null; // an empty list already says why in its own pane

  return (
    <main className="panes wk" ref={root}>
      <h1 className="sr">작업</h1>
      <WorkChips
        host={chipHost}
        filters={view}
        onFilters={setFilters}
        projects={projects}
        stages={stageOptions(items ?? [], skipped ?? [], view.showClosed)}
        blockedCount={stageFiltered.filter(isBlocked).length}
        skipped={skipped}
        onSkipped={async (next) => {
          try {
            setSkipped((await workApi.setWorkflow(next)).skipped_stages);
            setRefresh((r) => r + 1);
          } catch (e) {
            onError(e);
          }
        }}
      />
      <WorkList
        state={state}
        groups={groups}
        head={{ base: view.showClosed ? '모든 작업' : '열린 작업', count: items === null ? null : visible.length, narrowed }}
        selected={selected}
        onSelect={select}
        onPick={narrow ? open : select}
        onOpen={open}
        onNew={startNew}
        onClearFilters={clearFilters}
        onShowClosed={() => setFilters({ showClosed: true })}
        onReload={() => setRefresh((r) => r + 1)}
      />
      {!narrow && (
        <section className="pane-detail" aria-label={pane === 'new' ? '새 작업' : '작업 상세'} onKeyDown={onDetailKey}>
          {detailPane}
        </section>
      )}
      {narrow && sheet.open && (
        <Sheet title={pane === 'new' ? '새 작업' : (current?.external_ref ?? '작업 상세')} onClose={closeSheet}>
          {detailPane}
        </Sheet>
      )}
    </main>
  );
}
