import type { ReactNode } from 'react';
import { Icon } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import type { WorkItem } from '../workApi';
import { stageLabel, waitLabel } from '../workModel';
import { isBlocked, rowOrder, type Holder, type HolderGroup, type ListState } from './workView';

export type { ListHead } from '../ui/ListPane';

const MARKS: Record<Holder, ReactNode> = {
  wait: <StageGlyph stage="in_progress" blocked />,
  ai: <Icon name="session" />,
  person: <Icon name="person" />,
  done: <StageGlyph stage="done" />,
};

/** What an empty group says. The AI line follows the server: new and imported work is held by a person. */
const GROUP_EMPTY: Record<Holder, { title: string; body?: string; sessions?: boolean }> = {
  wait: { title: '막힌 작업이 없다', body: '대기 사유가 붙은 열린 작업이 하나도 없다.' },
  ai: {
    title: 'AI 세션이 든 작업이 없다',
    body: '지금은 작업을 만들거나 가져오면 사람이 든다. 살아 있는 세션은 세션 현황에서 본다.',
    sessions: true,
  },
  person: { title: '사람이 든 작업이 없다' },
  done: { title: '완료하거나 취소한 작업이 없다' },
};

type Props = {
  state: ListState;
  groups: HolderGroup[];
  head: ListHead;
  selected: string | null;
  /** The arrow keys moved the selection. */
  onSelect: (id: string) => void;
  /** A click or tap on a row: select it (wide) or open it as a sheet (narrow). */
  onPick: (id: string) => void;
  /** Enter on a row: the detail takes the focus (wide) or opens as a sheet (narrow). */
  onOpen: (id: string) => void;
  onNew: () => void;
  onClearFilters: () => void;
  onShowClosed: () => void;
  onReload: () => void;
};

/**
 * The work list, grouped by who holds the work now (DESIGN.md 「화면 배치」 4). The arrow keys,
 * Home and End move the selection; only the selected row is in the Tab order, so Tab leaves the
 * list in one step. A group shows even when it is empty, with a line that says so.
 */
export function WorkList({ state, groups, head, selected, onSelect, onPick, onOpen, onNew, onClearFilters, onShowClosed, onReload }: Props) {
  const order = rowOrder(groups);
  return (
    <ListPane
      title="작업 목록"
      head={head}
      by="들고 있는 쪽으로 묶음"
      action={
        <button type="button" className="btn btn-primary" onClick={onNew}>
          <Icon name="plus" />새 작업
        </button>
      }
      order={order}
      onSelect={onSelect}
      onOpen={onOpen}
    >
      <ListBody
        state={state}
        groups={groups}
        selected={selected}
        tabbable={tabStop(order, selected)}
        onPick={onPick}
        onNew={onNew}
        onClearFilters={onClearFilters}
        onShowClosed={onShowClosed}
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
  onNew,
  onClearFilters,
  onShowClosed,
  onReload,
}: Omit<Props, 'head' | 'onOpen' | 'onSelect'> & { tabbable: string | null }) {
  switch (state.kind) {
    case 'loading':
      return <RowSkeleton rows={4} />;
    case 'error':
      return (
        <EmptyState
          kind="error"
          title="작업 목록을 읽지 못했다"
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
        <EmptyState
          kind="unused"
          title="아직 가져온 작업이 없다"
          action={
            <button type="button" className="btn btn-primary" onClick={onNew}>
              <Icon name="plus" />새 작업 만들기
            </button>
          }
        >
          zm-docs 체크아웃에서 <code>zm-baton import zm-docs --apply</code>를 실행하면 작업(<code>TSK-</code> 번호)을 가져온다. 가져온
          작업은 정본이 zm-docs의 Git 파일이라 여기서는 읽기만 한다. 여기서 만든 작업은 여기서 고친다.
        </EmptyState>
      );
    case 'no-open':
      return (
        <EmptyState
          kind="empty"
          title="열린 작업이 없다"
          action={
            <button type="button" className="btn" onClick={onShowClosed}>
              완료·취소 보기
            </button>
          }
        >
          작업이 모두 완료되거나 취소됐다.
        </EmptyState>
      );
    case 'filtered':
      return (
        <EmptyState
          kind="empty"
          title="거른 조건에 맞는 작업이 없다"
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
            <div key={g.holder} className="pane-grp" data-holder={g.holder}>
              <GroupHead mark={MARKS[g.holder]} label={g.label} count={g.items.length} />
              {g.items.length === 0 ? (
                <EmptyState
                  kind="empty"
                  title={GROUP_EMPTY[g.holder].title}
                  action={
                    GROUP_EMPTY[g.holder].sessions ? (
                      <a className="btn" href="#sessions">
                        세션 현황 열기
                      </a>
                    ) : undefined
                  }
                >
                  {GROUP_EMPTY[g.holder].body}
                </EmptyState>
              ) : (
                <ul className="pane-rows">
                  {g.items.map((i) => (
                    <li key={i.id}>
                      <WorkRow item={i} selected={i.id === selected} tabbable={i.id === tabbable} onPick={onPick} />
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
 * One row: the stage glyph and name, the number in monospace and the title; under them the
 * project, then the wait reason when the item is blocked or else its next action, cut to a line.
 * Below 1180px the project drops out of the second line (ui/panes.css), so the reason keeps its room.
 */
export function WorkRow({
  item,
  selected,
  tabbable,
  onPick,
}: {
  item: WorkItem;
  selected: boolean;
  tabbable: boolean;
  onPick: (id: string) => void;
}) {
  const blocked = isBlocked(item);
  return (
    <button
      type="button"
      className="lrow"
      data-row={item.id}
      aria-current={selected ? 'true' : undefined}
      tabIndex={tabbable ? 0 : -1}
      onClick={() => onPick(item.id)}
    >
      <span className="lrow-l1">
        <StageGlyph stage={item.stage} blocked={blocked} />
        <span className="lrow-state">{stageLabel(item.stage)}</span>
        {item.external_ref && <code className="lrow-ref">{item.external_ref}</code>}
        <span className="lrow-title">{item.title}</span>
      </span>
      <span className="lrow-sub">
        {item.project && <span className="lrow-proj">{item.project.name}</span>}
        {blocked ? (
          <>
            <span className="wrow-why">{item.wait_reasons.map(waitLabel).join(', ')}</span>
            {item.wait_note && <span className="wrow-note">{item.wait_note}</span>}
          </>
        ) : (
          <span className="wrow-next">{item.next_action ?? '다음 행동이 적혀 있지 않다'}</span>
        )}
      </span>
    </button>
  );
}
