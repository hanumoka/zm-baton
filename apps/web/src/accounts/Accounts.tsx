import { useRef, useState } from 'react';
import { accountsApi, type Account, type AccountDetail } from '../accountsApi';
import { useNarrow } from '../shell/useNarrow';
import { DetailPane } from '../ui/DetailPane';
import { EmptyState } from '../ui/EmptyState';
import { RowSkeleton } from '../ui/Group';
import { focusRow } from '../ui/rowKeys';
import { useAddress } from '../ui/useAddress';
import { useSheet } from '../ui/useSheet';
import { useShownRow } from '../ui/useShownRow';
import { usePoll } from '../usePoll';
import { ApiError } from '../workApi';
import { AccountDetailView, type AccountActions } from './AccountDetail';
import { AccountForm } from './AccountForm';
import { AccountList } from './AccountList';
import { changedFields, DEFAULT_DRAFT, draftFrom, groupAccounts, KIND_LABELS, listState, readView, registerFields, rowOrder, writeView, type AccountDraft } from './accountView';

type Props = {
  onError: (e: unknown) => void;
  onRead: () => void;
};

/** The detail pane shows an account, or a form: a new account or a change of one. */
type Pane = { kind: 'detail' } | { kind: 'new' } | { kind: 'edit'; base: Account; start: AccountDraft };

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * AI accounts (FN-0501) with their usage and limits (FN-0409), in the other screens' grammar: the
 * list and the detail side by side, the list grouped by whether an account can take work now. The
 * selected account lives in the address. The screen sits in the sidebar's settings place: the limits
 * are organisation settings (G39.4), and a settings screen will hold this as one of its parts.
 */
export function Accounts({ onError, onRead }: Props) {
  const narrow = useNarrow();
  const [view, setView, , navigation] = useAddress('accounts', readView, writeView);
  const [items, setItems] = useState<Account[] | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [detail, setDetail] = useState<AccountDetail | null>(null);
  const [pane, setPane] = useState<Pane>({ kind: 'detail' });
  const [actionError, setActionError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const sheet = useSheet(view.selected, navigation);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const root = useRef<HTMLElement>(null);
  const { selected } = view;

  usePoll(
    async (alive, signal) => {
      const list = await accountsApi.list(signal);
      if (!alive()) return;
      setItems(list);
      setListError(null);
      onRead();
    },
    10000,
    [refresh],
    (e) => {
      setListError(message(e));
      onError(e);
    },
  );

  const showDetail = pane.kind === 'detail' && (!narrow || sheet.open);
  usePoll(
    async (alive, signal) => {
      if (selected === null || !showDetail) return;
      const d = await accountsApi.detail(selected, signal);
      if (alive()) setDetail(d);
    },
    10000,
    [selected, showDetail, refresh],
    (e) => {
      if (e instanceof ApiError && e.status === 404) setView((v) => (v.selected === selected ? { selected: null } : v));
      else onError(e);
    },
  );

  const groups = groupAccounts(items ?? []);
  const order = rowOrder(groups);
  const state = listState(items, listError);
  const current = selected === null ? null : (items?.find((a) => a.id === selected) ?? (detail?.account.id === selected ? detail.account : null));

  useShownRow({
    fill: !narrow && pane.kind === 'detail',
    follow: !narrow,
    selected,
    order,
    filterKey: 'accounts',
    settled: items !== null,
    choose: (id) => setView({ selected: id }),
  });

  function select(id: string) {
    setView((v) => (v.selected === id ? v : { selected: id }));
    setActionError(null);
    setPane({ kind: 'detail' });
  }

  function open(id: string) {
    select(id);
    if (narrow) sheet.show();
    else requestAnimationFrame(() => titleRef.current?.focus());
  }

  function openPane(next: Pane) {
    setActionError(null);
    setPane(next);
    if (narrow && !sheet.open) sheet.show();
  }

  function closePane() {
    setActionError(null);
    setPane({ kind: 'detail' });
    if (narrow && current === null) sheet.hide();
  }

  /** What the server returned for the account just changed: shown at once, the list read again. */
  function settle(d: AccountDetail) {
    setDetail(d);
    setActionError(null);
    setRefresh((r) => r + 1);
    setView({ selected: d.account.id });
    setPane({ kind: 'detail' });
  }

  function refused(e: unknown) {
    setActionError(message(e));
    setRefresh((r) => r + 1);
  }

  const shown = detail !== null && detail.account.id === selected ? detail : null;
  const actions: AccountActions | undefined = shown
    ? {
        edit: () => openPane({ kind: 'edit', base: shown.account, start: draftFrom(shown.account) }),
        run: async (name) => {
          const a = shown.account;
          try {
            const call = { disable: accountsApi.disable, enable: accountsApi.enable, resume_billing: accountsApi.resumeBilling }[name];
            settle(await call(a.id, a.version));
          } catch (e) {
            refused(e);
          }
        },
      }
    : undefined;

  async function register(draft: AccountDraft) {
    try {
      settle(await accountsApi.register(registerFields(draft)));
    } catch (e) {
      refused(e);
    }
  }

  async function saveEdit(draft: AccountDraft) {
    if (pane.kind !== 'edit') return;
    const fields = changedFields(pane.start, draft);
    try {
      settle(Object.keys(fields).length === 0 ? (shown ?? (await accountsApi.detail(pane.base.id))) : await accountsApi.update(pane.base.id, pane.base.version, fields));
    } catch (e) {
      refused(e);
    }
  }

  const paneTitle = { detail: null, new: '새 AI 계정', edit: 'AI 계정 고치기' }[pane.kind];
  const body =
    pane.kind === 'new' ? (
      <AccountForm mode="new" start={DEFAULT_DRAFT} onSubmit={register} onCancel={closePane} error={actionError} inSheet={narrow} />
    ) : pane.kind === 'edit' ? (
      <AccountForm key={`${pane.base.id}:${pane.base.version}`} mode="edit" start={pane.start} onSubmit={saveEdit} onCancel={closePane} error={actionError} inSheet={narrow} />
    ) : current ? (
      <AccountDetailView account={current} detail={detail} actions={actions} error={actionError} titleRef={titleRef} inSheet={narrow} />
    ) : selected !== null ? (
      <RowSkeleton rows={3} />
    ) : state.kind === 'rows' ? (
      <EmptyState kind="empty" title="고른 계정이 없다">
        목록에서 계정을 고르면 여기에 보인다.
      </EmptyState>
    ) : null;

  return (
    <main className="panes acc" ref={root}>
      <h1 className="sr">AI 계정</h1>
      <AccountList
        state={state}
        groups={groups}
        head={{ base: 'AI 계정', count: items === null ? null : items.length, narrowed: false }}
        selected={selected}
        onSelect={select}
        onPick={narrow ? open : select}
        onOpen={open}
        onNew={() => openPane({ kind: 'new' })}
        onReload={() => setRefresh((r) => r + 1)}
      />
      <DetailPane
        narrow={narrow}
        open={sheet.open}
        label={paneTitle ?? 'AI 계정 상세'}
        sheetTitle={paneTitle ?? (current ? KIND_LABELS[current.auth_kind] : 'AI 계정')}
        onClose={() => {
          sheet.hide();
          setPane({ kind: 'detail' });
        }}
        onBack={() => {
          if (pane.kind !== 'detail') setPane({ kind: 'detail' });
          focusRow(root.current, selected);
        }}
      >
        {body}
      </DetailPane>
    </main>
  );
}
