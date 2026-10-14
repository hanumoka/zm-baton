import { useState } from 'react';
import { decisionsApi } from '../decisionsApi';
import { DecisionMark } from '../icons/DecisionMark';
import { usePoll } from '../usePoll';
import type { Decision } from '../workApi';
import { decisionStatusLabel } from '../workModel';
import { bucketOf, refOf } from './decisionView';

/** The decisions screen's address for one decision; a past one only shows there with 지난 것 unfolded. */
export function decisionHref(d: Pick<Decision, 'id' | 'status'>): string {
  const p = new URLSearchParams();
  if (bucketOf(d.status) === 'past') p.set('past', '1');
  p.set('item', d.id);
  return `#decisions?${p.toString()}`;
}

/** The linked decisions as links, for a work item's aside. Nothing is drawn when there are none. */
export function LinkedDecisionList({ decisions }: { decisions: Decision[] }) {
  if (decisions.length === 0) return null;
  return (
    <div className="dc-linked">
      <h3>
        이어진 결정 <span className="num">{decisions.length}</span>
      </h3>
      <ul className="dc-links">
        {decisions.map((d) => (
          <li key={d.id}>
            <a className="dc-link" href={decisionHref(d)}>
              <span className="dc-link-l1">
                <DecisionMark status={d.status} />
                <span className="lrow-state">{decisionStatusLabel(d.status)}</span>
                {refOf(d) && <code>{refOf(d)}</code>}
              </span>
              <span className="dc-link-t">{d.question}</span>
            </a>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * The decisions that name a work item among the work they are about (FN-0604), read on their own
 * so the work detail needs one line for them. A failed read leaves the list out: the work detail
 * has its own error line, and this list is a convenience beside it.
 */
export function LinkedDecisions({ workItemId }: { workItemId: string }) {
  const [list, setList] = useState<{ id: string; decisions: Decision[] } | null>(null);
  usePoll(
    async (alive, signal) => {
      const decisions = await decisionsApi.forWork(workItemId, signal);
      if (alive()) setList({ id: workItemId, decisions });
    },
    15000,
    [workItemId],
    () => setList(null),
  );
  return <LinkedDecisionList decisions={list?.id === workItemId ? list.decisions : []} />;
}
