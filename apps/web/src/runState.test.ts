import { describe, expect, it } from 'vitest';
import type { RunEvent } from './api';
import { displayState, mergeEvents, summary } from './runState';

const ev = (seq: number, kind: string, payload: Record<string, unknown> = {}): RunEvent => ({
  event_id: `evt_${seq}`,
  generation: 1,
  seq,
  kind,
  payload,
  occurred_at: null,
  received_at: '2026-10-05T00:00:00Z',
});

describe('displayState', () => {
  it('is waiting before any lifecycle event', () => {
    expect(displayState([ev(1, 'answer', { text: 'x' })])).toEqual({ state: 'waiting' });
  });

  it('is running after started, with the session id', () => {
    expect(displayState([ev(1, 'lifecycle', { phase: 'started', session_id: 's1' })])).toEqual({
      state: 'running',
      sessionId: 's1',
    });
  });

  it('uses seq order, not arrival order', () => {
    const events = [
      ev(3, 'lifecycle', { phase: 'ended', end_reason: 'submitted' }),
      ev(1, 'lifecycle', { phase: 'started' }),
    ];
    expect(displayState(events)).toEqual({ state: 'ended', endReason: 'submitted' });
  });

  it('keeps the first ended, as the server does', () => {
    const events = [
      ev(1, 'lifecycle', { phase: 'ended', end_reason: 'lost' }),
      ev(2, 'lifecycle', { phase: 'ended', end_reason: 'submitted' }),
    ];
    expect(displayState(events)).toEqual({ state: 'ended', endReason: 'lost' });
  });

  it('ignores an ended event without a string end reason', () => {
    const events = [ev(1, 'lifecycle', { phase: 'started' }), ev(2, 'lifecycle', { phase: 'ended' })];
    expect(displayState(events).state).toBe('running');
  });
});

describe('summary', () => {
  it('shows only text, cut to 300 characters', () => {
    expect(summary(ev(1, 'answer', { text: 'a'.repeat(400) }))).toHaveLength(300);
    expect(summary(ev(1, 'answer', { text: { html: '<b>' } }))).toBe('');
  });

  it('labels tool calls and failed results', () => {
    expect(summary(ev(1, 'action', { name: 'Read' }))).toBe('도구 호출: Read');
    expect(summary(ev(1, 'action_result', { text: 'no', is_error: true }))).toBe('도구 결과(오류): no');
  });
});

describe('mergeEvents', () => {
  it('appends new events in seq order and drops repeats', () => {
    const merged = mergeEvents([ev(1, 'answer'), ev(2, 'answer')], [ev(2, 'answer'), ev(4, 'answer'), ev(3, 'answer')]);
    expect(merged.map((e) => e.seq)).toEqual([1, 2, 3, 4]);
  });
});
