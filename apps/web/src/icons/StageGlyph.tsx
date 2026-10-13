import type { Stage } from '../workApi';

/*
 * The stage glyph (apps/web/DESIGN.md 「화면 배치」 5). The further a work item has come, the more
 * the circle fills from the bottom; when it is blocked the circle becomes a square filled to the
 * same height. The glyph is tied to the place in the order, not to the stage's name, so an
 * organisation that renames a stage changes no code. A glyph never stands alone: the stage name
 * is always written next to it.
 */

const CENTRE = 8;
/** Inside radius of the ring: ring radius 6.25 minus half of the 1.5 line. */
const INNER = 5.5;
const BOTTOM = CENTRE + INNER;
const SQUARE_CORNER = 2;
const DRAFT_DASH = '1.2 3.71';
const DRAFT_DASH_SQUARE = '1.2 3.33';

/** How high the inside is filled, from the bottom, in a 16px glyph: 2.75, 5.5, 8.25 and 11. */
export const FILL: Record<Stage, number> = {
  draft: 0,
  plan_review: 0,
  ready: 2.75,
  in_progress: 5.5,
  result_review: 8.25,
  done: 11,
  cancelled: 0,
};

/** Only work still on its way can be blocked; a closed stage keeps its own glyph. */
const OPEN: ReadonlySet<Stage> = new Set<Stage>(['draft', 'plan_review', 'ready', 'in_progress', 'result_review']);

const r3 = (n: number) => String(Math.round(n * 1000) / 1000);

/** The part of the circle's inside below the line [h] up from its bottom. */
export function circleFill(h: number): string {
  const y = BOTTOM - h;
  const half = Math.sqrt(INNER * INNER - (y - CENTRE) ** 2);
  const large = h > INNER ? 1 : 0;
  return `M${r3(CENTRE - half)} ${r3(y)}A${INNER} ${INNER} 0 ${large} 0 ${r3(CENTRE + half)} ${r3(y)}Z`;
}

/** The part of the square's inside below the line [h] up from its bottom, bottom corners round. */
export function squareFill(h: number): string {
  const lo = CENTRE - INNER;
  const hi = BOTTOM;
  const k = SQUARE_CORNER;
  return `M${lo} ${r3(hi - h)}H${hi}V${hi - k}A${k} ${k} 0 0 1 ${hi - k} ${hi}H${lo + k}A${k} ${k} 0 0 1 ${lo} ${hi - k}Z`;
}

type Props = {
  stage: Stage;
  /** True when the work item has a wait reason (막힘). Ignored once the stage is closed. */
  blocked?: boolean;
  className?: string;
};

export function StageGlyph({ stage, blocked = false, className }: Props) {
  const isBlocked = blocked && OPEN.has(stage);
  const h = FILL[stage] ?? 0;
  const classes = ['glyph', isBlocked ? 'is-block' : '', className ?? ''].filter(Boolean).join(' ');
  return (
    <svg className={classes} viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false" data-stage={stage}>
      {isBlocked ? (
        <>
          <rect className="ring" x="1.75" y="1.75" width="12.5" height="12.5" rx="2.75" strokeDasharray={stage === 'draft' ? DRAFT_DASH_SQUARE : undefined} />
          {h > 0 && <path className="fill" d={squareFill(h)} />}
        </>
      ) : stage === 'done' ? (
        <circle className="fill" cx="8" cy="8" r="7" />
      ) : (
        <>
          <circle className="ring" cx="8" cy="8" r="6.25" strokeDasharray={stage === 'draft' ? DRAFT_DASH : undefined} />
          {stage === 'cancelled' && <path className="ring" d="M5 11 11 5" />}
          {h > 0 && <path className="fill" d={circleFill(h)} />}
        </>
      )}
    </svg>
  );
}
