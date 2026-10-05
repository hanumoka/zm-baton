import type { RunEvent } from './api';

export type DisplayState =
  | { state: 'waiting' }
  | { state: 'running'; sessionId?: string }
  | { state: 'ended'; endReason: string };

/**
 * The run state shown on screen, computed from the run's events in seq order and never
 * read from a stored column (contract v1). The first valid "ended" wins, as on the server.
 */
export function displayState(events: readonly RunEvent[]): DisplayState {
  let s: DisplayState = { state: 'waiting' };
  for (const e of [...events].sort((a, b) => a.seq - b.seq)) {
    if (e.kind !== 'lifecycle' || s.state === 'ended') continue;
    const { phase, end_reason: endReason, session_id: sessionId } = e.payload;
    if (phase === 'started') {
      s = { state: 'running', sessionId: typeof sessionId === 'string' ? sessionId : undefined };
    } else if (phase === 'ended' && typeof endReason === 'string') {
      s = { state: 'ended', endReason };
    }
  }
  return s;
}

export function stateLabel(s: DisplayState): string {
  switch (s.state) {
    case 'waiting':
      return '시작 전';
    case 'running':
      return '실행 중';
    case 'ended':
      return `끝남(${s.endReason})`;
  }
}

const text = (v: unknown, max = 300) => (typeof v === 'string' ? v.slice(0, max) : '');

/** One line per event. Payloads come from executor output, so they are only ever shown as text. */
export function summary(e: RunEvent): string {
  const p = e.payload;
  switch (e.kind) {
    case 'lifecycle':
      return p.phase === 'ended' ? `종료: ${text(p.end_reason)}` : '시작';
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

/** Appends newly polled events, keeping seq order and dropping any already held. */
export function mergeEvents(held: readonly RunEvent[], polled: readonly RunEvent[]): RunEvent[] {
  const seen = new Set(held.map((e) => e.event_id));
  return [...held, ...polled.filter((e) => !seen.has(e.event_id))].sort((a, b) => a.seq - b.seq);
}
