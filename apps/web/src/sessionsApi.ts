// Server API for the first screen ("나를 기다리는 것") and the session list
// (contract v1 "직접 연 AI 세션을 서버에 등록한다", "첫 화면"). Same origin as the page.

export type SessionView = {
  id: string;
  executor: string;
  executor_version: string | null;
  device_name: string;
  repository: { remote?: string | null; prefix?: string | null; branch?: string | null } | null;
  repo_key: string | null;
  /** Null when the session does not share what it works on. */
  current_work: string | null;
  shared: boolean;
  current_work_at: string | null;
  state: 'live' | 'ended' | 'deregistered' | 'expired';
  created_at: string;
  last_seen_at: string;
  touched_files: string[];
  conflicted_files: string[];
};

export type InboxItem = {
  id: string;
  bucket: 1 | 2 | 3 | 4 | 5;
  kind: string;
  source_kind: string;
  source_id: string;
  summary: string;
  detail: Record<string, unknown> | null;
  wait_kind: string | null;
  repeat_count: number;
  waiting_since: string;
  notify: 'immediate' | 'digest';
};

export type NoticeStatus = 'acknowledged' | 'not_a_conflict' | 'resolved';

async function call<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const res = await fetch(path, {
    method,
    signal,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${method} ${path}: HTTP ${res.status}`);
  return (await res.json()) as T;
}

export const sessionsApi = {
  inbox: (signal?: AbortSignal) => call<InboxItem[]>('GET', '/api/inbox', undefined, signal),
  sessions: (includeEnded: boolean, signal?: AbortSignal) =>
    call<SessionView[]>('GET', `/api/sessions?include_ended=${includeEnded}&limit=100`, undefined, signal),
  judgeNotice: (id: string, status: NoticeStatus, note?: string) =>
    call<{ id: string; status: NoticeStatus }>('POST', `/api/notices/${encodeURIComponent(id)}/status`, { status, note }),
};
