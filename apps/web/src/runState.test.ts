import { describe, expect, it } from 'vitest';
import type { Run, RunEvent } from './api';
import { applyPolled, contiguousPrefix, FIRST_RESPONSE_MS, reportStatus, SILENCE_MS, summary, type HeldEvents } from './runState';

const T0 = Date.parse('2026-10-05T00:00:00Z');
const iso = (ms: number) => new Date(ms).toISOString();

const ev = (seq: number, kind: string, payload: Record<string, unknown> = {}, at = T0): RunEvent => ({
  event_id: `evt_${seq}`,
  generation: 1,
  seq,
  kind,
  payload,
  occurred_at: null,
  received_at: iso(at),
});

const run = (over: Partial<Run> = {}): Run => ({
  id: 'run_a',
  work_item_id: 'wrk_a',
  state: 'running',
  end_reason: null,
  executor: 'claude_code',
  generation: 1,
  device_id: 'dev_a',
  late_reports: false,
  created_at: iso(T0),
  last_event_at: null,
  ...over,
});

describe('reportStatus', () => {
  it('takes the end reason the server applied, whatever the ended events say', () => {
    // The server applied "lost" first; a later batch carried a smaller-seq "submitted".
    const events = [
      ev(9, 'lifecycle', { phase: 'ended', end_reason: 'submitted' }),
      ev(10, 'lifecycle', { phase: 'ended', end_reason: 'lost' }),
    ];
    expect(reportStatus(run({ state: 'ended', end_reason: 'lost' }), events, T0)).toBe('error');
    expect(reportStatus(run({ state: 'ended', end_reason: 'submitted' }), [], T0)).toBe('done');
    expect(reportStatus(run({ state: 'ended', end_reason: 'cancelled' }), [], T0)).toBe('cancelled');
    expect(reportStatus(run({ state: 'ended', end_reason: 'replaced' }), [], T0)).toBe('replaced');
    expect(reportStatus(run({ state: 'ended', end_reason: 'failed' }), [], T0)).toBe('error');
  });

  it('is claim pending before a claim', () => {
    expect(reportStatus(run({ state: 'requested' }), [], T0 + 2 * FIRST_RESPONSE_MS)).toBe('claim_pending');
  });

  it('needs input when the last activity is a question, even with usage after it', () => {
    const events = [ev(1, 'lifecycle', { phase: 'started' }), ev(2, 'question', { text: '?' }), ev(3, 'usage')];
    expect(reportStatus(run(), events, T0)).toBe('needs_input');
  });

  it('is an error when the last activity is an error', () => {
    expect(reportStatus(run(), [ev(1, 'lifecycle', { phase: 'started' }), ev(2, 'error', { text: 'x' })], T0)).toBe('error');
  });

  it('has no response without a first event in time, or after a long silence', () => {
    expect(reportStatus(run(), [], T0 + FIRST_RESPONSE_MS - 1)).toBe('working');
    expect(reportStatus(run(), [], T0 + FIRST_RESPONSE_MS + 1)).toBe('no_response');
    const r = run({ last_event_at: iso(T0) });
    const events = [ev(1, 'answer', { text: 'hi' })];
    expect(reportStatus(r, events, T0 + SILENCE_MS - 1)).toBe('working');
    expect(reportStatus(r, events, T0 + SILENCE_MS + 1)).toBe('no_response');
  });
});

describe('applyPolled', () => {
  it('ignores a late answer for a run that is no longer selected', () => {
    // Run A was selected, then B; A's slow response arrives after B's.
    let held: HeldEvents = { runId: 'run_b', events: [] };
    held = applyPolled(held, 'run_b', [ev(1, 'answer')]);
    held = applyPolled(held, 'run_a', [ev(1, 'answer'), ev(100, 'lifecycle', { phase: 'ended', end_reason: 'submitted' })]);
    expect(held).toEqual({ runId: 'run_b', events: [ev(1, 'answer')] });
  });

  it('appends new events in seq order and drops repeats', () => {
    const held = applyPolled({ runId: 'run_a', events: [ev(1, 'answer'), ev(2, 'answer')] }, 'run_a', [
      ev(2, 'answer'),
      ev(4, 'answer'),
      ev(3, 'answer'),
    ]);
    expect(held.events.map((e) => e.seq)).toEqual([1, 2, 3, 4]);
  });
});

describe('contiguousPrefix', () => {
  it('stops before the first missing seq, so a later-stored smaller seq is still polled', () => {
    expect(contiguousPrefix([])).toBe(0);
    expect(contiguousPrefix([ev(1, 'answer'), ev(3, 'answer')])).toBe(1);
    // Seq 2 is stored after 1 and 3 were read: polling after 1 brings it in.
    const held = applyPolled({ runId: 'run_a', events: [ev(1, 'answer'), ev(3, 'answer')] }, 'run_a', [
      ev(2, 'answer'),
      ev(3, 'answer'),
    ]);
    expect(held.events.map((e) => e.seq)).toEqual([1, 2, 3]);
    expect(contiguousPrefix(held.events)).toBe(3);
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
