import type { ReactNode } from 'react';

/*
 * Group heads for lists grouped by who holds the work now (DESIGN.md 「화면 배치」 4): waiting,
 * AI sessions, people (a team: me and each teammate), done. A group shows even when empty, with
 * an empty-state line under it, so 0 reads as 0 and not as missing.
 */

type HeadProps = {
  /** An Icon or a StageGlyph; it stands before the name. */
  mark: ReactNode;
  label: string;
  count: number;
  /** A short column title at the right, like 「열린 지」. */
  aside?: string;
};

export function GroupHead({ mark, label, count, aside }: HeadProps) {
  return (
    <h3 className="grp">
      {mark}
      {label} <span className="n num">{count}</span>
      {aside && <span className="grp-r">{aside}</span>}
    </h3>
  );
}

/** A teammate's name under 「팀원」, with how many items they hold. */
export function GroupSubHead({ label, count }: { label: string; count: number }) {
  return (
    <h4 className="sub">
      {label} <span className="n num">{count}</span>
    </h4>
  );
}

/** A list row's shape while it loads (DESIGN.md 「상태와 문구」: no spinner in the middle). */
export function RowSkeleton({ rows = 3 }: { rows?: number }) {
  const widths = ['72%', '58%', '66%', '49%'];
  return (
    <div className="skel-list" aria-busy="true">
      <span className="sr">불러오는 중</span>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skel-row" aria-hidden="true">
          <span className="skel skel-mark" />
          <span className="skel skel-title" style={{ width: widths[i % widths.length] }} />
          <span className="skel skel-meta" />
        </div>
      ))}
    </div>
  );
}
