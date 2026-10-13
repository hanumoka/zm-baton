import type { IconName } from '../icons/Icon';
import { repoName } from '../inboxModel';
import type { InboxItem, SessionView } from '../sessionsApi';

/*
 * What the session screen shows, as plain functions: live sessions and the rest, the filters and
 * the search, the address that keeps them, which empty state applies, and the first-screen item
 * of a conflicting file. The server holds the sessions; this only arranges what it returned.
 */

export type GroupKey = 'live' | 'other';

export const GROUPS: { key: GroupKey; label: string; mark: IconName; aside?: string }[] = [
  { key: 'live', label: '살아 있음', mark: 'session' },
  { key: 'other', label: '그 밖', mark: 'done', aside: '끝남, 등록 끔, 만료' },
];

export type SessionGroup = { key: GroupKey; label: string; mark: IconName; aside?: string; items: SessionView[] };

/** Live first, then (only with ended sessions shown) the rest; each with the newest signal first. */
export function groupSessions(items: SessionView[], showEnded: boolean): SessionGroup[] {
  return GROUPS.filter((g) => showEnded || g.key === 'live').map((g) => ({
    ...g,
    items: items
      .filter((s) => (s.state === 'live') === (g.key === 'live'))
      .sort((a, b) => b.last_seen_at.localeCompare(a.last_seen_at) || a.id.localeCompare(b.id)),
  }));
}

/** The rows in the order they are drawn, for the arrow keys. */
export const rowOrder = (groups: SessionGroup[]) => groups.flatMap((g) => g.items.map((s) => s.id));

/** A session's repository as a filter value: its key across clones, or 「none」 when it named none. */
export const NO_REPO = 'none';
export const repoId = (s: Pick<SessionView, 'repo_key'>) => s.repo_key ?? NO_REPO;

export type RepoOption = { id: string; label: string; count: number };

/** The repositories the listed sessions are in, most sessions first; the chosen one stays offered. */
export function repoOptions(items: SessionView[], chosen: string): RepoOption[] {
  const by = new Map<string, RepoOption>();
  for (const s of items) {
    const id = repoId(s);
    const o = by.get(id) ?? { id, label: repoName(s), count: 0 };
    o.count += 1;
    by.set(id, o);
  }
  if (chosen !== '' && !by.has(chosen)) by.set(chosen, { id: chosen, label: chosen === NO_REPO ? '저장소 없음' : chosen, count: 0 });
  return [...by.values()].sort((a, b) => b.count - a.count || a.label.localeCompare(b.label));
}

export type SessionFilters = {
  /** A repository key, NO_REPO, or '' for every repository. */
  repo: string;
  /** Show ended, turned-off and expired sessions too. */
  ended: boolean;
};

export const NO_FILTERS: SessionFilters = { repo: '', ended: false };

/** The words in the command line find a session by its id, what it works on (when shared) and its branch. */
export function matches(s: SessionView, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (q === '') return true;
  const fields = [s.id, s.shared ? s.current_work : null, s.repository?.branch ?? null];
  return fields.some((f) => f !== null && f !== undefined && f.toLowerCase().includes(q));
}

/** The server knows no search for sessions, so the repository, the search and live-or-not are applied here. */
export function filterSessions(items: SessionView[], f: SessionFilters, query: string): SessionView[] {
  return items.filter((s) => (f.ended || s.state === 'live') && (f.repo === '' || repoId(s) === f.repo) && matches(s, query));
}

/** Filters a person set, apart from showing ended sessions and the search words. */
export const hasChosenFilters = (f: SessionFilters) => f.repo !== '';

/** What the list is filtered by, in words, for the empty state. */
export function conditions(f: SessionFilters, query: string, repos: RepoOption[]): string[] {
  const out: string[] = [];
  if (f.repo !== '') out.push(`저장소 ${repos.find((r) => r.id === f.repo)?.label ?? f.repo}`);
  if (query.trim() !== '') out.push(`찾는 말 「${query.trim()}」`);
  if (!f.ended) out.push('끝난 세션 숨김');
  return out;
}

/** The state of the whole list: loading, one of the empty states, or rows. */
export type ListState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'unused' }
  | { kind: 'no-live' }
  | { kind: 'filtered'; conditions: string[] }
  | { kind: 'rows' };

export function listState(a: {
  /** What the server returned; null before the first read. */
  items: SessionView[] | null;
  visible: number;
  error: string | null;
  filters: SessionFilters;
  query: string;
  /** Whether sessions that are not live exist, asked only when no live one came back and nothing is filtered. */
  endedExist: boolean | null;
  repos: RepoOption[];
}): ListState {
  if (a.items === null) return a.error === null ? { kind: 'loading' } : { kind: 'error', message: a.error };
  if (a.visible > 0) return { kind: 'rows' };
  if (hasChosenFilters(a.filters) || a.query.trim() !== '') return { kind: 'filtered', conditions: conditions(a.filters, a.query, a.repos) };
  if (!a.filters.ended && a.endedExist === true) return { kind: 'no-live' };
  return { kind: 'unused' };
}

/** Filters and the selected session, kept in the address so a reload shows the same list. */
export type SessionsView = SessionFilters & { selected: string | null };

/** `#sessions?repo=git:abc&ended=1&item=ses_b`; the same form as inboxModel's sessionHref. */
export function readView(hash: string): SessionsView {
  const at = hash.indexOf('?');
  const p = new URLSearchParams(at < 0 ? '' : hash.slice(at + 1));
  return { repo: p.get('repo') ?? '', ended: p.get('ended') === '1', selected: p.get('item') || null };
}

export function writeView(v: SessionsView): string {
  const p = new URLSearchParams();
  if (v.repo) p.set('repo', v.repo);
  if (v.ended) p.set('ended', '1');
  if (v.selected) p.set('item', v.selected);
  const q = p.toString();
  return q ? `#sessions?${q}` : '#sessions';
}

/** The title of a session: what it works on, or why that is not shown. */
export function workLine(s: Pick<SessionView, 'shared' | 'current_work'>): { text: string; shown: boolean } {
  if (!s.shared) return { text: '하는 일을 공유하지 않는다', shown: false };
  if (!s.current_work) return { text: '하는 일을 아직 올리지 않았다', shown: false };
  return { text: s.current_work, shown: true };
}

/**
 * The open first-screen item for a conflict this session is in on [path]. None when the conflict
 * was acknowledged: it still marks the file here, but it no longer waits for anyone.
 */
export function conflictItem(inbox: InboxItem[] | null, sessionId: string, path: string): InboxItem | null {
  return (
    inbox?.find((i) => {
      if (i.kind !== 'notice' || i.detail?.path !== path) return false;
      const sessions = i.detail?.sessions;
      return Array.isArray(sessions) && sessions.some((s) => typeof s === 'object' && s !== null && (s as { session_id?: unknown }).session_id === sessionId);
    }) ?? null
  );
}
