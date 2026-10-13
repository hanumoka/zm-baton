import { Icon } from '../icons/Icon';
import { shortId, waited } from '../inboxModel';
import type { InboxItem, SessionView } from '../sessionsApi';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import { groupNote, itemSessions, kindOf, notifyLabel, rowOrder, rowTitle, type InboxGroup, type ListState } from './inboxView';

type Props = {
  state: ListState;
  groups: InboxGroup[];
  head: ListHead;
  selected: string | null;
  /** Live sessions, to show what a waiting session works on. */
  sessions: SessionView[] | null;
  now: number;
  /** The arrow keys moved the selection. */
  onSelect: (id: string) => void;
  /** A click or tap on a row: select it (wide) or open it as a sheet (narrow). */
  onPick: (id: string) => void;
  /** Enter on a row: the detail takes the focus (wide) or opens as a sheet (narrow). */
  onOpen: (id: string) => void;
  onReload: () => void;
};

/**
 * What waits for me, in the contract's five buckets and their fixed order; in each, the longest
 * wait first. A bucket the server fills shows a short line when it is clear; one it does not fill
 * yet says so in its head, so an empty box never reads as 「nothing came」. The keys are the work
 * list's: the arrows, Home and End move the selection, Enter opens it.
 */
export function InboxList({ state, groups, head, selected, sessions, now, onSelect, onPick, onOpen, onReload }: Props) {
  const order = rowOrder(groups);
  return (
    <ListPane title="나를 기다리는 것 목록" head={head} by="급한 순서로 묶음" order={order} onSelect={onSelect} onOpen={onOpen}>
      <ListBody state={state} groups={groups} selected={selected} tabbable={tabStop(order, selected)} sessions={sessions} now={now} onPick={onPick} onReload={onReload} />
    </ListPane>
  );
}

function ListBody({
  state,
  groups,
  selected,
  tabbable,
  sessions,
  now,
  onPick,
  onReload,
}: Omit<Props, 'head' | 'onOpen' | 'onSelect'> & { tabbable: string | null }) {
  switch (state.kind) {
    case 'loading':
      return <RowSkeleton rows={4} />;
    case 'error':
      return (
        <EmptyState
          kind="error"
          title="나를 기다리는 것을 읽지 못했다"
          action={
            <button type="button" className="btn" onClick={onReload}>
              다시 읽기
            </button>
          }
        >
          {state.message}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 읽는다.
        </EmptyState>
      );
    case 'empty':
      return (
        <EmptyState
          kind="empty"
          title="지금 나를 기다리는 것이 없다"
          action={
            <a className="btn" href="#sessions">
              세션 현황 열기
            </a>
          }
        >
          등록한 AI 세션이 터미널에서 입력을 기다리거나, 두 세션이 같은 파일을 고치면 여기에 쌓인다. 권한 요청 중계와 위험 목록·표본 검토는 아직 서버가
          만들지 않는다.
        </EmptyState>
      );
    case 'rows':
      return (
        <>
          {groups.map((g) => (
            <div key={g.bucket} className="pane-grp" data-bucket={g.bucket}>
              <GroupHead mark={<Icon name={g.mark} />} label={g.label} count={g.items.length} aside={g.items.length === 0 ? groupNote(g) : undefined} />
              {g.items.length > 0 && (
                <ul className="pane-rows">
                  {g.items.map((i) => (
                    <li key={i.id}>
                      <InboxRow item={i} sessions={sessions} now={now} selected={i.id === selected} tabbable={i.id === tabbable} onPick={onPick} />
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
 * One row: the kind as a shape and a word, then what waits (the file of a conflict, the words or
 * the work of a waiting session), cut to a line. Under them how long it has waited, whether it
 * alerts at once, how often it came back, and the sessions it names with their branch.
 */
export function InboxRow({
  item,
  sessions,
  now,
  selected,
  tabbable,
  onPick,
}: {
  item: InboxItem;
  sessions: SessionView[] | null;
  now: number;
  selected: boolean;
  tabbable: boolean;
  onPick: (id: string) => void;
}) {
  const kind = kindOf(item);
  return (
    <button
      type="button"
      className="lrow"
      data-row={item.id}
      data-kind={item.kind}
      aria-current={selected ? 'true' : undefined}
      tabIndex={tabbable ? 0 : -1}
      onClick={() => onPick(item.id)}
    >
      <span className="lrow-l1">
        <Icon name={kind.mark} />
        <span className="lrow-state">{kind.label}</span>
        <span className={item.kind === 'notice' ? 'lrow-title ib-file' : 'lrow-title'}>{rowTitle(item, sessions)}</span>
      </span>
      <span className="lrow-sub">
        <span className="ib-age num">{waited(item.waiting_since, now)}</span>
        <span className={item.notify === 'immediate' ? 'ib-ntf is-now' : 'ib-ntf'}>{notifyLabel(item)}</span>
        {item.repeat_count > 0 && <span className="ib-rep num">반복 {item.repeat_count}회</span>}
        {itemSessions(item).map((s) => (
          <span key={s.id} className="ib-ses-ref">
            <code>{shortId(s.id)}</code>
            {s.branch && <span>{s.branch}</span>}
          </span>
        ))}
      </span>
    </button>
  );
}
