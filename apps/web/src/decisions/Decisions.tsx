import { useRef, useState, type KeyboardEvent } from 'react';
import { decisionsApi, type DecisionFields } from '../decisionsApi';
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
import { changedFields, draftFields, draftFrom, emptyDraft, supersedeDraft, type DecisionDraft } from './decisionDraft';
import { DecisionChips } from './DecisionChips';
import { DecisionDetailView, type DecisionActions } from './DecisionDetailView';
import { DecisionForm, type FormMode } from './DecisionForm';
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
  refOf,
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

/** The detail pane shows a decision, or a form: a new proposal, a change, or a proposal that replaces one. */
type Pane = { kind: 'detail' } | { kind: 'form'; mode: FormMode; start: DecisionDraft; base: DecisionDetail | null };

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * Decision records (FN-0604), in the work screen's grammar: the list and the detail side by side
 * (DESIGN.md 「화면 배치」 3), the list grouped by status. Filters and the selected decision live
 * in the address. Copies of zm-docs decisions change in Git there (FN-0605), so they are only read;
 * decisions made here are proposed, edited while proposed, accepted or rejected, and replaced here.
 */
export function Decisions({ onError, onRead, chipHost, query, onClearQuery }: Props) {
  const narrow = useNarrow();
  const [view, setView, pushView, navigation] = useAddress('decisions', readView, writeView);
  /** The last list the server returned, with the server-side filters it answered. */
  const [loaded, setLoaded] = useState<{ key: string; list: Decision[] } | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [detail, setDetail] = useState<{ decision: DecisionDetail; related: WorkItem[] } | null>(null);
  const [pane, setPane] = useState<Pane>({ kind: 'detail' });
  /** Work a form can link; read when a form opens. */
  const [work, setWork] = useState<WorkItem[] | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const sheet = useSheet(view.selected, navigation);
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

  const showDetail = pane.kind === 'detail' && (!narrow || sheet.open);
  usePoll(
    async (alive, signal) => {
      if (selected === null || !showDetail) return;
      const d = await workApi.decision(selected, signal);
      const ref = d.decision.external_ref;
      // A Git copy has no linked work; work that writes its number is the closest real link.
      const related =
        d.source_of_truth === 'git' && ref ? mentioning(await workApi.workItems({ open: false, q: ref, limit: 50 }, signal).catch(() => []), ref) : [];
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

  // A form links work: read it once the form is open (open and closed work, newest first).
  usePoll(
    async (alive, signal) => {
      if (pane.kind !== 'form') return;
      const list = await workApi.workItems({ open: false, limit: 500 }, signal);
      if (alive()) setWork(list);
    },
    60000,
    [pane.kind],
    onError,
  );

  const items = loaded?.list ?? null;
  const visible = items ? filterDecisions(items, view.status) : [];
  const groups = groupByStatus(visible, pastShown(view));
  const order = rowOrder(groups);
  const state = listState({ items, visible: visible.length, error: listError, filters: view, query: q, projects });
  const narrowed = hasChosenFilters(view) || q !== '';
  const current = selected === null ? null : (items?.find((d) => d.id === selected) ?? (detail?.decision.decision.id === selected ? detail.decision.decision : null));
  const shown = detail !== null && detail.decision.decision.id === selected ? detail.decision : null;

  useShownRow({
    fill: !narrow && pane.kind === 'detail',
    follow: !narrow,
    selected,
    order,
    filterKey: `${serverKey}|${view.status}|${pastShown(view)}`,
    settled: loaded?.key === serverKey,
    choose: (id) => setView((v) => ({ ...v, selected: id })),
  });

  function select(id: string) {
    setView((v) => (v.selected === id ? v : { ...v, selected: id }));
    setActionError(null);
    setPane({ kind: 'detail' });
  }

  /** Enter on a row, or a tap on a narrow screen: the detail takes the focus, or opens as a sheet. */
  function open(id: string) {
    select(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  /** A supersession link: a step in the browser's history (Back returns), the focus on the other question. */
  function jump(id: string) {
    setPane({ kind: 'detail' });
    pushView(jumpTo(view, id, items));
    requestAnimationFrame(() => titleRef.current?.focus());
  }

  function openForm(mode: FormMode, start: DecisionDraft, base: DecisionDetail | null) {
    setActionError(null);
    setPane({ kind: 'form', mode, start, base });
    if (narrow && !sheet.open) sheet.show();
  }

  function closeForm() {
    setActionError(null);
    setPane({ kind: 'detail' });
    if (narrow && current === null) sheet.hide();
  }

  function closeSheet() {
    sheet.hide();
    setPane({ kind: 'detail' });
  }

  /** Shows what the server returned for the decision just changed, and reloads the list. */
  function settle(d: DecisionDetail) {
    setDetail({ decision: d, related: [] });
    setActionError(null);
    setRefresh((r) => r + 1);
  }

  /** A refusal stays on screen with the server's reason; a 409 usually means it changed meanwhile, so reload. */
  function refused(e: unknown) {
    setActionError(message(e));
    setRefresh((r) => r + 1);
  }

  async function submit(draft: DecisionDraft) {
    if (pane.kind !== 'form') return;
    try {
      if (pane.mode === 'edit' && pane.base) {
        const fields: DecisionFields = changedFields(pane.start, draft);
        const d = Object.keys(fields).length === 0 ? pane.base : await decisionsApi.update(pane.base.decision.id, pane.base.decision.version, fields);
        settle(d);
        setPane({ kind: 'detail' });
      } else {
        const d = await decisionsApi.propose(draftFields(draft));
        settle(d);
        setView((v) => ({ ...v, selected: d.decision.id, status: v.status === '' || v.status === 'proposed' ? v.status : '' }));
        setPane({ kind: 'detail' });
      }
    } catch (e) {
      refused(e);
    }
  }

  const actions: DecisionActions | undefined = shown
    ? {
        edit: () => openForm('edit', draftFrom(shown), shown),
        supersede: () => openForm('supersede', supersedeDraft(shown), shown),
        accept: async () => {
          try {
            settle(await decisionsApi.judge(shown.decision.id, shown.decision.version, 'approved'));
          } catch (e) {
            refused(e);
          }
        },
        reject: async (note) => {
          try {
            settle(await decisionsApi.judge(shown.decision.id, shown.decision.version, 'rejected', note));
          } catch (e) {
            refused(e);
          }
        },
      }
    : undefined;

  const setFilters = (f: Partial<DecisionFilters>) => setView((v) => ({ ...v, ...f }));

  function clearFilters() {
    setView((v) => ({ ...v, projectId: '', status: '' }));
    onClearQuery();
  }

  /** Esc in the wide detail goes back to the selected row; inside a field it stays with the field. */
  function onDetailKey(e: KeyboardEvent<HTMLElement>) {
    if (e.key !== 'Escape' || e.defaultPrevented || (e.target as HTMLElement).closest('input, textarea, select')) return;
    e.preventDefault();
    if (pane.kind === 'form') setPane({ kind: 'detail' });
    focusRow(root.current, selected);
  }

  const detailPane =
    pane.kind === 'form' ? (
      <DecisionForm
        key={`${pane.mode}:${pane.base?.decision.id ?? ''}:${pane.base?.decision.version ?? ''}`}
        mode={pane.mode}
        start={pane.start}
        projects={projects}
        work={work}
        approver={pane.base?.approver ?? 'usr_owner'}
        onSubmit={submit}
        onCancel={closeForm}
        error={actionError}
        inSheet={narrow}
      />
    ) : current ? (
      <DecisionDetailView
        decision={current}
        detail={detail?.decision ?? null}
        related={detail?.decision.decision.id === current.id ? detail.related : null}
        onJump={jump}
        resolveDecision={(ref) => items?.find((d) => d.external_ref === ref)?.id ?? null}
        actions={actions}
        error={actionError}
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

  const formTitle = pane.kind === 'form' ? { new: '새 결정', edit: '결정 고치기', supersede: '결정 대체하기' }[pane.mode] : null;

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
        onNew={() => openForm('new', emptyDraft(view.projectId), null)}
      />
      {!narrow && (
        <section className="pane-detail" aria-label={formTitle ?? '결정 상세'} onKeyDown={onDetailKey}>
          {detailPane}
        </section>
      )}
      {narrow && sheet.open && (
        <Sheet title={formTitle ?? (current ? (refOf(current) ?? '결정 상세') : '결정 상세')} onClose={closeSheet}>
          {detailPane}
        </Sheet>
      )}
    </main>
  );
}
