import type { DecisionStatus } from '../icons/DecisionMark';
import type { Decision, DecisionDetail, Project, WorkItem } from '../workApi';
import { decisionStatusLabel } from '../workModel';

/*
 * What the decisions screen shows, as plain functions: the three status groups, the filters, the
 * address that keeps them, which empty state applies and what the detail reads. The server holds
 * the records; this only arranges what it returned.
 */

/** The status groups (DESIGN.md 「화면 배치」 4 for decisions): to decide, in force, past. */
export type Bucket = 'open' | 'current' | 'past';

export const BUCKETS: { bucket: Bucket; label: string; mark: DecisionStatus }[] = [
  { bucket: 'open', label: '정할 것', mark: 'proposed' },
  { bucket: 'current', label: '지금 유효', mark: 'accepted' },
  { bucket: 'past', label: '지난 것', mark: 'superseded' },
];

export function bucketOf(status: DecisionStatus): Bucket {
  if (status === 'proposed') return 'open';
  if (status === 'accepted') return 'current';
  return 'past';
}

/** The four statuses for the status chip: the two that count now, then the two that are past. */
export const STATUSES: DecisionStatus[] = ['proposed', 'accepted', 'rejected', 'superseded'];

/** Newest decision first; a decision with no date goes last, then by number, the higher first. */
export function byNewest(a: Decision, b: Decision): number {
  if (a.decided_on !== b.decided_on) {
    if (a.decided_on === null) return 1;
    if (b.decided_on === null) return -1;
    return b.decided_on.localeCompare(a.decided_on);
  }
  return (b.external_ref ?? '').localeCompare(a.external_ref ?? '');
}

export type DecisionGroup = {
  bucket: Bucket;
  label: string;
  mark: DecisionStatus;
  items: Decision[];
  /** 지난 것 starts folded: its head and count show, its rows do not. */
  folded: boolean;
};

/** The three groups in order, each newest first. A group shows even when it is empty. */
export function groupByStatus(items: Decision[], showPast: boolean): DecisionGroup[] {
  return BUCKETS.map(({ bucket, label, mark }) => ({
    bucket,
    label,
    mark,
    items: items.filter((d) => bucketOf(d.status) === bucket).sort(byNewest),
    folded: bucket === 'past' && !showPast,
  }));
}

/** The rows in the order they are drawn, for the arrow keys; a folded group has none. */
export const rowOrder = (groups: DecisionGroup[]) => groups.flatMap((g) => (g.folded ? [] : g.items.map((d) => d.id)));

export type DecisionFilters = {
  projectId: string;
  status: DecisionStatus | '';
  /** Unfold 지난 것 (superseded and rejected). */
  showPast: boolean;
};

export const NO_FILTERS: DecisionFilters = { projectId: '', status: '', showPast: false };

/** A status chip that asks for a past status unfolds 지난 것; otherwise the chip would show nothing. */
export const pastShown = (f: Pick<DecisionFilters, 'status' | 'showPast'>) => f.showPast || (f.status !== '' && bucketOf(f.status) === 'past');

/**
 * Status is applied here, so the chip can count every status. Project and the search words go to
 * the server (`project_id`, `q`), so the server keeps the one meaning of a search.
 */
export function filterDecisions(items: Decision[], status: DecisionStatus | ''): Decision[] {
  return status === '' ? items : items.filter((d) => d.status === status);
}

export type StatusOption = { status: DecisionStatus; label: string; count: number };

export function statusOptions(items: Decision[]): StatusOption[] {
  return STATUSES.map((status) => ({ status, label: decisionStatusLabel(status), count: items.filter((d) => d.status === status).length }));
}

/** Filters a person set, apart from unfolding 지난 것 and the search words. */
export const hasChosenFilters = (f: DecisionFilters) => f.projectId !== '' || f.status !== '';

/** What the list is filtered by, in words, for the empty state. */
export function conditions(f: DecisionFilters, query: string, projects: Project[]): string[] {
  const out: string[] = [];
  if (f.projectId !== '') out.push(`프로젝트 ${projects.find((p) => p.id === f.projectId)?.name ?? f.projectId}`);
  if (f.status !== '') out.push(`상태 ${decisionStatusLabel(f.status)}`);
  if (query.trim() !== '') out.push(`찾는 말 「${query.trim()}」`);
  return out;
}

/** The state of the whole list: loading, one of the three empty states, or rows. */
export type ListState = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'unused' } | { kind: 'filtered'; conditions: string[] } | { kind: 'rows' };

