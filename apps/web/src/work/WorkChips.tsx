import { useEffect, useId, useRef } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import type { Project, Stage } from '../workApi';
import { SKIPPABLE, stageLabel } from '../workModel';
import type { StageOption, WorkFilters } from './workView';

type Props = {
  filters: WorkFilters;
  onFilters: (f: Partial<WorkFilters>) => void;
  projects: Project[];
  stages: StageOption[];
  /** Blocked items among those the other filters leave, so the chip says what checking it shows. */
  blockedCount: number;
  /** null until the setting has been read: the button then claims nothing. */
  skipped: Stage[] | null;
  onSkipped: (next: Stage[]) => void;
};

/**
 * The work screen's filter chips, drawn into the row under the command line (`#screen-chips`,
 * DESIGN.md 「화면 배치」 2). A one-person organisation has no assignee or team chips. The review
 * stage setting sits at the end of the row: it is a setting for the organisation, not a filter.
 */
export function WorkChips({ host, ...props }: Props & { host: HTMLElement | null }) {
  return host ? createPortal(<ChipRow {...props} />, host) : null;
}

export function ChipRow({ filters, onFilters, projects, stages, blockedCount, skipped, onSkipped }: Props) {
  return (
    <div className="wk-chips" role="group" aria-label="작업 거르기">
      <label className="chip">
        프로젝트
        <select value={filters.projectId} onChange={(e) => onFilters({ projectId: e.target.value })} aria-label="프로젝트">
          <option value="">전체</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name} ({p.open_work_items})
            </option>
          ))}
        </select>
        <Icon name="chevron-down" className="chev" />
      </label>
      <label className="chip">
        단계
        <select value={filters.stage} onChange={(e) => onFilters({ stage: e.target.value as Stage | '' })} aria-label="단계">
          <option value="">전체</option>
          {stages.map((o) => (
            <option key={o.stage} value={o.stage}>
              {o.label} ({o.count})
            </option>
          ))}
        </select>
        <Icon name="chevron-down" className="chev" />
      </label>
      <label className="chip">
        <input type="checkbox" checked={filters.blocked} onChange={(e) => onFilters({ blocked: e.target.checked })} />
        <StageGlyph stage="in_progress" blocked />
        막힘만 <span className="n num">{blockedCount}</span>
      </label>
      <label className="chip">
        <input type="checkbox" checked={filters.showClosed} onChange={(e) => onFilters({ showClosed: e.target.checked })} />
        완료·취소 보기
      </label>
      <ReviewSetting skipped={skipped} onChange={onSkipped} />
    </div>
  );
}

/** What the review stage button says: the setting at a glance, so nobody opens it to find out. */
export function reviewSummary(skipped: Stage[]): string {
  const used = SKIPPABLE.filter((s) => !skipped.includes(s));
  if (used.length === 0) return '검토 단계 건너뜀';
  if (used.length === SKIPPABLE.length) return '검토 단계 모두 씀';
  return `${used.map(stageLabel).join(', ')}만 씀`;
}

/**
 * The organisation uses or skips each review stage; a skipped stage is passed through by the
 * server. A native popover (the browser closes it with Esc and a click outside); it opens under
 * the button, its right edge on the button's, and never past the window's edge.
 */
function ReviewSetting({ skipped, onChange }: { skipped: Stage[] | null; onChange: (next: Stage[]) => void }) {
  const id = useId();
  const button = useRef<HTMLButtonElement>(null);
  const pop = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = pop.current;
    if (!el) return;
    const place = (e: Event) => {
      const b = button.current;
      if ((e as ToggleEvent).newState !== 'open' || !b) return;
      const r = b.getBoundingClientRect();
      const token = (name: string) => Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue(name)) || 0;
      el.style.top = `${r.bottom + token('--s-1')}px`;
      el.style.right = `${Math.max(token('--s-4'), window.innerWidth - r.right)}px`;
    };
    el.addEventListener('beforetoggle', place);
    return () => el.removeEventListener('beforetoggle', place);
  }, []);

  return (
    <>
      <button ref={button} type="button" className="chip wk-review" popoverTarget={id} disabled={skipped === null}>
        <Icon name="settings" />
        {skipped === null ? '검토 단계 읽는 중' : reviewSummary(skipped)}
        <Icon name="chevron-down" />
      </button>
      <div ref={pop} id={id} popover="auto" className="wk-pop" role="group" aria-label="검토 단계 설정">
        <p className="wk-pop-t">검토 단계</p>
        {SKIPPABLE.map((s) => (
          <label key={s} className="check">
            <input
              type="checkbox"
              checked={skipped !== null && !skipped.includes(s)}
              onChange={(e) => skipped !== null && onChange(e.target.checked ? skipped.filter((x) => x !== s) : [...skipped, s])}
            />
            {stageLabel(s)} 쓰기
          </label>
        ))}
        <p className="wk-pop-note">끄면 그 단계는 서버가 지나간다. 조직 전체에 적용되고 감사 기록에 남는다.</p>
      </div>
    </>
  );
}
