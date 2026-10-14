import { useRef, useState } from 'react';
import { orgApi, type OrgView } from '../orgApi';
import { useNarrow } from '../shell/useNarrow';
import { DetailPane } from '../ui/DetailPane';
import { EmptyState } from '../ui/EmptyState';
import { focusRow } from '../ui/rowKeys';
import { useAddress } from '../ui/useAddress';
import { useSheet } from '../ui/useSheet';
import { useShownRow } from '../ui/useShownRow';
import { usePoll } from '../usePoll';
import { OrgDetail } from './OrgDetail';
import { OrgForm } from './OrgForm';
import { OrgList } from './OrgList';
import { addedId, addFrom, applyChange, type Change } from './orgChange';
import type { Refusal } from './OrgControls';
import { groupOrg, KIND_LABELS, kindOf, listState, newDraft, readView, rowOrder, writeView, type NewDraft } from './orgView';

type Props = {
  onError: (e: unknown) => void;
  onRead: () => void;
};

type Pane = { kind: 'detail' } | { kind: 'new'; start: NewDraft };

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * The organisation (FN-0101, FN-0104): its people with their roles, teams, products, projects,
 * repositories and environments, and how they hang together, in the other screens' grammar. A
 * one-person organisation sees the few parts it uses; adding a person or a team widens the list. The
 * selected row lives in the address. The screen sits in the sidebar's settings place; only an
 * operator changes the structure, and the server says so when anyone else tries.
 */
export function Org({ onError, onRead }: Props) {
  const narrow = useNarrow();
  const [view, setView, , navigation] = useAddress('org', readView, writeView);
  const [org, setOrg] = useState<OrgView | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [pane, setPane] = useState<Pane>({ kind: 'detail' });
  const [refusal, setRefusal] = useState<Refusal>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const sheet = useSheet(view.selected, navigation);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const root = useRef<HTMLElement>(null);
  const { selected } = view;

  usePoll(
    async (alive, signal) => {
      const next = await orgApi.view(signal);
      if (!alive()) return;
      setOrg(next);
      setListError(null);
      onRead();
    },
    30000,
    [refresh],
    (e) => {
      setListError(message(e));
      onError(e);
    },
  );

  const groups = org ? groupOrg(org) : [];
  const order = rowOrder(groups);
  const state = listState(org, listError);
  const shownId = selected !== null && org !== null && kindOf(org, selected) !== null ? selected : null;

  useShownRow({
    fill: !narrow && pane.kind === 'detail',
    follow: !narrow,
    selected: shownId,
    order,
    filterKey: 'org',
    settled: org !== null,
    choose: (id) => setView({ selected: id }),
  });

  function select(id: string) {
    setView((v) => (v.selected === id ? v : { selected: id }));
    setRefusal(null);
    setPane({ kind: 'detail' });
  }

  function open(id: string) {
    select(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  function openNew(kind?: NewDraft['kind']) {
    setFormError(null);
    setPane({ kind: 'new', start: newDraft(kind) });
    if (narrow && !sheet.open) sheet.show();
  }

  function closeNew() {
    setFormError(null);
    setPane({ kind: 'detail' });
    if (narrow && shownId === null) sheet.hide();
  }

  /** A change on the shown row: the structure the server returns shows at once; a refusal stays by its control. */
  async function run(change: Change) {
    if (org === null || shownId === null) return;
    const kind = kindOf(org, shownId);
    if (kind === null) return;
    try {
      setOrg(await applyChange(kind, shownId, change));
      setRefusal(null);
    } catch (e) {
      setRefusal({ where: change.kind, message: message(e) });
      setRefresh((r) => r + 1);
    }
  }

  async function add(d: NewDraft) {
    try {
      const next = await addFrom(d);
      const id = addedId(org, next, d.kind);
      setOrg(next);
      setFormError(null);
      setPane({ kind: 'detail' });
      if (id) setView({ selected: id });
    } catch (e) {
      setFormError(message(e));
    }
  }

  const kind = org && shownId ? kindOf(org, shownId) : null;
  const body =
    pane.kind === 'new' && org ? (
      <OrgForm start={pane.start} envKinds={org.environment_kinds} onSubmit={add} onCancel={closeNew} error={formError} inSheet={narrow} />
    ) : org && shownId ? (
      <OrgDetail org={org} id={shownId} run={run} refusal={refusal} titleRef={titleRef} inSheet={narrow} onOpen={open} />
    ) : state.kind === 'rows' ? (
      <EmptyState kind="empty" title="고른 항목이 없다">
        목록에서 고르면 여기에 보인다.
      </EmptyState>
    ) : null;

  return (
    <main className="panes org" ref={root}>
      <h1 className="sr">조직</h1>
      <OrgList
        state={state}
        groups={groups}
        head={{ base: '조직 항목', count: org === null ? null : order.length, narrowed: false }}
        selected={shownId}
        canManage={org?.can_manage === true}
        onSelect={select}
        onPick={narrow ? open : select}
        onOpen={open}
        onNew={(k) => openNew(k)}
        onReload={() => setRefresh((r) => r + 1)}
      />
      <DetailPane
        narrow={narrow}
        open={sheet.open}
        label={pane.kind === 'new' ? '조직에 더하기' : '조직 상세'}
        sheetTitle={pane.kind === 'new' ? '조직에 더하기' : kind ? KIND_LABELS[kind] : '조직'}
        onClose={() => {
          sheet.hide();
          setPane({ kind: 'detail' });
        }}
        onBack={() => {
          if (pane.kind !== 'detail') setPane({ kind: 'detail' });
          focusRow(root.current, shownId);
        }}
      >
        {body}
      </DetailPane>
    </main>
  );
}
