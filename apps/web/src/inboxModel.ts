import type { InboxItem, SessionView } from './sessionsApi';

/**
 * The five buckets of the first screen, in the contract's order (contract v1 "항목과 순서").
 * [raised] is whether the server has a path that puts items there yet: relayed permission requests
 * (1), questions and waiting sessions (2) and file conflicts between sessions (3). The others have
 * kinds in the contract and none in the server, so the screen says so instead of showing an empty box.
 */
export const BUCKETS: { bucket: InboxItem['bucket']; label: string; raised: boolean }[] = [
  { bucket: 1, label: '권한 요청', raised: true },
  { bucket: 2, label: '멈춘 AI의 질문', raised: true },
  { bucket: 3, label: '내 작업과 겹친 충돌', raised: true },
  { bucket: 4, label: '위험 목록 검토', raised: false },
  { bucket: 5, label: '표본 검토와 나머지 알림', raised: false },
];

/** Waiting for me now: open, or a relay's answer on its way. A relay that ended only reports how. */
const stillWaiting = (i: InboxItem) => i.state === undefined || i.state === 'open' || i.state === 'answered';

/**
 * Items per bucket. Within a bucket, whoever waited longest comes first, and relays that already
 * ended (shown for a while so the person sees how) come after the ones still waiting.
 */
export function groupByBucket(items: InboxItem[]): Map<number, InboxItem[]> {
  const groups = new Map<number, InboxItem[]>(BUCKETS.map((b) => [b.bucket, []]));
  for (const item of items) groups.get(item.bucket)?.push(item);
  for (const list of groups.values()) {
    list.sort(
      (a, b) => Number(!stillWaiting(a)) - Number(!stillWaiting(b)) || a.waiting_since.localeCompare(b.waiting_since) || a.id.localeCompare(b.id),
    );
  }
  return groups;
}

/**
 * What the list shows. A session whose permission request is being relayed also waits in its
 * terminal, and the notification hook reports that wait too; while the relay is on, that wait
 * would say the same thing twice, so it stays out of the list.
 */
export function visibleItems(items: InboxItem[]): InboxItem[] {
  const relaying = new Set(
    items.filter((i) => i.kind === 'permission_request' && i.relay_deadline && stillWaiting(i)).map((i) => i.source_id),
  );
  return items.filter((i) => !(i.kind === 'session_waiting' && i.wait_kind === 'permission_prompt' && relaying.has(i.source_id)));
}

/** What still waits for me: the sidebar's count and what a browser notification may announce. */
export const waitingItems = (items: InboxItem[]) => visibleItems(items).filter((i) => i.state === undefined || i.state === 'open');

/**
 * Items to raise as a browser notification now: immediate items not seen in the previous poll.
 * The first poll after loading the page only records what is there (seen is null).
 */
export function newImmediate(seen: Set<string> | null, items: InboxItem[]): InboxItem[] {
  if (seen === null) return [];
  return items.filter((i) => i.notify === 'immediate' && !seen.has(i.id));
}

/** "방금", "3분 전", "2시간 전", "3일 전": how long ago something happened. */
export function since(iso: string, now: number): string {
  const minutes = Math.floor((now - Date.parse(iso)) / 60_000);
  if (Number.isNaN(minutes) || minutes < 1) return '방금';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}

/** "방금", "3분째", "2시간째", "3일째": how long something has been waiting until now. */
export function waited(iso: string, now: number): string {
  const minutes = Math.floor((now - Date.parse(iso)) / 60_000);
  if (Number.isNaN(minutes) || minutes < 1) return '방금';
  if (minutes < 60) return `${minutes}분째`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간째`;
  return `${Math.floor(hours / 24)}일째`;
}

const STATE_LABELS: Record<SessionView['state'], string> = {
  live: '살아 있음',
  ended: '끝남',
  deregistered: '등록 끔',
  expired: '만료',
};

export function stateLabel(state: SessionView['state']): string {
  return STATE_LABELS[state] ?? state;
}

/** `hanumoka/zm-docs` from a remote, or the short repository key when there is no remote. */
export function repoName(s: Pick<SessionView, 'repository' | 'repo_key'>): string {
  const remote = s.repository?.remote ?? '';
  const m = /[:/]([^/:]+\/[^/]+?)(?:\.git)?$/.exec(remote);
  if (m) return m[1];
  if (s.repo_key) return s.repo_key.slice(0, 16);
  return '저장소 없음';
}

/** `ses_01J9…7f3c`: enough to tell sessions apart on one screen. */
export function shortId(id: string): string {
  return id.length > 12 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id;
}

const EXECUTOR_LABELS: Record<string, string> = { claude_code: 'Claude Code', codex: 'Codex' };

/** The executor's product name; an executor the screen does not know yet shows as the server names it. */
export function executorLabel(executor: string | null | undefined): string {
  if (!executor) return '실행기 모름';
  return EXECUTOR_LABELS[executor] ?? executor;
}

/**
 * The session screen's address for one session. A session that is not live shows there only with
 * ended sessions shown, so the address turns that on.
 */
export function sessionHref(id: string, live: boolean): string {
  const p = new URLSearchParams();
  if (!live) p.set('ended', '1');
  p.set('item', id);
  return `#sessions?${p.toString()}`;
}

/** The first screen's address for one item. */
export const inboxHref = (id: string) => `#inbox?item=${encodeURIComponent(id)}`;
