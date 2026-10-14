import { useRef, useState, type KeyboardEvent } from 'react';
import { documentsApi, type Doc, type DocDetail } from '../documentsApi';
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
import { ApiError, workApi, type Decision, type Project, type WorkItem } from '../workApi';
import { DocumentDetailView, type DocActions } from './DocumentDetailView';
import { DocumentForm } from './DocumentForm';
import { DocumentChips, DocumentList } from './DocumentList';
import {
  changedDocFields,
  docDraftFrom,
  emptyDocDraft,
  filterDocs,
  groupDocs,
  hasChosenFilters,
  listState,
  readView,
  registerFields,
  rowOrder,
  SOURCE_LABELS,
  sourceCounts,
  writeView,
  type DocDraft,
  type DocFilters,
} from './documentView';
import { VersionWriter } from './VersionWriter';

type Props = {
  onError: (e: unknown) => void;
  onRead: () => void;
  chipHost: HTMLElement | null;
  /** Words typed in the command line: title and Git path. */
  query: string;
  onClearQuery: () => void;
};

/** The detail pane shows a document, or a form: a new document, a change of its fields, or the next version. */
type Pane = { kind: 'detail' } | { kind: 'new' } | { kind: 'edit'; base: DocDetail; start: DocDraft } | { kind: 'write'; base: DocDetail };

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * Documents (FN-0601, FN-0602), in the other screens' grammar: the list and the detail side by
 * side, the list grouped by approval. Filters, the selected document and the version shown live
 * in the address. Product documents are written here version by version; Git documents are only
 * pointed at, and their text changes in Git.
 */
