import { useEffect, useRef } from 'react';
import { DecisionMark } from '../icons/DecisionMark';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import type { Decision } from '../workApi';
import { decisionStatusLabel } from '../workModel';
import { previewText, rowOrder, type Bucket, type DecisionGroup, type ListState } from './decisionView';

/** What an empty group says. */
const GROUP_EMPTY: Record<Bucket, { title: string; body?: string }> = {
  open: { title: '제안 상태로 남은 결정이 없다' },
  current: { title: '지금 유효한 결정이 없다' },
  past: { title: '대체되거나 기각된 결정이 없다' },
};

type Props = {
  state: ListState;
  groups: DecisionGroup[];
  head: ListHead;
  selected: string | null;
  /** The arrow keys moved the selection. */
  onSelect: (id: string) => void;
  /** A click or tap on a row: select it (wide) or open it as a sheet (narrow). */
  onPick: (id: string) => void;
  /** Enter on a row: the detail takes the focus (wide) or opens as a sheet (narrow). */
  onOpen: (id: string) => void;
  onShowPast: () => void;
  onClearFilters: () => void;
  onReload: () => void;
};

/**
 * The decision list, grouped by status: to decide, in force, and past (superseded and rejected,
 * folded until asked for). Each group is newest first. The keys are the work list's: the arrows,
 * Home and End move the selection, Enter opens it.
 */
export function DecisionList({ state, groups, head, selected, onSelect, onPick, onOpen, onShowPast, onClearFilters, onReload }: Props) {
  const order = rowOrder(groups);
  const box = useRef<HTMLDivElement>(null);
  // A decision opened by its address or by a supersession link may sit far down: bring its row into view.
  const shows = selected !== null && order.includes(selected);
  useEffect(() => {
    if (!shows || selected === null) return;
    box.current?.querySelector<HTMLElement>(`[data-row="${CSS.escape(selected)}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [selected, shows]);
  return (
    <ListPane title="결정 목록" head={head} by="상태로 묶음" order={order} onSelect={onSelect} onOpen={onOpen} bodyRef={box}>
      <ListBody
        state={state}
        groups={groups}
        selected={selected}
        tabbable={tabStop(order, selected)}
        onPick={onPick}
        onShowPast={onShowPast}
        onClearFilters={onClearFilters}
        onReload={onReload}
      />
    </ListPane>
  );
}

function ListBody({
  state,
  groups,
  selected,
  tabbable,
  onPick,
  onShowPast,
  onClearFilters,
  onReload,
}: Omit<Props, 'head' | 'onOpen' | 'onSelect'> & { tabbable: string | null }) {
  switch (state.kind) {
    case 'loading':
      return <RowSkeleton rows={4} />;
    case 'error':
      return (
        <EmptyState
          kind="error"
          title="결정 목록을 읽지 못했다"
          action={
            <button type="button" className="btn" onClick={onReload}>
              다시 읽기
            </button>
          }
        >
          {state.message}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 읽는다.
        </EmptyState>
      );
    case 'unused':
      return (
        <EmptyState kind="unused" title="아직 가져온 결정이 없다">
          결정 기록은 zm-docs의 <code>docs/governance/decisions/</code> 문서다. zm-docs 체크아웃에서 <code>zm-baton import zm-docs --apply</code>를
          실행하면 가져온다. 정본이 Git 파일이라 여기서는 읽기만 하고, 고칠 때는 zm-docs에서 고친 뒤 다시 가져온다.
        </EmptyState>
      );
    case 'filtered':
      return (
        <EmptyState
          kind="empty"
          title="거른 조건에 맞는 결정이 없다"
          action={
            <button type="button" className="btn" onClick={onClearFilters}>
              거르기 풀기
            </button>
          }
        >
          걸러 본 조건: {state.conditions.join(', ')}
        </EmptyState>
      );
    case 'rows':
      return (
        <>
          {groups.map((g) => (
            <div key={g.bucket} className="pane-grp" data-bucket={g.bucket}>
              <GroupHead mark={<DecisionMark status={g.mark} />} label={g.label} count={g.items.length} />
              {g.items.length === 0 ? (
                <EmptyState kind="empty" title={GROUP_EMPTY[g.bucket].title}>
                  {GROUP_EMPTY[g.bucket].body}
                </EmptyState>
              ) : g.folded ? (
                <p className="dc-fold">
                  <span>대체되거나 기각된 결정은 접어 두었다.</span>
                  <button type="button" className="btn btn-quiet" onClick={onShowPast}>
                    지난 것 {g.items.length}건 보기
                  </button>
                </p>
              ) : (
                <ul className="pane-rows">
                  {g.items.map((d) => (
                    <li key={d.id}>
                      <DecisionRow decision={d} selected={d.id === selected} tabbable={d.id === tabbable} onPick={onPick} />
                    </li>
                  ))}
                </ul>
              )}
            </div>
          ))}
        </>
      );
  }
}

/**
 * One row: the status mark and name, the number in monospace and the question; under them the
 * date, the projects and the start of the decision, cut to a line. A superseded decision says
 * which one replaced it first. Below 1180px the projects drop out (ui/panes.css).
 */
export function DecisionRow({
  decision: d,
  selected,
  tabbable,
  onPick,
}: {
  decision: Decision;
  selected: boolean;
  tabbable: boolean;
  onPick: (id: string) => void;
}) {
  return (
    <button
      type="button"
      className="lrow"
      data-row={d.id}
      aria-current={selected ? 'true' : undefined}
      tabIndex={tabbable ? 0 : -1}
      onClick={() => onPick(d.id)}
    >
      <span className="lrow-l1">
        <DecisionMark status={d.status} />
        <span className="lrow-state">{decisionStatusLabel(d.status)}</span>
        {d.external_ref && <code className="lrow-ref">{d.external_ref}</code>}
        <span className="lrow-title">{d.question}</span>
      </span>
      <span className="lrow-sub">
        <span className="dc-date num">{d.decided_on ?? '날짜 없음'}</span>
        {d.projects.length > 0 && <span className="lrow-proj">{d.projects.map((p) => p.name).join(', ')}</span>}
        {d.superseded_by && <span className="dc-by">대체한 결정 {d.superseded_by.external_ref ?? d.superseded_by.question}</span>}
        <span className="dc-choice">{previewText(d)}</span>
      </span>
    </button>
  );
}
