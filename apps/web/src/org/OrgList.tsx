import { Icon } from '../icons/Icon';
import { EmptyState } from '../ui/EmptyState';
import { GroupHead, RowSkeleton } from '../ui/Group';
import { ListPane, type ListHead } from '../ui/ListPane';
import { tabStop } from '../ui/rowKeys';
import { KIND_ICONS, KIND_LABELS, rowOrder, type Entry, type ListState, type OrgGroup } from './orgView';

type Props = {
  state: ListState;
  groups: OrgGroup[];
  head: ListHead;
  selected: string | null;
  canManage: boolean;
  onSelect: (id: string) => void;
  onPick: (id: string) => void;
  onOpen: (id: string) => void;
  /** Opens the add form, for a kind when the button names one. */
  onNew: (kind?: 'team') => void;
  onReload: () => void;
};

/**
 * The organisation's items, grouped by kind: the organisation, people, teams, products, projects,
 * repositories and environments. A one-person organisation folds the kinds it does not use into one
 * quiet row. The keys are the other lists': the arrows, Home and End move the selection, Enter opens it.
 */
export function OrgList({ state, groups, head, selected, canManage, onSelect, onPick, onOpen, onNew, onReload }: Props) {
  const order = rowOrder(groups);
  const tabbable = tabStop(order, selected);
  return (
    <ListPane
      title="조직 목록"
      head={head}
      by="종류로 묶음"
      action={
        canManage ? (
          <button type="button" className="btn btn-primary" onClick={() => onNew()}>
            <Icon name="plus" />더하기
          </button>
        ) : undefined
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
          title="조직을 읽지 못했다"
          action={
            <button type="button" className="btn" onClick={onReload}>
              다시 읽기
            </button>
          }
        >
          {state.message}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 읽는다.
        </EmptyState>
      ) : (
        groups.map((g) => (
          <div key={g.kind} className="pane-grp" data-group={g.kind}>
            {g.kind === 'rest' ? (
              <p className="org-rest">
                <Icon name={g.icon} />
                <span>{g.empty}</span>
                {canManage && (
                  <button type="button" className="btn btn-quiet" onClick={() => onNew('team')}>
                    <Icon name="plus" />팀 더하기
                  </button>
                )}
              </p>
            ) : (
              <>
                {g.kind !== 'org' && <GroupHead mark={<Icon name={g.icon} />} label={g.label} count={g.entries.length} />}
                {g.entries.length === 0 ? (
                  <EmptyState kind="empty" title={g.empty} />
                ) : (
                  <ul className="pane-rows">
                    {g.entries.map((e) => (
                      <li key={e.id}>
                        <OrgRow entry={e} selected={e.id === selected} tabbable={e.id === tabbable} onPick={onPick} />
                      </li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </div>
        ))
      )}
    </ListPane>
  );
}

/** One row: the icon and the name (the group head names the kind), then what it holds; archived and inactive rows read quieter. */
export function OrgRow({ entry: e, selected, tabbable, onPick }: { entry: Entry; selected: boolean; tabbable: boolean; onPick: (id: string) => void }) {
  return (
    <button
      type="button"
      className={e.archived ? 'lrow org-off' : 'lrow'}
      data-row={e.id}
      aria-current={selected ? 'true' : undefined}
      tabIndex={tabbable ? 0 : -1}
      onClick={() => onPick(e.id)}
    >
      <span className="lrow-l1">
        <Icon name={KIND_ICONS[e.kind]} />
        {e.kind === 'org' && <span className="lrow-state">{KIND_LABELS[e.kind]}</span>}
        <span className="lrow-title">{e.name}</span>
      </span>
      <span className="lrow-sub">
        {e.archived && e.kind !== 'person' && <span className="org-tag">보관됨</span>}
        {e.sub}
      </span>
    </button>
  );
}
