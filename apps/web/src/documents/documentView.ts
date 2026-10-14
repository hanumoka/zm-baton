import type { IconName } from '../icons/Icon';
import type { Doc, DocDetail, DocFields, DocSource, LinkedDecision } from '../documentsApi';
import type { HistoryEntry, Project } from '../workApi';

/*
 * What the documents screen shows, as plain functions: the groups, the filters and the address
 * that keeps them, which empty state applies, the form's data and the history lines. The server
 * holds the records; this only arranges what it returned.
 */

/** Documents have no one holding them, so they group by where their approval stands (DESIGN.md 「화면 배치」 4). */
export type Bucket = 'review' | 'approved' | 'none';

export const BUCKETS: { bucket: Bucket; label: string; icon: IconName }[] = [
  { bucket: 'review', label: '승인 기다림', icon: 'wait' },
  { bucket: 'approved', label: '승인됨', icon: 'done' },
  { bucket: 'none', label: '판 없음', icon: 'doc' },
];

export function bucketOf(d: Pick<Doc, 'latest'>): Bucket {
  if (d.latest === null) return 'none';
  return d.latest.state === 'approved' ? 'approved' : 'review';
}

export const SOURCE_LABELS: Record<DocSource, string> = { product: '제품 안', git: 'Git' };

export const sourceIcon = (s: DocSource): IconName => (s === 'git' ? 'git-file' : 'doc');

/** 「판 3 초안」, 「판 3 승인됨」 or 「판 없음」 for a row and the facts line. */
export function versionLabel(d: Pick<Doc, 'latest'>): string {
  if (d.latest === null) return '판 없음';
  return `판 ${d.latest.number} ${d.latest.state === 'approved' ? '승인됨' : '초안'}`;
}

export type DocGroup = { bucket: Bucket; label: string; icon: IconName; items: Doc[] };

/** The three groups in order, each most recently changed first. A group shows even when empty. */
export function groupDocs(items: Doc[]): DocGroup[] {
  return BUCKETS.map(({ bucket, label, icon }) => ({
    bucket,
    label,
    icon,
    items: items.filter((d) => bucketOf(d) === bucket).sort((a, b) => b.updated_at.localeCompare(a.updated_at)),
  }));
}

export const rowOrder = (groups: DocGroup[]) => groups.flatMap((g) => g.items.map((d) => d.id));

export type DocFilters = { projectId: string; source: DocSource | '' };

export const NO_FILTERS: DocFilters = { projectId: '', source: '' };

/** The source is applied here, so its chip can count both; the project and search words go to the server. */
export function filterDocs(items: Doc[], source: DocSource | ''): Doc[] {
  return source === '' ? items : items.filter((d) => d.source_of_truth === source);
}

export const sourceCounts = (items: Doc[]) => ({
  product: items.filter((d) => d.source_of_truth === 'product').length,
  git: items.filter((d) => d.source_of_truth === 'git').length,
});

export const hasChosenFilters = (f: DocFilters) => f.projectId !== '' || f.source !== '';

export function conditions(f: DocFilters, query: string, projects: Project[]): string[] {
  const out: string[] = [];
  if (f.projectId !== '') out.push(`프로젝트 ${projects.find((p) => p.id === f.projectId)?.name ?? f.projectId}`);
  if (f.source !== '') out.push(`정본 위치 ${SOURCE_LABELS[f.source]}`);
  if (query.trim() !== '') out.push(`찾는 말 「${query.trim()}」`);
  return out;
}

export type ListState = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'unused' } | { kind: 'filtered'; conditions: string[] } | { kind: 'rows' };

export function listState(a: { items: Doc[] | null; visible: number; error: string | null; filters: DocFilters; query: string; projects: Project[] }): ListState {
  if (a.items === null) return a.error === null ? { kind: 'loading' } : { kind: 'error', message: a.error };
  if (a.visible > 0) return { kind: 'rows' };
  if (hasChosenFilters(a.filters) || a.query.trim() !== '') return { kind: 'filtered', conditions: conditions(a.filters, a.query, a.projects) };
  return { kind: 'unused' };
}

/** Filters, the selected document and the version shown, kept in the address. */
export type DocsView = DocFilters & { selected: string | null; version: number | null };

/** `#documents?project=prj_a&source=git&item=doc_b&ver=2`; anything unknown is dropped. */
export function readView(hash: string): DocsView {
  const at = hash.indexOf('?');
  const p = new URLSearchParams(at < 0 ? '' : hash.slice(at + 1));
  const source = p.get('source') ?? '';
  const ver = Number(p.get('ver'));
  return {
    projectId: p.get('project') ?? '',
    source: source === 'product' || source === 'git' ? source : '',
    selected: p.get('item') || null,
    version: Number.isInteger(ver) && ver > 0 ? ver : null,
  };
}

export function writeView(v: DocsView): string {
  const p = new URLSearchParams();
  if (v.projectId) p.set('project', v.projectId);
  if (v.source) p.set('source', v.source);
  if (v.selected) p.set('item', v.selected);
  if (v.selected && v.version) p.set('ver', String(v.version));
  const q = p.toString();
  return q ? `#documents?${q}` : '#documents';
}

