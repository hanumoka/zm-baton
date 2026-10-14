import type { Account } from '../accountsApi';
import { Icon } from '../icons/Icon';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import { causeLabel, KIND_LABELS, rowOrder, todayLine, type AccountGroup, type Bucket, type ListState } from './accountView';

const GROUP_EMPTY: Record<Bucket, string> = {
  ready: '지금 일을 맡을 수 있는 계정이 없다',
  stopped: '상한이나 신호로 멈춘 계정이 없다',
  disabled: '비활성화한 계정이 없다',
};

type Props = {
  state: ListState;
  groups: AccountGroup[];
  head: ListHead;
  selected: string | null;
  onSelect: (id: string) => void;
  onPick: (id: string) => void;
  onOpen: (id: string) => void;
  onNew: () => void;
  onReload: () => void;
};

/**
 * The AI accounts, grouped by whether they can take work now: ready, stopped by a limit or a
 * signal, and disabled. The keys are the other lists': the arrows, Home and End move the selection,
 * Enter opens it.
 */
export function AccountList({ state, groups, head, selected, onSelect, onPick, onOpen, onNew, onReload }: Props) {
  const order = rowOrder(groups);
  const tabbable = tabStop(order, selected);
  return (
    <ListPane
      title="AI 계정 목록"
      head={head}
      by="쓸 수 있는지로 묶음"
      action={
        <button type="button" className="btn btn-primary" onClick={onNew}>
          <Icon name="plus" />새 계정
        </button>
      }
      order={order}
      onSelect={onSelect}
      onOpen={onOpen}
    >
      {state.kind === 'loading' ? (
        <RowSkeleton rows={3} />
      ) : state.kind === 'error' ? (
        <EmptyState
          kind="error"
          title="AI 계정을 읽지 못했다"
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
          title="등록한 AI 계정이 없다"
          action={
            <button type="button" className="btn btn-primary" onClick={onNew}>
              <Icon name="plus" />새 계정 등록하기
            </button>
          }
        >
          AI에게 일을 맡기려면 계정이 하나 있어야 한다. 계정은 로그인 정보가 아니라 어느 PC의 어떤 로그인이나 키로 돌지를 가리키는 이름이다.
        </EmptyState>
      ) : (
        groups.map((g) => (
          <div key={g.bucket} className="pane-grp" data-bucket={g.bucket}>
            <GroupHead mark={<Icon name={g.icon} />} label={g.label} count={g.items.length} />
            {g.items.length === 0 ? (
              <EmptyState kind="empty" title={GROUP_EMPTY[g.bucket]} />
            ) : (
              <ul className="pane-rows">
                {g.items.map((a) => (
                  <li key={a.id}>
                    <AccountRow account={a} selected={a.id === selected} tabbable={a.id === tabbable} onPick={onPick} />
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

/** One row: the kind, then the name; under them today's use against the daily limit, or why it is stopped. */
export function AccountRow({ account: a, selected, tabbable, onPick }: { account: Account; selected: boolean; tabbable: boolean; onPick: (id: string) => void }) {
  return (
    <button type="button" className="lrow" data-row={a.id} aria-current={selected ? 'true' : undefined} tabIndex={tabbable ? 0 : -1} onClick={() => onPick(a.id)}>
      <span className="lrow-l1">
        <Icon name="key" />
        <span className="lrow-state">{KIND_LABELS[a.auth_kind]}</span>
        <span className="lrow-title">{a.name}</span>
      </span>
      <span className="lrow-sub">
        {a.block && a.status !== 'disabled' && <span className="acc-flag">멈춤: {causeLabel(a.block.cause)}</span>}
        <span className="num acc-use">{todayLine(a)}</span>
        {a.today.runs > 0 && <span className="num">실행 {a.today.runs}회</span>}
      </span>
    </button>
  );
}
