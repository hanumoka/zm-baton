import { createPortal } from 'react-dom';
import { DecisionMark, type DecisionStatus } from '../icons/DecisionMark';
import { CheckChip, SelectChip } from '../ui/Chip';
import type { Project } from '../workApi';
import type { DecisionFilters, StatusOption } from './decisionView';

type Props = {
  filters: DecisionFilters;
  onFilters: (f: Partial<DecisionFilters>) => void;
  projects: Project[];
  statuses: StatusOption[];
  /** Superseded and rejected decisions among those the project and search leave. */
  pastCount: number;
};

/**
 * The decisions screen's filter chips, drawn into the row under the command line
 * (`#screen-chips`, DESIGN.md 「화면 배치」 2): project, status with how many each has, and
 * whether 지난 것 unfolds. Searching is the command line's job.
 */
export function DecisionChips({ host, ...props }: Props & { host: HTMLElement | null }) {
  return host ? createPortal(<DecisionChipRow {...props} />, host) : null;
}

export function DecisionChipRow({ filters, onFilters, projects, statuses, pastCount }: Props) {
  return (
    <div className="chip-row" role="group" aria-label="결정 거르기">
      <SelectChip label="프로젝트" value={filters.projectId} onChange={(projectId) => onFilters({ projectId })}>
        <option value="">전체</option>
        {projects.map((p) => (
          <option key={p.id} value={p.id}>
            {p.name}
          </option>
        ))}
      </SelectChip>
      <SelectChip label="상태" value={filters.status} onChange={(status) => onFilters({ status: status as DecisionStatus | '' })}>
        <option value="">전체</option>
        {statuses.map((o) => (
          <option key={o.status} value={o.status}>
            {o.label} ({o.count})
          </option>
        ))}
      </SelectChip>
      <CheckChip checked={filters.showPast} onChange={(showPast) => onFilters({ showPast })}>
        <DecisionMark status="superseded" />
        지난 것 보기 <span className="n num">{pastCount}</span>
      </CheckChip>
    </div>
  );
}
