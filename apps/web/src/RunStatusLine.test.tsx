import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Run, RunEvent } from './api';
import { RunStatusLine } from './RunStatusLine';

const T0 = Date.parse('2026-10-05T00:00:00Z');

const run = (over: Partial<Run> = {}): Run => ({
  id: 'run_a',
  work_item_id: 'wrk_a',
  state: 'running',
  end_reason: null,
  executor: 'claude_code',
  generation: 1,
  device_id: 'dev_a',
  late_reports: false,
  created_at: new Date(T0).toISOString(),
  last_event_at: new Date(T0).toISOString(),
  ...over,
});

const question: RunEvent = {
  event_id: 'evt_q',
  generation: 1,
  seq: 1,
  kind: 'question',
  payload: { text: '?' },
  occurred_at: null,
  received_at: new Date(T0).toISOString(),
};

describe('RunStatusLine', () => {
  it('says next to "작업 중" that an open question is not judged', () => {
    const html = renderToStaticMarkup(<RunStatusLine run={run()} events={[question]} now={T0} />);
    expect(html).toContain('작업 중');
    expect(html).toContain('<li class="relevant">입력 필요: 판정하지 않음');
    expect(html).toContain('첫 응답 기한: 판정하지 않음');
  });

  it('says the first response window is not judged while waiting for the first event', () => {
    const html = renderToStaticMarkup(<RunStatusLine run={run({ state: 'claimed', last_event_at: null })} events={[]} now={T0} />);
    expect(html).toContain('첫 사건 대기');
    expect(html).toContain('<li class="relevant">첫 응답 기한: 판정하지 않음');
  });

  it('shows no undecided rules once the run has ended', () => {
    const html = renderToStaticMarkup(
      <RunStatusLine run={run({ state: 'ended', end_reason: 'submitted' })} events={[question]} now={T0} />,
    );
    expect(html).toContain('완료');
    expect(html).not.toContain('판정하지 않음');
  });
});
