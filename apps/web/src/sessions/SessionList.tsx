import { Icon } from '../icons/Icon';
import { executorLabel, repoName, since, stateLabel } from '../inboxModel';
import type { SessionView } from '../sessionsApi';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import { rowOrder, workLine, type GroupKey, type ListState, type SessionGroup } from './sessionView';

const GROUP_EMPTY: Record<GroupKey, string> = {
  live: '살아 있는 세션이 없다',
  other: '끝났거나 등록을 끈 세션이 없다',
};

type Props = {
  state: ListState;
  groups: SessionGroup[];
  head: ListHead;
  selected: string | null;
  now: number;
  /** The arrow keys moved the selection. */
  onSelect: (id: string) => void;
  /** A click or tap on a row: select it (wide) or open it as a sheet (narrow). */
  onPick: (id: string) => void;
  /** Enter on a row: the detail takes the focus (wide) or opens as a sheet (narrow). */
  onOpen: (id: string) => void;
  onShowEnded: () => void;
  onClearFilters: () => void;
  onReload: () => void;
};

/**
 * Registered AI sessions, live ones first and, with ended sessions shown, the rest under them;
 * each group with the newest signal first. The keys are the work list's: the arrows, Home and End
 * move the selection, Enter opens it.
 */
export function SessionList({ state, groups, head, selected, now, onSelect, onPick, onOpen, onShowEnded, onClearFilters, onReload }: Props) {
  const order = rowOrder(groups);
  return (
    <ListPane title="세션 목록" head={head} by="살아 있는지로 묶음" order={order} onSelect={onSelect} onOpen={onOpen}>
      <ListBody
        state={state}
        groups={groups}
        selected={selected}
        tabbable={tabStop(order, selected)}
        now={now}
        onPick={onPick}
        onShowEnded={onShowEnded}
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
  now,
  onPick,
  onShowEnded,
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
          title="세션 목록을 읽지 못했다"
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
        <EmptyState kind="unused" title="등록된 세션이 없다">
          훅을 붙인 저장소에서 Claude Code를 새로 열면 시작 훅이 <code>zm-baton session register</code>로 등록하고 여기에 나타난다. 훅은{' '}
          <code>zm-baton hooks install</code>로 붙인다.
        </EmptyState>
      );
    case 'no-live':
      return (
        <EmptyState
          kind="empty"
          title="지금 살아 있는 세션이 없다"
          action={
            <button type="button" className="btn" onClick={onShowEnded}>
              끝난 세션 보기
            </button>
          }
        >
          등록했던 세션은 모두 끝났거나, 등록을 껐거나, 만료됐다.
        </EmptyState>
      );
    case 'filtered':
      return (
        <EmptyState
          kind="empty"
          title="거른 조건에 맞는 세션이 없다"
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
            <div key={g.key} className="pane-grp" data-group={g.key}>
              <GroupHead mark={<Icon name={g.mark} />} label={g.label} count={g.items.length} aside={g.aside} />
              {g.items.length === 0 ? (
                <EmptyState kind="empty" title={GROUP_EMPTY[g.key]} />
              ) : (
                <ul className="pane-rows">
                  {g.items.map((s) => (
                    <li key={s.id}>
                      <SessionRow session={s} now={now} selected={s.id === selected} tabbable={s.id === tabbable} onPick={onPick} />
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
 * One row: the executor and what the session works on (or that it does not share it), and a
 * conflict mark when another session touched one of its files; under them the state (when not
 * live), repository, branch, last signal and device. Below 1180px the repository drops out.
 */
export function SessionRow({
  session: s,
  now,
  selected,
  tabbable,
  onPick,
}: {
  session: SessionView;
  now: number;
  selected: boolean;
  tabbable: boolean;
  onPick: (id: string) => void;
}) {
  const work = workLine(s);
  const overlaps = s.conflicted_files.length;
  return (
    <button
      type="button"
      className="lrow"
      data-row={s.id}
      aria-current={selected ? 'true' : undefined}
      tabIndex={tabbable ? 0 : -1}
      onClick={() => onPick(s.id)}
    >
      <span className="lrow-l1">
        <Icon name="session" />
        <span className="lrow-state">{executorLabel(s.executor)}</span>
        <span className={work.shown ? 'lrow-title' : 'lrow-title ss-none'}>{work.text}</span>
        {overlaps > 0 && (
          <span className="ss-ovl">
            <Icon name="conflict" />
            겹침 <span className="num">{overlaps}</span>
          </span>
        )}
      </span>
      <span className="lrow-sub">
        {s.state !== 'live' && <span className="ss-state">{stateLabel(s.state)}</span>}
        <span className="lrow-proj">{repoName(s)}</span>
        {s.repository?.branch && <span className="ss-branch">{s.repository.branch}</span>}
        <span className="num">마지막 신호 {since(s.last_seen_at, now)}</span>
        <span>{s.device_name}</span>
      </span>
    </button>
  );
}
