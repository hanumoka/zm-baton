import { Icon } from '../icons/Icon';
import { shortId, waited } from '../inboxModel';
import type { InboxItem, SessionView } from '../sessionsApi';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, GroupSubHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import { filePath, groupAside, itemSessions, kindOf, notifyLabel, reviewFiles, rowOrder, rowTitle, type InboxGroup, type ListState } from './inboxView';
import { RelayState } from './RelayDetail';
import { isRelay, relayActive } from './relayView';

type Props = {
  state: ListState;
  groups: InboxGroup[];
  head: ListHead;
  selected: string | null;
  /** Live sessions, to show what a waiting session works on. */
  sessions: SessionView[] | null;
  now: number;
  /** Focus mode is on: the relaying buckets say that new requests go to the terminal. */
  focusOn?: boolean;
  /** Whether the server relays at all (ZM_BATON_RELAY); null until it said. Off: bucket 1 says so and why. */
  relayOn?: boolean | null;
  /** The arrow keys moved the selection. */
  onSelect: (id: string) => void;
  /** A click or tap on a row: select it (wide) or open it as a sheet (narrow). */
  onPick: (id: string) => void;
  /** Enter on a row: the detail takes the focus (wide) or opens as a sheet (narrow). */
  onOpen: (id: string) => void;
  onReload: () => void;
  /** What the last verdict did, shown above the groups until the next one. */
  done?: { text: string; href: string | null } | null;
};

/**
 * What waits for me, in the contract's five buckets and their fixed order; in each, the longest
 * wait first. A bucket the server fills shows a short line when it is clear; one it does not fill
 * yet says so in its head, so an empty box never reads as 「nothing came」. The keys are the work
 * list's: the arrows, Home and End move the selection, Enter opens it.
 */
export function InboxList({ state, groups, head, selected, sessions, now, focusOn = false, relayOn = null, onSelect, onPick, onOpen, onReload, done = null }: Props) {
  const order = rowOrder(groups);
  return (
    <ListPane title="나를 기다리는 것 목록" head={head} by="급한 순서로 묶음" order={order} onSelect={onSelect} onOpen={onOpen}>
      {done && (
        <p className="ib-done" role="status">
          <Icon name="done" />
          <span>
            {done.text}.{' '}
            {done.href && (
              <a href={done.href}>
                작업 열기
              </a>
            )}
          </span>
        </p>
      )}
      <ListBody
        state={state}
        groups={groups}
        selected={selected}
        tabbable={tabStop(order, selected)}
        sessions={sessions}
        now={now}
        focusOn={focusOn}
        relayOn={relayOn}
        onPick={onPick}
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
  sessions,
  now,
  focusOn = false,
  relayOn = null,
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
          등록한 AI 세션이 권한을 묻거나 선택형 질문을 내거나 터미널에서 입력을 기다리면, 두 세션이 같은 파일을 고치면, 맡긴 AI 실행의 결과가 위험 경로에
          걸리거나 표본으로 뽑히면 여기에 쌓인다.
        </EmptyState>
      );
    case 'rows':
      return (
        <>
          {groups.map((g) => {
            // A relay that ended stays a while under its own line, so the person sees how it went; it no longer counts.
            const waiting = g.items.filter(relayActive);
            const ended = g.items.filter((i) => !relayActive(i));
            const rows = (list: InboxItem[]) => (
              <ul className="pane-rows">
                {list.map((i) => (
                  <li key={i.id}>
                    <InboxRow item={i} sessions={sessions} now={now} selected={i.id === selected} tabbable={i.id === tabbable} onPick={onPick} />
                  </li>
                ))}
              </ul>
            );
            return (
              <div key={g.bucket} className="pane-grp" data-bucket={g.bucket}>
                <GroupHead
                  mark={<Icon name={g.mark} />}
                  label={g.label}
                  count={waiting.length}
                  aside={groupAside(g, waiting.length, focusOn, relayOn)}
                />
                {waiting.length > 0 && rows(waiting)}
                {ended.length > 0 && (
                  <>
                    <GroupSubHead label="방금 끝난 중계" count={ended.length} />
                    {rows(ended)}
                  </>
                )}
              </div>
            );
          })}
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
  const files = reviewFiles(item);
  return (
    <button
      type="button"
      className="lrow"
      data-row={item.id}
      data-kind={item.kind}
      data-state={item.state ?? 'open'}
      aria-current={selected ? 'true' : undefined}
      tabIndex={tabbable ? 0 : -1}
      onClick={() => onPick(item.id)}
    >
      <span className="lrow-l1">
        <Icon name={kind.mark} />
        <span className="lrow-state">{kind.label}</span>
        <span className={filePath(item) ? 'lrow-title ib-file' : 'lrow-title'}>{rowTitle(item, sessions)}</span>
      </span>
      <span className="lrow-sub">
        <span className="ib-age num">{waited(item.waiting_since, now)}</span>
        {isRelay(item) && <RelayState item={item} now={now} />}
        {relayActive(item) && <span className={item.notify === 'immediate' ? 'ib-ntf is-now' : 'ib-ntf'}>{notifyLabel(item)}</span>}
        {item.repeat_count > 0 && <span className="ib-rep num">반복 {item.repeat_count}회</span>}
        {item.promoted_at && <span className="ib-ntf is-now">오래 기다려 위로 올림</span>}
        {files && (
          <span className="ib-rv-file">
            {files.first}
            {files.more > 0 && ` 외 ${files.more}개`}
          </span>
        )}
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
