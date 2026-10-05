import type { Run, RunEvent } from './api';
import { reportStatus, statusLabel, undecidedRules } from './runState';

/**
 * The status line of one run: the report status, the server's lifecycle, the event count,
 * and the contract rules this screen does not judge, so "작업 중" is not read as more than it is.
 */
export function RunStatusLine({ run, events, now }: { run: Run; events: readonly RunEvent[]; now: number }) {
  const undecided = undecidedRules(run, events);
  return (
    <div className="status">
      <p>
        보고 상태: <strong data-testid="report-status">{statusLabel(reportStatus(run, events, now))}</strong>
        {' · '}실행 시도:{' '}
        <span data-testid="run-lifecycle">
          {run.state}
          {run.end_reason && `/${run.end_reason}`}
        </span>
        {' · '}사건 <span data-testid="event-count">{events.length}</span>건
      </p>
      {undecided.length > 0 && (
        <ul className="undecided" data-testid="undecided">
          {undecided.map((u) => (
            <li key={u.rule} className={u.relevant ? 'relevant' : undefined}>
              {u.rule}: 판정하지 않음 — {u.reason}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
