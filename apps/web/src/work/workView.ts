import type { Project, Stage, WorkItem } from '../workApi';
import { STAGES, stageLabel } from '../workModel';

export { step } from '../ui/rowKeys';

/*
 * What the work screen shows, as plain functions: who holds each item now, the filters, the
 * address that keeps them and which empty state applies. The keyboard steps are shared (ui/rowKeys).
 * The server holds the rules for stages and moves; this only arranges what it returned.
 */

/** Who holds the work now (DESIGN.md 「화면 배치」 4, one-person organisation). */
export type Holder = 'wait' | 'ai' | 'person' | 'done';

export const HOLDERS: { holder: Holder; label: string }[] = [
  { holder: 'wait', label: '기다림' },
  { holder: 'ai', label: 'AI 세션' },
  { holder: 'person', label: '사람' },
  { holder: 'done', label: '끝남' },
];

const CLOSED: ReadonlySet<Stage> = new Set<Stage>(['done', 'cancelled']);

/** `executor_kind` values that are AI, from the server's schema check: human, manager, shared_ai. */
const AI_EXECUTORS: ReadonlySet<string> = new Set(['manager', 'shared_ai']);

export const isClosed = (item: Pick<WorkItem, 'stage'>) => CLOSED.has(item.stage);

/** 막힘: an open item with a wait reason. A closed item keeps its own place under 끝남. */
export const isBlocked = (item: Pick<WorkItem, 'stage' | 'wait_reasons'>) => !CLOSED.has(item.stage) && item.wait_reasons.length > 0;

export function holderOf(item: Pick<WorkItem, 'stage' | 'wait_reasons' | 'executor_kind'>): Holder {
  if (isClosed(item)) return 'done';
  if (item.wait_reasons.length > 0) return 'wait';
  if (item.executor_kind !== null && AI_EXECUTORS.has(item.executor_kind)) return 'ai';
  // A person, or nobody yet: a person has to pick it up, so it stays with the people.
  return 'person';
}

const STAGE_INDEX = new Map(STAGES.map((s, i) => [s.stage, i]));

export type HolderGroup = { holder: Holder; label: string; items: WorkItem[] };

/** The groups in order; 끝남 only while closed work is shown. A group shows even when it is empty. */
export function groupByHolder(items: WorkItem[], showClosed: boolean): HolderGroup[] {
  return HOLDERS.filter((h) => showClosed || h.holder !== 'done').map(({ holder, label }) => ({
    holder,
    label,
    items: items
      .filter((i) => holderOf(i) === holder)
      // Stage order as the per-stage list read before, then the most recently changed first so what is moving is on top.
      .sort(
        (a, b) =>
          (STAGE_INDEX.get(a.stage) ?? 0) - (STAGE_INDEX.get(b.stage) ?? 0) ||
          b.updated_at.localeCompare(a.updated_at) ||
          (b.external_ref ?? '').localeCompare(a.external_ref ?? ''),
      ),
  }));
}

/** The rows in the order they are drawn, for the arrow keys. */
export const rowOrder = (groups: HolderGroup[]) => groups.flatMap((g) => g.items.map((i) => i.id));

export type WorkFilters = {
  projectId: string;
  stage: Stage | '';
  blocked: boolean;
  showClosed: boolean;
};

export const NO_FILTERS: WorkFilters = { projectId: '', stage: '', blocked: false, showClosed: false };

/**
 * Stage and 막힘 are applied here. Project, open or closed, and the search words go to the server
 * (`project_id`, `open`, `q`), so the server keeps the one meaning of a search.
 */
export function filterItems(items: WorkItem[], f: Pick<WorkFilters, 'stage' | 'blocked'>): WorkItem[] {
  return items.filter((i) => (f.stage === '' || i.stage === f.stage) && (!f.blocked || isBlocked(i)));
}

export type StageOption = { stage: Stage; label: string; count: number };

/**
 * Stages offered by the stage chip: open ones, closed ones while closed work is shown, and a
 * skipped stage only while an item still stands in it.
 */
export function stageOptions(items: WorkItem[], skipped: Stage[], showClosed: boolean): StageOption[] {
  return STAGES.map(({ stage, label }) => ({ stage, label, count: items.filter((i) => i.stage === stage).length })).filter(
    (o) => (showClosed || !CLOSED.has(o.stage)) && (!skipped.includes(o.stage) || o.count > 0),
  );
}

/** Filters a person set, apart from hiding closed work (the default) and the search words. */
export const hasChosenFilters = (f: WorkFilters) => f.projectId !== '' || f.stage !== '' || f.blocked;

/** What the list is filtered by, in words, for the empty state and the list head. */
export function conditions(f: WorkFilters, query: string, projects: Project[]): string[] {
  const out: string[] = [];
  if (f.projectId !== '') out.push(`프로젝트 ${projects.find((p) => p.id === f.projectId)?.name ?? f.projectId}`);
  if (f.stage !== '') out.push(`단계 ${stageLabel(f.stage)}`);
  if (f.blocked) out.push('막힘만');
  if (query.trim() !== '') out.push(`찾는 말 「${query.trim()}」`);
  if (!f.showClosed) out.push('완료·취소 숨김');
  return out;
}

/** The state of the whole list: loading, one of the three empty states, or rows. */
export type ListState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'unused' }
  | { kind: 'no-open' }
  | { kind: 'filtered'; conditions: string[] }
  | { kind: 'rows' };

export function listState(a: {
  /** What the server returned for the project, open and search filters; null before the first read. */
  items: WorkItem[] | null;
  visible: number;
  error: string | null;
  filters: WorkFilters;
  query: string;
  /** Whether closed work exists, asked only when nothing open came back and nothing is filtered. */
  closedExist: boolean | null;
  projects: Project[];
}): ListState {
  if (a.items === null) return a.error === null ? { kind: 'loading' } : { kind: 'error', message: a.error };
  if (a.visible > 0) return { kind: 'rows' };
  if (hasChosenFilters(a.filters) || a.query.trim() !== '') return { kind: 'filtered', conditions: conditions(a.filters, a.query, a.projects) };
  if (!a.filters.showClosed && a.closedExist === true) return { kind: 'no-open' };
  return { kind: 'unused' };
}

/** Filters and the selected item, kept in the address so a reload shows the same list. */
export type WorkView = WorkFilters & { selected: string | null };

const STAGE_IDS: ReadonlySet<string> = new Set(STAGES.map((s) => s.stage));

/** `#work?project=prj_a&stage=in_progress&blocked=1&closed=1&item=wrk_b`; anything unknown is dropped. */
export function readView(hash: string): WorkView {
  const at = hash.indexOf('?');
  const p = new URLSearchParams(at < 0 ? '' : hash.slice(at + 1));
  const showClosed = p.get('closed') === '1';
  const stage = p.get('stage') ?? '';
  const known = STAGE_IDS.has(stage) && (showClosed || !CLOSED.has(stage as Stage));
  return {
    projectId: p.get('project') ?? '',
    stage: known ? (stage as Stage) : '',
    blocked: p.get('blocked') === '1',
    showClosed,
    selected: p.get('item') || null,
  };
}

export function writeView(v: WorkView): string {
  const p = new URLSearchParams();
  if (v.projectId) p.set('project', v.projectId);
  if (v.stage) p.set('stage', v.stage);
  if (v.blocked) p.set('blocked', '1');
  if (v.showClosed) p.set('closed', '1');
  if (v.selected) p.set('item', v.selected);
  const q = p.toString();
  return q ? `#work?${q}` : '#work';
}
