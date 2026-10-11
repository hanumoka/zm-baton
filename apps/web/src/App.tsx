import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, type Run, type WorkItem } from './api';
import { RunStatusLine } from './RunStatusLine';
import { applyPolled, pollEvents, summary, type HeldEvents } from './runState';
import { usePoll } from './usePoll';

/** The stage-1 compat test screen: create a work item and a run, watch the run's events. */
export function App() {
  const [items, setItems] = useState<WorkItem[]>([]);
  const [workId, setWorkId] = useState<string>();
  const [runs, setRuns] = useState<{ workId?: string; list: Run[] }>({ list: [] });
  const [runId, setRunId] = useState<string>();
  const [held, setHeld] = useState<HeldEvents>({ runId: undefined, events: [] });
  const heldRef = useRef(held);
  heldRef.current = held;
  const tickRef = useRef(0);
  const [now, setNow] = useState(() => Date.now());
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string>();
  const report = (e: unknown) => setError(e instanceof Error ? e.message : String(e));

  function selectWork(id: string) {
    setWorkId(id);
    setRuns({ workId: id, list: [] });
    setRunId(undefined);
    setHeld({ runId: undefined, events: [] });
  }

  function selectRun(id: string) {
    setRunId(id);
    setHeld({ runId: id, events: [] });
  }

  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 5000);
    return () => clearInterval(id);
  }, []);

  usePoll(
    async (alive, signal) => {
      const list = await api.workItems(signal);
      if (alive()) setItems(list);
    },
    2000,
    [],
    report,
  );

  usePoll(
    async (alive, signal) => {
      if (!workId) return;
      const list = await api.runs(workId, signal);
      if (!alive()) return;
      setRuns({ workId, list });
      const newest = list.at(-1)?.id;
      if (newest && heldRef.current.runId === undefined) selectRun(newest);
    },
    2000,
    [workId],
    report,
  );

  usePoll(
    async (alive, signal) => {
      if (!runId) return;
      const mine = heldRef.current.runId === runId ? heldRef.current.events : [];
      const polled = await pollEvents((after, limit) => api.events(runId, after, limit, signal), mine, tickRef.current++);
      if (alive()) setHeld((h) => applyPolled(h, runId, polled));
    },
    1000,
    [runId],
    report,
  );

  const run = runs.workId === workId ? runs.list.find((r) => r.id === runId) : undefined;
  const events = held.runId === runId ? held.events : [];

  async function create(e: FormEvent) {
    e.preventDefault();
    if (!title.trim()) return;
    try {
      const work = await api.createWorkItem(title.trim());
      await api.createRun(work.id);
      setTitle('');
      selectWork(work.id);
      setItems(await api.workItems());
    } catch (err) {
      report(err);
    }
  }

  return (
    <main>
      <header>
        <h1>zm-baton</h1>
        <p>1단계 설치·호환 시험 화면. 실행 시도의 상태와 종료 이유는 서버가 정한 값이고, 보고 상태는 그 값과 사건에서 계산한다.</p>
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
                <button className={w.id === workId ? 'selected' : undefined} onClick={() => selectWork(w.id)}>
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
                {runs.workId === workId &&
                  runs.list.map((r) => (
                    <li key={r.id}>
                      <button className={r.id === runId ? 'selected' : undefined} onClick={() => selectRun(r.id)}>
                        {r.executor} · 세대 {r.generation ?? '-'}
                        {r.late_reports && ' · 늦은 보고 있음'}
                      </button>
                    </li>
                  ))}
              </ul>
              {run && (
                <>
                  <RunStatusLine run={run} events={events} now={now} />
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
