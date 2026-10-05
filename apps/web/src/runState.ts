import type { Run, RunEvent } from './api';

/**
 * Report status (contract v1 "보고 상태"). The run's lifecycle state and end reason come
 * from the server, which decided them; this only adds what the events say on top.
 */
export type ReportStatus =
  | 'claim_pending' // not claimed yet (before the contract's table applies)
  | 'working' // 작업 중
  | 'needs_input' // 입력 필요
  | 'done' // 완료
  | 'error' // 오류
  | 'no_response' // 응답 없음
  | 'cancelled'
  | 'replaced';

/** Defaults from the contract: first response within 60 s, then events at least every 10 min. */
export const FIRST_RESPONSE_MS = 60_000;
export const SILENCE_MS = 10 * 60_000;

/**
 * The run's last activity event, skipping usage reports so a trailing rate-limit or
 * cost line does not hide an error or a question.
 */
function lastActivity(events: readonly RunEvent[]): RunEvent | undefined {
  let last: RunEvent | undefined;
  for (const e of events) {
    if (e.kind === 'usage') continue;
    if (!last || e.seq > last.seq) last = e;
  }
  return last;
}

export function reportStatus(run: Run, events: readonly RunEvent[], now: number): ReportStatus {
  if (run.state === 'requested') return 'claim_pending';
  if (run.state === 'ended') {
    switch (run.end_reason) {
      case 'submitted':
        return 'done';
      case 'cancelled':
        return 'cancelled';
      case 'replaced':
        return 'replaced';
      default:
        return 'error'; // failed, lost
    }
  }
  const last = lastActivity(events);
  if (last?.kind === 'question') return 'needs_input';
  if (last?.kind === 'error') return 'error';
  if (events.length === 0) {
    // The claim time is not stored, so the first-response window starts at the run's creation.
    return now - Date.parse(run.created_at) > FIRST_RESPONSE_MS ? 'no_response' : 'working';
  }
  const lastAt = Date.parse(run.last_event_at ?? events.at(-1)!.received_at);
  return now - lastAt > SILENCE_MS ? 'no_response' : 'working';
}

export function statusLabel(s: ReportStatus): string {
  switch (s) {
    case 'claim_pending':
      return '청구 대기';
    case 'working':
      return '작업 중';
    case 'needs_input':
      return '입력 필요';
    case 'done':
      return '완료';
    case 'error':
      return '오류';
    case 'no_response':
      return '응답 없음';
    case 'cancelled':
      return '취소됨';
    case 'replaced':
      return '다른 실행으로 넘김';
  }
}

const text = (v: unknown, max = 300) => (typeof v === 'string' ? v.slice(0, max) : '');

/** One line per event. Payloads come from executor output, so they are only ever shown as text. */
export function summary(e: RunEvent): string {
  const p = e.payload;
  switch (e.kind) {
    case 'lifecycle':
      return p.phase === 'ended' ? `종료 보고: ${text(p.end_reason)}` : '시작';
    case 'action':
      return `도구 호출: ${text(p.name)}`;
    case 'action_result':
      return `도구 결과${p.is_error === true ? '(오류)' : ''}: ${text(p.text)}`;
    case 'usage':
      return `사용량(${text(p.source)})`;
    default:
      return text(p.text);
  }
}

/** Events held for one run. Responses for any other run are ignored. */
export type HeldEvents = { runId: string | undefined; events: RunEvent[] };

/** Merges a polled page into what is held for runId, keeping seq order and dropping repeats. */
export function applyPolled(held: HeldEvents, runId: string, polled: readonly RunEvent[]): HeldEvents {
  if (held.runId !== runId || polled.length === 0) return held;
  const seen = new Set(held.events.map((e) => e.event_id));
  const events = [...held.events, ...polled.filter((e) => !seen.has(e.event_id))].sort((a, b) => a.seq - b.seq);
  return { runId, events };
}

/**
 * The poll cursor: the largest k such that seqs 1..k are all held. The server may store a
 * smaller seq after a larger one, so polling after the largest seq held could miss it.
 */
export function contiguousPrefix(events: readonly RunEvent[]): number {
  const seqs = new Set(events.map((e) => e.seq));
  let k = 0;
  while (seqs.has(k + 1)) k++;
  return k;
}