export function Documents({ onError, onRead, chipHost, query, onClearQuery }: Props) {
  const narrow = useNarrow();
  const [view, setView, pushView] = useAddress('documents', readView, writeView);
  const [loaded, setLoaded] = useState<{ key: string; list: Doc[] } | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [projects, setProjects] = useState<Project[]>([]);
  const [detail, setDetail] = useState<DocDetail | null>(null);
  const [pane, setPane] = useState<Pane>({ kind: 'detail' });
  /** Work and decisions a form can link; read when a form opens. */
  const [links, setLinks] = useState<{ work: WorkItem[]; decisions: Decision[] } | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
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
        documentsApi.list({ projectId: view.projectId || undefined, q: q || undefined }, signal),
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
      const d = await documentsApi.detail(selected, view.version ?? undefined, signal);
      if (alive()) setDetail(d);
    },
    15000,
    [selected, view.version, showDetail, refresh],
    (e) => {
      // A document or version the server does not have: drop it from the address instead of an alarm every 15 seconds.
      if (e instanceof ApiError && e.status === 404) setView((v) => (v.selected === selected ? { ...v, selected: v.version ? v.selected : null, version: null } : v));
      else onError(e);
    },
  );

  const formOpen = pane.kind === 'new' || pane.kind === 'edit';
  usePoll(
    async (alive, signal) => {
      if (!formOpen) return;
      const [work, decisions] = await Promise.all([workApi.workItems({ open: false, limit: 500 }, signal), workApi.decisions({}, signal)]);
      if (alive()) setLinks({ work, decisions });
    },
    60000,
    [formOpen],
    onError,
  );

  const items = loaded?.list ?? null;
  const visible = items ? filterDocs(items, view.source) : [];
  const groups = groupDocs(visible);
  const order = rowOrder(groups);
  const state = listState({ items, visible: visible.length, error: listError, filters: view, query: q, projects });
  const narrowed = hasChosenFilters(view) || q !== '';
  const current = selected === null ? null : (items?.find((d) => d.id === selected) ?? (detail?.document.id === selected ? detail.document : null));
  const shown = detail !== null && detail.document.id === selected ? detail : null;

  useShownRow({
    fill: !narrow && pane.kind === 'detail',
    follow: !narrow,
    selected,
    order,
    filterKey: `${serverKey}|${view.source}`,
    settled: loaded?.key === serverKey,
    choose: (id) => setView((v) => ({ ...v, selected: id, version: null })),
  });

  function select(id: string) {
    setView((v) => (v.selected === id ? v : { ...v, selected: id, version: null }));
    setActionError(null);
    setPane({ kind: 'detail' });
  }

  function open(id: string) {
    select(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  function openPane(next: Pane) {
    setActionError(null);
    setPane(next);
    if (narrow && !sheet.open) sheet.show();
  }

  function closePane() {
    setActionError(null);
    setPane({ kind: 'detail' });
    if (narrow && current === null) sheet.hide();
  }

  function closeSheet() {
    sheet.hide();
    setPane({ kind: 'detail' });
  }

  /** What the server returned for the document just changed: shown at once, the list read again. */
  function settle(d: DocDetail, version: number | null = null) {
    setDetail(d);
    setActionError(null);
    setRefresh((r) => r + 1);
    setView((v) => ({ ...v, selected: d.document.id, version }));
    setPane({ kind: 'detail' });
  }

  function refused(e: unknown) {
    setActionError(message(e));
    setRefresh((r) => r + 1);
  }

  const run = (work: () => Promise<void>) => work().catch(refused);

  const actions: DocActions | undefined = shown
    ? {
        write: () =>
          void run(async () => {
            // The writer starts from the newest body, whichever version is shown now.
            const newest = view.version === null ? shown : await documentsApi.detail(shown.document.id);
            openPane({ kind: 'write', base: newest });
          }),
        record: (commit, note) =>
          run(async () => settle(await documentsApi.addVersion(shown.document.id, { based_on: shown.versions[0]?.number ?? null, git_commit_sha: commit, note }))),
        approve: (number, note) => run(async () => settle(await documentsApi.approve(shown.document.id, number, note), number)),
        edit: () => openPane({ kind: 'edit', base: shown, start: docDraftFrom(shown) }),
        show: (number) => {
          pushView({ ...view, version: number === shown.versions[0]?.number ? null : number });
          requestAnimationFrame(() => titleRef.current?.focus());
        },
      }
    : undefined;

  async function register(draft: DocDraft) {
    await run(async () => settle(await documentsApi.register(registerFields(draft))));
  }

  async function saveEdit(draft: DocDraft) {
    if (pane.kind !== 'edit') return;
    const fields = changedDocFields(pane.start, draft);
    await run(async () =>
      settle(Object.keys(fields).length === 0 ? pane.base : await documentsApi.update(pane.base.document.id, pane.base.document.version, fields)),
    );
  }

  async function saveVersion(body: string, note: string) {
    if (pane.kind !== 'write') return;
    const base = pane.base;
    await run(async () =>
      settle(await documentsApi.addVersion(base.document.id, { based_on: base.versions[0]?.number ?? null, body_markdown: body, note: note || undefined })),
    );
  }

  const setFilters = (f: Partial<DocFilters>) => setView((v) => ({ ...v, ...f }));

  function clearFilters() {
    setView((v) => ({ ...v, projectId: '', source: '' }));
    onClearQuery();
  }

  function onDetailKey(e: KeyboardEvent<HTMLElement>) {
    if (e.key !== 'Escape' || e.defaultPrevented || (e.target as HTMLElement).closest('input, textarea, select')) return;
    e.preventDefault();
    if (pane.kind !== 'detail') setPane({ kind: 'detail' });
    focusRow(root.current, selected);
  }

  const paneTitle = { detail: null, new: '새 문서', edit: '문서 정보 고치기', write: '새 판 쓰기' }[pane.kind];
  const detailPane =
    pane.kind === 'new' ? (
      <DocumentForm
        mode="new"
        start={emptyDocDraft(view.projectId)}
        projects={projects}
        work={links?.work ?? null}
        decisions={links?.decisions ?? null}
        onSubmit={register}
        onCancel={closePane}
        error={actionError}
        inSheet={narrow}
      />
    ) : pane.kind === 'edit' ? (
      <DocumentForm
        key={`${pane.base.document.id}:${pane.base.document.version}`}
        mode="edit"
        start={pane.start}
        projects={projects}
        work={links?.work ?? null}
        decisions={links?.decisions ?? null}
        onSubmit={saveEdit}
        onCancel={closePane}
        error={actionError}
        inSheet={narrow}
      />
    ) : pane.kind === 'write' ? (
      <VersionWriter key={pane.base.document.id} base={pane.base} onSave={saveVersion} onCancel={closePane} error={actionError} inSheet={narrow} />
    ) : current ? (
      <DocumentDetailView doc={current} detail={detail} actions={actions} error={actionError} titleRef={titleRef} inSheet={narrow} />
    ) : selected !== null ? (
      <RowSkeleton rows={3} />
    ) : state.kind === 'rows' ? (
      <EmptyState kind="empty" title="고른 문서가 없다">
        목록에서 문서를 고르면 여기에 보인다.
      </EmptyState>
    ) : null;

  return (
    <main className="panes doc" ref={root}>
      <h1 className="sr">문서</h1>
      <DocumentChips host={chipHost} filters={view} onFilters={setFilters} projects={projects} counts={sourceCounts(items ?? [])} />
      <DocumentList
        state={state}
        groups={groups}
        head={{ base: '문서', count: items === null ? null : visible.length, narrowed }}
        selected={selected}
        onSelect={select}
        onPick={narrow ? open : select}
        onOpen={open}
        onNew={() => openPane({ kind: 'new' })}
        onClearFilters={clearFilters}
        onReload={() => setRefresh((r) => r + 1)}
      />
      {!narrow && (
        <section className="pane-detail" aria-label={paneTitle ?? '문서 상세'} onKeyDown={onDetailKey}>
          {detailPane}
        </section>
      )}
      {narrow && sheet.open && (
        <Sheet title={paneTitle ?? (current ? `${SOURCE_LABELS[current.source_of_truth]} 문서` : '문서 상세')} onClose={closeSheet}>
          {detailPane}
        </Sheet>
      )}
    </main>
  );
}
