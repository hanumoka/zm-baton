import { createPortal } from 'react-dom';
import type { AuditEntry, AuditFacets } from '../auditApi';
import { Icon } from '../icons/Icon';
import { SelectChip } from '../ui/Chip';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import {
  ACTOR_CLASSES,
  ACTOR_LABELS,
  actionLabel,
  actorClass,
  actorIcon,
  knownAction,
  PERIOD_LABELS,
  rowOrder,
  shortId,
  targetLabel,
  targetOptions,
  timeOf,
  type AuditFilters,
  type DayGroup,
  type ListState,
  type Period,
} from './auditView';

type Props = {
  state: ListState;
  groups: DayGroup[];
  head: ListHead;
  selected: string | null;
  onSelect: (id: string) => void;
  onPick: (id: string) => void;
  onOpen: (id: string) => void;
  /** Opens the retention settings and the export in the detail pane. */
  onRetention: () => void;
  retentionOpen: boolean;
  onClearFilters: () => void;
  onReload: () => void;
  /** More rows can be read (「더 보기」), or the list reached what the screen reads at once. */
  more: 'more' | 'cap' | null;
  onMore: () => void;
};

/**
 * The audit log, newest first, grouped by day. A row is when, who, what and on what; the keys are
 * the other lists': the arrows, Home and End move the selection, Enter opens it. The head's button
 * opens the retention settings and the export.
 */
export function AuditList({ state, groups, head, selected, onSelect, onPick, onOpen, onRetention, retentionOpen, onClearFilters, onReload, more, onMore }: Props) {
  const order = rowOrder(groups);
  const tabbable = tabStop(order, selected);
  return (
    <ListPane
      title="감사 기록 목록"
      head={head}
      by="날짜로 묶음"
      action={
        <button type="button" className="btn" aria-pressed={retentionOpen} onClick={onRetention}>
          보존 규칙과 내보내기
        </button>
      }
      order={order}
      onSelect={onSelect}
      onOpen={onOpen}
    >
      {state.kind === 'loading' ? (
        <RowSkeleton rows={5} />
      ) : state.kind === 'error' ? (
        <EmptyState
          kind="error"
          title="감사 기록을 읽지 못했다"
          action={
            <button type="button" className="btn" onClick={onReload}>
              다시 읽기
            </button>
          }
        >
          {state.message}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 읽는다.
        </EmptyState>
      ) : state.kind === 'unused' ? (
        <EmptyState kind="unused" title="아직 남은 감사 기록이 없다">
          서버의 상태를 바꾸는 요청마다 누가, 무엇을, 무엇에 했는지 한 줄씩 남는다. 작업을 만들거나 세션을 등록하면 여기에 쌓인다.
        </EmptyState>
      ) : state.kind === 'filtered' ? (
        <EmptyState
          kind="empty"
          title="거른 조건에 맞는 기록이 없다"
          action={
            <button type="button" className="btn" onClick={onClearFilters}>
              거르기 풀기
            </button>
          }
        >
          걸러 본 조건: {state.conditions.join(', ')}
        </EmptyState>
      ) : (
        <>
          {groups.map((g) => (
            <div key={g.day} className="pane-grp">
              <GroupHead mark={null} label={g.label} count={g.items.length} />
              <ul className="pane-rows">
                {g.items.map((e) => (
                  <li key={e.id}>
                    <AuditRow entry={e} selected={e.id === selected} tabbable={e.id === tabbable} onPick={onPick} />
                  </li>
                ))}
              </ul>
            </div>
          ))}
          {more === 'more' && (
            <p className="au-more">
              <button type="button" className="btn" onClick={onMore}>
                더 보기
              </button>
            </p>
          )}
          {more === 'cap' && <p className="au-more au-note">한 번에 500건까지 보인다. 기간이나 대상으로 좁혀 본다.</p>}
        </>
      )}
    </ListPane>
  );
}

/** One row: who (shape and word) and what, then when, on what and the actor's id. */
export function AuditRow({ entry: e, selected, tabbable, onPick }: { entry: AuditEntry; selected: boolean; tabbable: boolean; onPick: (id: string) => void }) {
  const who = actorClass(e);
  return (
    <button type="button" className="lrow" data-row={e.id} aria-current={selected ? 'true' : undefined} tabIndex={tabbable ? 0 : -1} onClick={() => onPick(e.id)}>
      <span className="lrow-l1">
        <Icon name={actorIcon(who)} />
        <span className="lrow-state">{ACTOR_LABELS[who]}</span>
        <span className="lrow-title">{actionLabel(e.action)}</span>
      </span>
      <span className="lrow-sub">
        <time className="num" dateTime={e.at}>
          {timeOf(e.at)}
        </time>
        <span>
          {targetLabel(e.target_kind)}
          {e.target_id && (
            <>
              {' '}
              <code className="au-id">{shortId(e.target_id)}</code>
            </>
          )}
        </span>
        {!knownAction(e.action) ? <code className="au-id">{e.action}</code> : <code className="au-id au-actor">{shortId(e.actor_id)}</code>}
      </span>
    </button>
  );
}

/** The filter chips under the command line: actor class, target kind and period, with how many each has. */
export function AuditChips({ host, ...props }: ChipProps & { host: HTMLElement | null }) {
  return host ? createPortal(<AuditChipRow {...props} />, host) : null;
}

type ChipProps = {
  filters: AuditFilters;
  onFilters: (f: Partial<AuditFilters>) => void;
  facets: AuditFacets | null;
};

export function AuditChipRow({ filters, onFilters, facets }: ChipProps) {
  const n = (k: number | undefined) => (facets === null ? '' : ` (${k ?? 0})`);
  return (
    <div className="chip-row" role="group" aria-label="감사 기록 거르기">
      <SelectChip label="행위자" value={filters.actor} onChange={(actor) => onFilters({ actor: actor as AuditFilters['actor'] })}>
        <option value="">전체</option>
        {ACTOR_CLASSES.map((c) => (
          <option key={c} value={c}>
            {ACTOR_LABELS[c]}
            {n(facets?.actor_classes[c])}
          </option>
        ))}
      </SelectChip>
      <SelectChip label="대상" value={filters.target} onChange={(target) => onFilters({ target })}>
        <option value="">전체</option>
        {targetOptions(facets, filters.target).map((o) => (
          <option key={o.kind} value={o.kind}>
            {targetLabel(o.kind === 'none' ? null : o.kind)}
            {n(o.count)}
          </option>
        ))}
      </SelectChip>
      <SelectChip label="기간" value={filters.period} onChange={(period) => onFilters({ period: period as Period })}>
        {(Object.keys(PERIOD_LABELS) as Period[]).map((p) => (
          <option key={p} value={p}>
            {PERIOD_LABELS[p]}
          </option>
        ))}
      </SelectChip>
      {filters.targetId && (
        <button type="button" className="chip au-tid" onClick={() => onFilters({ targetId: '' })} aria-label={`대상 번호 ${filters.targetId} 거르기 풀기`}>
          대상 번호 <code>{shortId(filters.targetId)}</code>
          <Icon name="close" />
        </button>
      )}
    </div>
  );
}