export function listState(a: {
  /** What the server returned for the project and search filters; null before the first read. */
  items: Decision[] | null;
  /** How many the status chip leaves, folded 지난 것 included. */
  visible: number;
  error: string | null;
  filters: DecisionFilters;
  query: string;
  projects: Project[];
}): ListState {
  if (a.items === null) return a.error === null ? { kind: 'loading' } : { kind: 'error', message: a.error };
  if (a.visible > 0) return { kind: 'rows' };
  if (hasChosenFilters(a.filters) || a.query.trim() !== '') return { kind: 'filtered', conditions: conditions(a.filters, a.query, a.projects) };
  return { kind: 'unused' };
}

/** Filters and the selected decision, kept in the address so a reload shows the same list. */
export type DecisionsView = DecisionFilters & { selected: string | null };

const STATUS_IDS: ReadonlySet<string> = new Set(STATUSES);

/** `#decisions?project=prj_a&status=superseded&past=1&item=dcs_b`; anything unknown is dropped. */
export function readView(hash: string): DecisionsView {
  const at = hash.indexOf('?');
  const p = new URLSearchParams(at < 0 ? '' : hash.slice(at + 1));
  const status = p.get('status') ?? '';
  return {
    projectId: p.get('project') ?? '',
    status: STATUS_IDS.has(status) ? (status as DecisionStatus) : '',
    showPast: p.get('past') === '1',
    selected: p.get('item') || null,
  };
}

export function writeView(v: DecisionsView): string {
  const p = new URLSearchParams();
  if (v.projectId) p.set('project', v.projectId);
  if (v.status) p.set('status', v.status);
  if (v.showPast) p.set('past', '1');
  if (v.selected) p.set('item', v.selected);
  const q = p.toString();
  return q ? `#decisions?${q}` : '#decisions';
}

/**
 * The decision's first paragraph for a row's second line. Most zm-docs decisions open with the day
 * they were made (「2026-10-12 소유자가 정했다.」); the row already shows that day just before, so
 * the same day at the start is left out. The preview is Markdown, so its marks (bold, code, a link's
 * address) are taken off; the words stay.
 */
export function previewText(d: Pick<Decision, 'choice_preview' | 'decided_on'>): string | null {
  const text = plainMarks(d.choice_preview?.trim() ?? '');
  const day = d.decided_on;
  const rest = day && (text === day || text.startsWith(`${day} `)) ? text.slice(day.length).trim() : text;
  return rest === '' ? null : rest;
}

/** Inline Markdown marks off one line of text: `[words](address)` keeps the words, `**`, `__` and backticks go. */
export function plainMarks(text: string): string {
  return text
    .replace(/!?\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\*\*|__|`/g, '')
    .trim();
}

/**
 * Where a supersession link goes: the other decision selected, and its row shown in the list. A
 * status chip that would hide it is cleared, and 지난 것 unfolds when it is there. A decision the
 * list does not have (another project, other search words) is shown in the detail only.
 */
export function jumpTo(view: DecisionsView, id: string, items: Decision[] | null): DecisionsView {
  const next = { ...view, selected: id };
  const target = items?.find((d) => d.id === id);
  if (!target) return next;
  if (next.status !== '' && next.status !== target.status) next.status = '';
  if (bucketOf(target.status) === 'past' && !pastShown(next)) next.showPast = true;
  return next;
}

/**
 * The sections after the decision itself, in the file's order. The server takes the decision from
 * the section 「결정」 (or else the first heading ending in 「결정」); the detail shows it first as
 * 「고른 안」, so that section is not repeated.
 */
export function otherSections(detail: Pick<DecisionDetail, 'choice' | 'sections'>): DecisionDetail['sections'] {
  const choice = detail.choice?.trim() ?? null;
  if (choice === null) return detail.sections;
  const at = detail.sections.findIndex((s) => s.body.trim() === choice);
  return at < 0 ? detail.sections : detail.sections.filter((_, i) => i !== at);
}

/**
 * Work that writes this decision's number in its title, number or next action. The server keeps no
 * link between work and decisions; its search matches any part of a word, so `DEC-0015` would
 * also find `DEC-00150`. Only whole numbers count here.
 */
export function mentioning(items: WorkItem[], ref: string): WorkItem[] {
  const whole = new RegExp(`(^|[^0-9A-Za-z])${ref.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?![0-9])`);
  return items.filter((w) => [w.title, w.external_ref, w.next_action].some((f) => f !== null && whole.test(f)));
}

/** The work screen's address for one item; closed work only shows there with closed work shown. */
export function workHref(item: Pick<WorkItem, 'id' | 'stage'>): string {
  const p = new URLSearchParams();
  if (item.stage === 'done' || item.stage === 'cancelled') p.set('closed', '1');
  p.set('item', item.id);
  return `#work?${p.toString()}`;
}
