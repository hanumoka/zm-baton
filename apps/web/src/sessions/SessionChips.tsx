import { createPortal } from 'react-dom';
import { CheckChip, SelectChip } from '../ui/Chip';
import type { RepoOption, SessionFilters } from './sessionView';

type Props = {
  filters: SessionFilters;
  onFilters: (f: Partial<SessionFilters>) => void;
  /** Only the repositories the listed sessions are in. */
  repos: RepoOption[];
};

/**
 * The session screen's filter chips, drawn into the row under the command line (`#screen-chips`,
 * DESIGN.md 「화면 배치」 2): repository, and whether ended sessions show. Searching by session id,
 * work and branch is the command line's job.
 */
export function SessionChips({ host, ...props }: Props & { host: HTMLElement | null }) {
  return host ? createPortal(<SessionChipRow {...props} />, host) : null;
}

export function SessionChipRow({ filters, onFilters, repos }: Props) {
  return (
    <div className="chip-row" role="group" aria-label="세션 거르기">
      <SelectChip label="저장소" value={filters.repo} onChange={(repo) => onFilters({ repo })}>
        <option value="">전체</option>
        {repos.map((r) => (
          <option key={r.id} value={r.id}>
            {r.label} ({r.count})
          </option>
        ))}
      </SelectChip>
      <CheckChip checked={filters.ended} onChange={(ended) => onFilters({ ended })}>
        끝난 세션 보기
      </CheckChip>
    </div>
  );
}
