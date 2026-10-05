import { describe, expect, it } from 'vitest';
import type { Run, RunEvent } from './api';
import { applyPolled, gaps, PAGE, pollEvents, reportStatus, SILENCE_MS, summary, type HeldEvents } from './runState';

const T0 = Date.parse('2026-10-05T00:00:00Z');
const iso = (ms: number) => new Date(ms).toISOString();

const ev = (seq: number, kind = 'answer', payload: Record<string, unknown> = {}): RunEvent => ({
  event_id: `evt_${seq}`,
  generation: 1,
  seq,
  kind,
  payload,
  occurred_at: null,
  received_at: iso(T0),
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
  last_event_at: iso(T0),
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
    expect(reportStatus(run({ state: 'ended', end_reason: 'failed' }), [], T0)).toBe('error');
    expect(reportStatus(run({ state: 'ended', end_reason: 'cancelled' }), [], T0)).toBe('cancelled');
    expect(reportStatus(run({ state: 'ended', end_reason: 'replaced' }), [], T0)).toBe('replaced');
  });

  it('is claim pending before a claim', () => {
    expect(reportStatus(run({ state: 'requested', last_event_at: null }), [], T0)).toBe('claim_pending');
  });

  it('is an error only when the last event, of any kind, is an error', () => {
    expect(reportStatus(run(), [ev(1, 'lifecycle', { phase: 'started' }), ev(2, 'error', { text: 'x' })], T0)).toBe('error');
    // A usage report after the error is the last event: not an error by the contract's rule.
    expect(reportStatus(run(), [ev(1, 'error', { text: 'x' }), ev(2, 'usage')], T0)).toBe('working');
  });

  it('does not judge an open question: the question path is not built yet', () => {
    expect(reportStatus(run(), [ev(1, 'question', { text: '?' })], T0)).toBe('working');
    expect(reportStatus(run(), [ev(1, 'question', { text: '?' }), ev(2, 'action', { name: 'Read' })], T0)).toBe('working');
  });

  it('waits for the first event instead of measuring the first response from creation', () => {
    // Created two minutes before it was claimed, no event yet: not "no response".
    const claimedLate = run({ state: 'claimed', last_event_at: null, created_at: iso(T0) });
    expect(reportStatus(claimedLate, [], T0 + 120_000)).toBe('first_event_pending');
    // The server already has events this screen has not fetched yet: still working.
    expect(reportStatus(run({ last_event_at: iso(T0) }), [], T0 + 1000)).toBe('working');
  });

  it('has no response after a long silence since the last event the server stored', () => {
    const r = run({ last_event_at: iso(T0) });
    expect(reportStatus(r, [ev(1)], T0 + SILENCE_MS - 1)).toBe('working');
    expect(reportStatus(r, [ev(1)], T0 + SILENCE_MS + 1)).toBe('no_response');
  });
});

describe('applyPolled', () => {
  it('ignores a late answer for a run that is no longer selected', () => {
    // Run A was selected, then B; A's slow response arrives after B's.
    let held: HeldEvents = { runId: 'run_b', events: [] };
    held = applyPolled(held, 'run_b', [ev(1)]);
    held = applyPolled(held, 'run_a', [ev(1), ev(100, 'lifecycle', { phase: 'ended', end_reason: 'submitted' })]);
    expect(held).toEqual({ runId: 'run_b', events: [ev(1)] });
  });

  it('appends new events in seq order and drops repeats, also within one poll', () => {
    const held = applyPolled({ runId: 'run_a', events: [ev(1), ev(2)] }, 'run_a', [ev(2), ev(4), ev(3), ev(4)]);
    expect(held.events.map((e) => e.seq)).toEqual([1, 2, 3, 4]);
  });
});

/** A server holding events by seq, answering like GET /api/runs/{id}/events. */
function fakeServer(seqs: number[]) {
  const stored = new Set(seqs);
  const fetch = async (after: number, limit: number) =>
    [...stored]
      .filter((s) => s > after)
      .sort((a, b) => a - b)
      .slice(0, limit)
      .map((s) => ev(s));
  return { stored, fetch };
}

async function pollTimes(
  fetch: (after: number, limit: number) => Promise<RunEvent[]>,
  times: number,
  start: HeldEvents = { runId: 'run_a', events: [] },
) {
  let held = start;
  for (let i = 0; i < times; i++) held = applyPolled(held, 'run_a', await pollEvents(fetch, held.events, i));
  return held;
}

describe('pollEvents', () => {
  it('moves past more than ten pages even while seq 1 is missing', async () => {
    const server = fakeServer(Array.from({ length: 2100 }, (_, i) => i + 2)); // 2..2101
    const held = await pollTimes(server.fetch, 2);
    expect(held.events).toHaveLength(2100);
    expect(held.events.at(-1)?.seq).toBe(2101);
    expect(gaps(held.events)).toEqual([{ first: 1, last: 1 }]);
  });

  it('fills a missing seq that the server stores later, behind the forward cursor', async () => {
    const server = fakeServer([1, 3, 4]);
    let held = await pollTimes(server.fetch, 1);
    expect(held.events.map((e) => e.seq)).toEqual([1, 3, 4]);
    server.stored.add(2); // stored after 3 and 4 were read
    held = applyPolled(held, 'run_a', await pollEvents(server.fetch, held.events));
    expect(held.events.map((e) => e.seq)).toEqual([1, 2, 3, 4]);
  });

  it('keeps a late seq behind gaps that stay open', async () => {
    // 1..5 never arrive; 6 is stored after 7 and 8 were read.
    const server = fakeServer([7, 8]);
    let held = await pollTimes(server.fetch, 1);
    server.stored.add(6);
    held = await pollTimes(server.fetch, 1, held);
    expect(held.events.map((e) => e.seq)).toEqual([6, 7, 8]);
    expect(gaps(held.events)).toEqual([{ first: 1, last: 5 }]);
  });

  it('turns through more than five gaps, so a late seq in any of them arrives', async () => {
    const evens = Array.from({ length: 15 }, (_, i) => (i + 1) * 2); // 2..30: 15 one-seq gaps
    const server = fakeServer(evens);
    let held = await pollTimes(server.fetch, 1);
    server.stored.add(29); // the fifteenth gap
    held = await pollTimes(server.fetch, 3, held);
    expect(held.events.some((e) => e.seq === 29)).toBe(true);
  });

  it('finds gaps without walking every seq up to a huge one', () => {
    expect(gaps([ev(1), ev(1e12)])).toEqual([{ first: 2, last: 1e12 - 1 }]);
    expect(gaps([ev(3), ev(1), ev(3)])).toEqual([{ first: 2, last: 2 }]);
  });

  it('asks at most ten forward pages per poll', async () => {
    const calls: number[] = [];
    const server = fakeServer(Array.from({ length: PAGE * 12 }, (_, i) => i + 1));
    const fetch = (after: number, limit: number) => {
      calls.push(limit);
      return server.fetch(after, limit);
    };
    const got = await pollEvents(fetch, []);
    expect(got).toHaveLength(PAGE * 10);
    expect(calls.filter((l) => l === PAGE)).toHaveLength(10);
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
