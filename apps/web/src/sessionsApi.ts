// Server API for the first screen ("나를 기다리는 것") and the session list
// (contract v1 "직접 연 AI 세션을 서버에 등록한다", "첫 화면"). Same origin as the page.

import { ApiError } from './workApi';

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
  /** A review that waited longer than the organisation's hours, raised to bucket 1; an older server leaves it out. */
  promoted_at?: string | null;
  // A relayed permission request or question (contract 「권한 요청과 선택형 질문을 첫 화면으로 중계한다」).
  // An older server leaves these out; every other kind is always open.
  state?: RelayState;
  /** Until when it can be answered here. Null: not relayed, so it is answered in the session's terminal. */
  relay_deadline?: string | null;
  /** Why it was not relayed or how it ended: secret_suspected, too_large, focus_mode, not_visible, deadline, session_ended, resumed, person. */
  relay_reason?: string | null;
  /** What the person pressed: { decision, reason } or { answers: { question: [labels] } }. */
  answer?: Record<string, unknown> | null;
  answered_at?: string | null;
  delivered_at?: string | null;
  closed_at?: string | null;
};

export type RelayState = 'open' | 'answered' | 'delivered' | 'moved_to_terminal' | 'undelivered' | 'closed';

/** A person's answer to a relay: a decision for a permission request, chosen labels per question for a question. */
export type RelayAnswer = { decision: 'allow' | 'deny'; reason?: string } | { answers: Record<string, string[]> };

/** The person's own settings: focus mode and whether the first screen counts as visible. */
export type MeView = {
  user_id: string;
  focus_on: boolean;
  focus_changed_at: string | null;
  first_screen_visible: boolean;
  relay_wait_seconds: number;
  /** Whether the server relays permission requests and questions (ZM_BATON_RELAY=on). A server setting; no screen turns it on. */
  relay_enabled?: boolean;
};

export type NoticeStatus = 'acknowledged' | 'not_a_conflict' | 'resolved';

async function call<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const res = await fetch(path, {
    method,
    signal,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    // The server's own refusals say why, for a person (ApiErrors); keep that reason.
    let reason = '';
    try {
      reason = ((await res.json()) as { message?: string }).message ?? '';
    } catch {
      // not JSON: the status says enough
    }
    throw new ApiError(reason ? `HTTP ${res.status}: ${reason}` : `${method} ${path}: HTTP ${res.status}`, res.status);
  }
  return (await res.json()) as T;
}

export const sessionsApi = {
  inbox: (signal?: AbortSignal) => call<InboxItem[]>('GET', '/api/inbox', undefined, signal),
  /** Newest signal first; [limit] 1 asks only whether any exists. */
  sessions: (includeEnded: boolean, signal?: AbortSignal, limit = 100) =>
    call<SessionView[]>('GET', `/api/sessions?include_ended=${includeEnded}&limit=${limit}`, undefined, signal),
  judgeNotice: (id: string, status: NoticeStatus, note?: string) =>
    call<{ id: string; status: NoticeStatus }>('POST', `/api/notices/${encodeURIComponent(id)}/status`, { status, note }),
  /** A person's answer to a relayed request; a late one is refused with HTTP 409 and the reason. */
  answerRelay: (id: string, answer: RelayAnswer) => call<{ id: string; state: RelayState }>('POST', `/api/inbox/${encodeURIComponent(id)}/answer`, answer),
  /** Stops the relay so the session's hook gives no decision and the terminal asks. */
  relayToTerminal: (id: string) => call<{ id: string; state: RelayState }>('POST', `/api/inbox/${encodeURIComponent(id)}/to-terminal`),
  me: (signal?: AbortSignal) => call<MeView>('GET', '/api/me', undefined, signal),
  setFocus: (on: boolean) => call<MeView>('PUT', '/api/me/focus', { on }),
  /** The first screen says whether its tab is visible; the server counts it for 30 seconds. */
  setFirstScreen: (visible: boolean) => call<MeView>('PUT', '/api/me/first-screen', { visible }),
};
