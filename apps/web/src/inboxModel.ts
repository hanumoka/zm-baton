import type { InboxItem, SessionView } from './sessionsApi';

/** The five buckets of the first screen, in the contract's order (contract v1 "항목과 순서"). */
export const BUCKETS: { bucket: InboxItem['bucket']; label: string; empty: string }[] = [
  { bucket: 1, label: '권한 요청', empty: '권한 요청 중계는 아직 없다' },
  { bucket: 2, label: '멈춘 AI의 질문', empty: '없음' },
  { bucket: 3, label: '내 작업과 겹친 충돌', empty: '없음' },
  { bucket: 4, label: '위험 목록 검토', empty: '위험 경로 검토는 아직 없다' },
  { bucket: 5, label: '표본 검토 · 나머지 알림', empty: '없음' },
];

/** Items per bucket. Within a bucket, whoever waited longest comes first; the server sends that order. */
export function groupByBucket(items: InboxItem[]): Map<number, InboxItem[]> {
  const groups = new Map<number, InboxItem[]>(BUCKETS.map((b) => [b.bucket, []]));
  for (const item of items) groups.get(item.bucket)?.push(item);
  for (const list of groups.values()) {
    list.sort((a, b) => a.waiting_since.localeCompare(b.waiting_since) || a.id.localeCompare(b.id));
  }
  return groups;
}

/**
 * Items to raise as a browser notification now: immediate items not seen in the previous poll.
 * The first poll after loading the page only records what is there (seen is null).
 */
export function newImmediate(seen: Set<string> | null, items: InboxItem[]): InboxItem[] {
  if (seen === null) return [];
  return items.filter((i) => i.notify === 'immediate' && !seen.has(i.id));
}

/** "방금", "3분 전", "2시간 전", "어제 이후": how long something has waited. */
export function since(iso: string, now: number): string {
  const minutes = Math.floor((now - Date.parse(iso)) / 60_000);
  if (Number.isNaN(minutes) || minutes < 1) return '방금';
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
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
