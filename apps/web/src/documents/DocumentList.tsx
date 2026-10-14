import { createPortal } from 'react-dom';
import type { Doc, DocSource } from '../documentsApi';
import { Icon } from '../icons/Icon';
import { SelectChip } from '../ui/Chip';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import type { Project } from '../workApi';
import { rowOrder, SOURCE_LABELS, sourceIcon, versionLabel, type Bucket, type DocFilters, type DocGroup, type ListState } from './documentView';

const GROUP_EMPTY: Record<Bucket, string> = {
  review: '승인을 기다리는 판이 없다',
  approved: '최신 판이 승인된 문서가 없다',
  none: '판 없이 등록만 한 문서가 없다',
};

type Props = {
  state: ListState;
  groups: DocGroup[];
  head: ListHead;
  selected: string | null;
  onSelect: (id: string) => void;
  onPick: (id: string) => void;
  onOpen: (id: string) => void;
  onNew: () => void;
  onClearFilters: () => void;
  onReload: () => void;
};

/**
 * The document list, grouped by where its newest version's approval stands: waiting for approval,
 * approved, and no version yet. Each group is most recently changed first; the keys are the other
 * lists': the arrows, Home and End move the selection, Enter opens it.
 */
export function DocumentList({ state, groups, head, selected, onSelect, onPick, onOpen, onNew, onClearFilters, onReload }: Props) {
  const order = rowOrder(groups);
  const tabbable = tabStop(order, selected);
  return (
    <ListPane
      title="문서 목록"
      head={head}
      by="승인 상태로 묶음"
      action={
        <button type="button" className="btn btn-primary" onClick={onNew}>
          <Icon name="plus" />새 문서
        </button>
      }
      order={order}
      onSelect={onSelect}
      onOpen={onOpen}
    >
      {state.kind === 'loading' ? (
        <RowSkeleton rows={4} />
      ) : state.kind === 'error' ? (
        <EmptyState
          kind="error"
          title="문서 목록을 읽지 못했다"
          action={
            <button type="button" className="btn" onClick={onReload}>
              다시 읽기
            </button>
          }
        >
          {state.message}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 읽는다.
        </EmptyState>
      ) : state.kind === 'unused' ? (
        <EmptyState
          kind="unused"
          title="아직 등록한 문서가 없다"
          action={
            <button type="button" className="btn btn-primary" onClick={onNew}>
              <Icon name="plus" />새 문서 등록하기
            </button>
          }
        >
          문서마다 정본 위치를 하나로 정한다. 제품 안 문서는 여기서 Markdown으로 판을 쌓고 승인한다. Git 문서는 저장소의 경로와 커밋을 기록하고 본문은 Git에서
          고친다.
        </EmptyState>
      ) : state.kind === 'filtered' ? (
        <EmptyState
          kind="empty"
          title="거른 조건에 맞는 문서가 없다"
          action={
            <button type="button" className="btn" onClick={onClearFilters}>
              거르기 풀기
            </button>
          }
        >
          걸러 본 조건: {state.conditions.join(', ')}
        </EmptyState>
      ) : (
        groups.map((g) => (
          <div key={g.bucket} className="pane-grp" data-bucket={g.bucket}>
            <GroupHead mark={<Icon name={g.icon} />} label={g.label} count={g.items.length} />
            {g.items.length === 0 ? (
              <EmptyState kind="empty" title={GROUP_EMPTY[g.bucket]} />
            ) : (
              <ul className="pane-rows">
                {g.items.map((d) => (
                  <li key={d.id}>
                    <DocumentRow doc={d} selected={d.id === selected} tabbable={d.id === tabbable} onPick={onPick} />
                  </li>
                ))}
              </ul>
            )}
          </div>
        ))
      )}
    </ListPane>
  );
}

/**
 * One row: the source mark and the version's state, then the title; under them the source in
 * words, the project (gone below 1180px) and, for a Git document, its path.
 */
export function DocumentRow({ doc: d, selected, tabbable, onPick }: { doc: Doc; selected: boolean; tabbable: boolean; onPick: (id: string) => void }) {
  return (
    <button type="button" className="lrow" data-row={d.id} aria-current={selected ? 'true' : undefined} tabIndex={tabbable ? 0 : -1} onClick={() => onPick(d.id)}>
      <span className="lrow-l1">
        <Icon name={sourceIcon(d.source_of_truth)} />
        <span className="lrow-state">{versionLabel(d)}</span>
        <span className="lrow-title">{d.title}</span>
      </span>
      <span className="lrow-sub">
        <span className="doc-src">{SOURCE_LABELS[d.source_of_truth]}</span>
        {d.project && <span className="lrow-proj">{d.project.name}</span>}
        {d.source_of_truth === 'git' && <code className="doc-path">{d.git_ref?.path}</code>}
      </span>
    </button>
  );
}

/** The filter chips under the command line: project and source of truth with how many each has. */
export function DocumentChips({ host, ...props }: ChipProps & { host: HTMLElement | null }) {
  return host ? createPortal(<DocumentChipRow {...props} />, host) : null;
}

type ChipProps = {
  filters: DocFilters;
  onFilters: (f: Partial<DocFilters>) => void;
  projects: Project[];
  counts: Record<DocSource, number>;
};

export function DocumentChipRow({ filters, onFilters, projects, counts }: ChipProps) {
  return (
    <div className="chip-row" role="group" aria-label="문서 거르기">
      <SelectChip label="프로젝트" value={filters.projectId} onChange={(projectId) => onFilters({ projectId })}>
        <option value="">전체</option>
        {projects.map((p) => (
          <option key={p.id} value={p.id}>
            {p.name}
          </option>
        ))}
      </SelectChip>
      <SelectChip label="정본 위치" value={filters.source} onChange={(source) => onFilters({ source: source as DocSource | '' })}>
        <option value="">전체</option>
        {(['product', 'git'] as const).map((s) => (
          <option key={s} value={s}>
            {SOURCE_LABELS[s]} ({counts[s]})
          </option>
        ))}
      </SelectChip>
    </div>
  );
}
