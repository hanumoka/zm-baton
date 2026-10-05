import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { api, type Run, type RunEvent, type WorkItem } from './api';
import { displayState, mergeEvents, stateLabel, summary } from './runState';

/** Polls fn every ms while active; errors are reported, not thrown. */
function usePoll(fn: () => Promise<void>, ms: number, deps: unknown[], onError: (e: unknown) => void) {
  useEffect(() => {
    let stopped = false;
    const tick = () => fn().catch((e) => !stopped && onError(e));
    tick();
    const id = setInterval(tick, ms);
    return () => {
      stopped = true;
      clearInterval(id);
    };
  }, deps); // the caller lists what fn reads
}

export function App() {
  const [items, setItems] = useState<WorkItem[]>([]);
  const [workId, setWorkId] = useState<string>();
  const [runs, setRuns] = useState<Run[]>([]);
  const [runId, setRunId] = useState<string>();
  const [events, setEvents] = useState<RunEvent[]>([]);
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string>();
  const report = (e: unknown) => setError(e instanceof Error ? e.message : String(e));

  usePoll(async () => setItems(await api.workItems()), 2000, [], report);

  usePoll(
    async () => {
      if (!workId) return;
      const list = await api.runs(workId);
      setRuns(list);
      setRunId((current) => current ?? list.at(-1)?.id);
    },
    2000,
    [workId],
    report,
  );

  useEffect(() => setEvents([]), [runId]);
  usePoll(
    async () => {
      if (!runId) return;
      const after = events.at(-1)?.seq ?? 0;
      const polled = await api.events(runId, after);
      if (polled.length > 0) setEvents((held) => mergeEvents(held, polled));
    },
    1000,
    [runId, events.at(-1)?.seq],
    report,
  );

  const state = useMemo(() => displayState(events), [events]);

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    try {
      const work = await api.createWorkItem(title.trim());
      await api.createRun(work.id);
      setTitle('');
      setWorkId(work.id);
      setRunId(undefined);
      setItems(await api.workItems());
    } catch (err) {
      report(err);
    }
  }

  return (
    <main>
      <header>
        <h1>zm-baton</h1>
        <p>1단계 설치·호환 시험 화면. 실행 상태는 사건에서 계산한다.</p>
      </header>
      {error && (
        <p role="alert" className="error" onClick={() => setError(undefined)}>
          {error}
        </p>
      )}
      <div className="columns">
        <section aria-label="작업">
          <form onSubmit={create}>
            <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="새 작업 제목" aria-label="새 작업 제목" />
            <button type="submit">작업과 실행 시도 만들기</button>
          </form>
          <ul className="list">
            {items.map((w) => (
              <li key={w.id}>
                <button
                  className={w.id === workId ? 'selected' : undefined}
                  onClick={() => {
                    setWorkId(w.id);
                    setRunId(undefined);
                  }}
                >
                  <span>{w.title}</span>
                  <small>
                    {w.stage}
                    {w.wait_reasons.length > 0 && ` · ${w.wait_reasons.join(', ')}`}
                  </small>
                </button>
              </li>
            ))}
          </ul>
        </section>
        <section aria-label="실행 시도">
          {workId ? (
            <>
              <ul className="runs">
                {runs.map((r) => (
                  <li key={r.id}>
                    <button className={r.id === runId ? 'selected' : undefined} onClick={() => setRunId(r.id)}>
                      {r.executor} · 세대 {r.generation ?? '-'}
                      {r.late_reports && ' · 늦은 보고 있음'}
                    </button>
                  </li>
                ))}
              </ul>
              {runId && (
                <>
                  <p>
                    상태: <strong data-testid="run-state">{stateLabel(state)}</strong> · 사건{' '}
                    <span data-testid="event-count">{events.length}</span>건
                  </p>
                  <ol className="events">
                    {events.map((e) => (
                      <li key={e.event_id} className={`kind-${e.kind}`}>
                        <code>{e.seq}</code> <b>{e.kind}</b> {summary(e)}
                      </li>
                    ))}
                  </ol>
                </>
              )}
            </>
          ) : (
            <p>왼쪽에서 작업을 고른다.</p>
          )}
        </section>
      </div>
    </main>
  );
}
