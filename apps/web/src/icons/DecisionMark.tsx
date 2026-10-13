import type { Decision } from '../workApi';

/*
 * The decision status mark. Decisions are diamonds so they never read as a work stage (circles,
 * src/icons/StageGlyph.tsx). The shape alone tells the four apart at 16px, and a mark never stands
 * alone: the status name is always written next to it (DESIGN.md 「색」: not by colour alone).
 *
 * - 제안 (proposed): an outline with a dot in the middle, a decision still to be made
 * - 채택 (accepted): filled, in force
 * - 대체됨 (superseded): an outline struck through, replaced by a later decision
 * - 기각 (rejected): a dashed outline, it never came into force
 */

export type DecisionStatus = Decision['status'];

const DIAMOND = 'M8 1.5 14.5 8 8 14.5 1.5 8Z';
/** Eight dashes around the outline (its length is 4 × 6.5 × √2 ≈ 36.8). */
const DASH = '1.2 3.4';

export function DecisionMark({ status, className }: { status: DecisionStatus; className?: string }) {
  const classes = ['dmark', `is-${status}`, className ?? ''].filter(Boolean).join(' ');
  return (
    <svg className={classes} viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false" data-status={status}>
      {status === 'accepted' ? (
        <path className="fill" d={DIAMOND} />
      ) : (
        <path className="ring" d={DIAMOND} strokeDasharray={status === 'rejected' ? DASH : undefined} />
      )}
      {status === 'proposed' && <circle className="dot" cx="8" cy="8" r="1.25" />}
      {status === 'superseded' && <path className="ring" d="M5 8h6" />}
    </svg>
  );
}