/** What the register and change form holds. */
export type DocDraft = {
  title: string;
  source: DocSource;
  projectId: string;
  body: string;
  note: string;
  repo: string;
  path: string;
  branch: string;
  commit: string;
  workIds: string[];
  decisionIds: string[];
};

export function emptyDocDraft(projectId = ''): DocDraft {
  return { title: '', source: 'product', projectId, body: '', note: '', repo: '', path: '', branch: '', commit: '', workIds: [], decisionIds: [] };
}

export function docDraftFrom(d: DocDetail): DocDraft {
  const doc = d.document;
  return {
    ...emptyDocDraft(doc.project?.id ?? ''),
    title: doc.title,
    source: doc.source_of_truth,
    repo: doc.git_ref?.repo ?? '',
    path: doc.git_ref?.path ?? '',
    branch: doc.git_ref?.branch ?? '',
    workIds: d.work_items.map((w) => w.id),
    decisionIds: d.decisions.map((x) => x.id),
  };
}

/** Everything for a new document: a product one sends a first body only when there is one, a Git one its place and commit. */
export function registerFields(d: DocDraft): DocFields {
  const out: DocFields = {
    title: d.title.trim(),
    source_of_truth: d.source,
    project_id: d.projectId || null,
    work_item_ids: d.workIds,
    decision_ids: d.decisionIds,
  };
  if (d.note.trim() !== '') out.note = d.note.trim();
  if (d.source === 'product') {
    if (d.body.trim() !== '') out.body_markdown = d.body;
  } else {
    out.git = { repo: d.repo.trim(), path: d.path.trim() };
    if (d.branch.trim() !== '') out.git.branch = d.branch.trim();
    if (d.commit.trim() !== '') out.git.commit = d.commit.trim();
  }
  return out;
}

/** Only the document's own fields that differ from [before]; the source of truth never changes here. */
export function changedDocFields(before: DocDraft, after: DocDraft): DocFields {
  const out: DocFields = {};
  if (after.title.trim() !== before.title.trim()) out.title = after.title.trim();
  if (after.projectId !== before.projectId) out.project_id = after.projectId || null;
  const place = (d: DocDraft) => JSON.stringify([d.repo.trim(), d.path.trim(), d.branch.trim()]);
  if (after.source === 'git' && place(after) !== place(before)) {
    out.git = { repo: after.repo.trim(), path: after.path.trim(), ...(after.branch.trim() ? { branch: after.branch.trim() } : {}) };
  }
  if (JSON.stringify(after.workIds) !== JSON.stringify(before.workIds)) out.work_item_ids = after.workIds;
  if (JSON.stringify(after.decisionIds) !== JSON.stringify(before.decisionIds)) out.decision_ids = after.decisionIds;
  return out;
}

const COMMIT = /^[0-9a-fA-F]{7,64}$/;

/** Why the form cannot be sent yet, or null. The server checks again. */
export function docDraftProblem(d: DocDraft): string | null {
  if (d.title.trim() === '') return '제목을 쓰면 보낼 수 있다.';
  if (d.source === 'git') {
    if (d.repo.trim() === '' || d.path.trim() === '') return 'Git 문서는 저장소와 경로를 쓴다.';
    if (/^([/\\~]|[A-Za-z]:)/.test(d.path.trim())) return '경로는 저장소 뿌리에서 시작한다. 이 PC의 절대 경로는 받지 않는다.';
    if (d.commit.trim() !== '' && !COMMIT.test(d.commit.trim())) return '커밋은 16진수 7~64자다.';
  }
  return null;
}

export const commitProblem = (c: string) => (COMMIT.test(c.trim()) ? null : '커밋은 16진수 7~64자다.');

const FIELD_LABELS: Record<string, string> = {
  title: '제목',
  project_id: '프로젝트',
  git: 'Git 위치',
  work_item_ids: '영향 받는 작업',
  decision_ids: '관련 결정',
};

/** One line for an entry of a document's history. */
export function docHistoryLine(h: HistoryEntry): string {
  const d = h.details;
  const note = d.note ? ` — ${String(d.note)}` : '';
  switch (h.action) {
    case 'document.registered':
      return `등록함 (${SOURCE_LABELS[(d.source_of_truth as DocSource) ?? 'product'] ?? String(d.source_of_truth)})`;
    case 'document.updated':
      return `고침: ${((d.fields as string[] | undefined) ?? []).map((f) => FIELD_LABELS[f] ?? f).join(', ')}`;
    case 'document.version_added':
      return `판 ${String(d.number ?? '')} 더함${d.commit ? ` (커밋 ${String(d.commit).slice(0, 10)})` : ''}${note}`;
    case 'document.version_approved':
      return `판 ${String(d.number ?? '')} 승인함${note}`;
    default:
      return h.action;
  }
}

/** The decisions screen's address for a linked decision; a past one shows with 지난 것 unfolded. */
export function linkedDecisionHref(d: Pick<LinkedDecision, 'id' | 'status'>): string {
  const p = new URLSearchParams();
  if (d.status === 'superseded' || d.status === 'rejected') p.set('past', '1');
  p.set('item', d.id);
  return `#decisions?${p.toString()}`;
}
