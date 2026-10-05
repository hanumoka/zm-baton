import type { Run, RunEvent } from './api';

/**
 * Report status (contract v1 "보고 상태"). The run's lifecycle state and end reason come
 * from the server, which decided them; this only adds what the events say on top.
 *
 * Two contract rules are not judged in this compat screen, and it says so instead of guessing:
 * - 입력 필요 needs to know whether a question is still open; the question path is not built.
 * - 응답 없음 within the first response window is measured from the claim, and the claim
 *   time is not stored (data model "runs"), so a claimed run without events is shown as
 *   waiting for its first event.
 */
export type ReportStatus =
  | 'claim_pending' // not claimed yet, before the contract's table applies
  | 'first_event_pending' // claimed, no event yet; the first response window is not judged
  | 'working' // 작업 중
  | 'done' // 완료
  | 'error' // 오류
  | 'no_response' // 응답 없음, after a long silence
  | 'cancelled' // outside the contract's table, shown with the lifecycle
  | 'replaced'; // outside the contract's table, shown with the lifecycle

/** From the contract: events at least every 10 minutes. */
export const SILENCE_MS = 10 * 60_000;

/** The run's last event in seq order, of any kind (contract: the error is the last event). */
function lastEvent(events: readonly RunEvent[]): RunEvent | undefined {
  let last: RunEvent | undefined;
  for (const e of events) if (!last || e.seq > last.seq) last = e;
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
  if (lastEvent(events)?.kind === 'error') return 'error';
  // last_event_at comes from the server, so "no event yet" does not depend on whether this
  // screen has fetched the events already.
  if (run.last_event_at === null) return 'first_event_pending';
  return now - Date.parse(run.last_event_at) > SILENCE_MS ? 'no_response' : 'working';
}

export function statusLabel(s: ReportStatus): string {
  switch (s) {
    case 'claim_pending':
      return '청구 대기';
    case 'first_event_pending':
      return '첫 사건 대기';
    case 'working':
      return '작업 중';
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

/** Fetches events with seq > after, at most limit of them, in seq order. */
export type FetchEvents = (after: number, limit: number) => Promise<RunEvent[]>;

export const PAGE = 200;
const MAX_FORWARD_PAGES = 10;
const MAX_GAPS = 5;

/** Seqs missing below the largest held seq, lowest first, at most max of them. */
export function missingSeqs(events: readonly RunEvent[], max = MAX_GAPS): number[] {
  const seqs = new Set(events.map((e) => e.seq));
  const top = events.reduce((m, e) => Math.max(m, e.seq), 0);
  const out: number[] = [];
  for (let s = 1; s < top && out.length < max; s++) if (!seqs.has(s)) out.push(s);
  return out;
}

/**
 * One poll: moves forward after the largest held seq, then asks once for each of the first
 * few missing seqs. The server may store a smaller seq after a larger one, so the gaps are
 * asked again on every poll until they fill; the forward cursor never waits for them.
 */
export async function pollEvents(fetch: FetchEvents, held: readonly RunEvent[]): Promise<RunEvent[]> {
  const got: RunEvent[] = [];
  let after = held.reduce((m, e) => Math.max(m, e.seq), 0);
  for (let page = 0; page < MAX_FORWARD_PAGES; page++) {
    const polled = await fetch(after, PAGE);
    got.push(...polled);
    if (polled.length < PAGE) break;
    after = polled[polled.length - 1].seq;
  }
  for (const seq of missingSeqs([...held, ...got])) {
    const polled = await fetch(seq - 1, 1);
    if (polled[0]?.seq === seq) got.push(polled[0]);
  }
  return got;
}
