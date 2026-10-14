import { useId, useState, type ReactNode } from 'react';
import { Icon } from '../icons/Icon';
import type { OrgView } from '../orgApi';
import type { Change, Where } from './orgChange';
import { ENV_LABELS, ROLE_LABELS, ROLE_NOTES, ROLE_ORDER, type LinkOption } from './orgView';

/*
 * The small forms of the organisation detail: each changes one thing and shows the server's refusal
 * right under itself. A control that cannot be used says why beside a lock, as the other screens do.
 */

export type Run = (change: Change) => Promise<void>;

export type Refusal = { where: Where; message: string } | null;

/** The server's refusal of this control, under it. */
export function Refused({ refusal, where }: { refusal: Refusal; where: Where }) {
  if (refusal === null || refusal.where !== where) return null;
  return (
    <p role="alert" className="wd-refused org-refused">
      <Icon name="alert" />
      <span>{refusal.message}</span>
    </p>
  );
}

/** Why a control is locked, beside a lock. */
export function Locked({ children }: { children: ReactNode }) {
  return (
    <p className="wd-why">
      <Icon name="lock" />
      <span>{children}</span>
    </p>
  );
}

function useBusy(run: Run) {
  const [busy, setBusy] = useState(false);
  return {
    busy,
    go: (change: Change) => {
      setBusy(true);
      void run(change).finally(() => setBusy(false));
    },
  };
}

/** The name, saved when it changed. [locked] is why it cannot change, shown instead of the field. */
export function NameControl({ name, label, locked, run, refusal }: { name: string; label: string; locked?: string | null; run: Run; refusal: Refusal }) {
  const [value, setValue] = useState(name);
  const { busy, go } = useBusy(run);
  if (locked) return <Locked>{locked}</Locked>;
  const same = value.trim() === name || value.trim() === '';
  return (
    <>
      <form
        className="wd-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (!same && !busy) go({ kind: 'rename', name: value });
        }}
      >
        <label>
          {label}
          <input className="field" value={value} maxLength={100} onChange={(e) => setValue(e.target.value)} />
        </label>
        <button type="submit" className="btn" disabled={same || busy} aria-busy={busy || undefined}>
          이름 바꾸기
        </button>
      </form>
      <Refused refusal={refusal} where="rename" />
    </>
  );
}

/** A person's roles as checks with what each lets them do. The owner's are all checked and locked. */
export function RolesControl({ roles, owner, canManage, run, refusal }: { roles: string[]; owner: boolean; canManage: boolean; run: Run; refusal: Refusal }) {
  const [picked, setPicked] = useState<string[]>(owner ? ROLE_ORDER : roles);
  const { busy, go } = useBusy(run);
  const locked = owner || !canManage;
  const same = picked.length === roles.length && picked.every((r) => roles.includes(r));
  const legendId = useId();
  return (
    <>
      <fieldset className="hf-tools org-roles" aria-labelledby={legendId}>
        <legend id={legendId} className="sr">
          역할
        </legend>
        {ROLE_ORDER.map((r) => (
          <label key={r} className="check">
            <input
              type="checkbox"
              checked={picked.includes(r)}
              disabled={locked || busy}
              onChange={(e) => setPicked(e.target.checked ? [...picked, r] : picked.filter((x) => x !== r))}
            />
            <span>
              {ROLE_LABELS[r]} <small>{ROLE_NOTES[r]}</small>
            </span>
          </label>
        ))}
      </fieldset>
      {canManage && !owner && (
        <div className="wd-acts">
          <button type="button" className="btn" disabled={same || busy} aria-busy={busy || undefined} onClick={() => go({ kind: 'roles', roles: ROLE_ORDER.filter((r) => picked.includes(r)) })}>
            역할 저장하기
          </button>
        </div>
      )}
      {owner && <Locked>조직 소유자는 늘 모든 역할을 갖는다. 소유자의 역할과 상태는 바꾸지 않는다</Locked>}
      <Refused refusal={refusal} where="roles" />
    </>
  );
}

/** Active or inactive; the owner stays active. */
export function StatusControl({ status, owner, run, refusal }: { status: 'active' | 'inactive'; owner: boolean; run: Run; refusal: Refusal }) {
  const { busy, go } = useBusy(run);
  const next = status === 'active' ? 'inactive' : 'active';
  return (
    <>
      <div className="wd-acts">
        <button type="button" className="btn" disabled={owner || busy} aria-busy={busy || undefined} onClick={() => go({ kind: 'status', status: next })}>
          {status === 'active' ? '비활성으로 바꾸기' : '다시 활동 중으로'}
        </button>
      </div>
      {!owner && status === 'active' && <p className="org-note">비활성으로 바꿔도 그 사람의 기록은 남는다. 그 사람은 조직의 일을 할 수 없다.</p>}
      <Refused refusal={refusal} where="status" />
    </>
  );
}

/** A team's or project's people, as checks. */
export function MembersControl({ org, members, run, refusal }: { org: OrgView; members: string[]; run: Run; refusal: Refusal }) {
  const [picked, setPicked] = useState(members);
  const { busy, go } = useBusy(run);
  const same = picked.length === members.length && picked.every((m) => members.includes(m));
  return (
    <>
      <fieldset className="hf-tools">
        <legend>사람 고르기</legend>
        {org.people.map((p) => (
          <label key={p.id} className="check">
            <input type="checkbox" checked={picked.includes(p.id)} disabled={busy} onChange={(e) => setPicked(e.target.checked ? [...picked, p.id] : picked.filter((x) => x !== p.id))} />
            <span>
              {p.display_name} {p.status === 'inactive' && <small>비활성</small>}
            </span>
          </label>
        ))}
      </fieldset>
      <div className="wd-acts">
        <button type="button" className="btn" disabled={same || busy} aria-busy={busy || undefined} onClick={() => go({ kind: 'members', ids: picked })}>
          사람 저장하기
        </button>
      </div>
      <Refused refusal={refusal} where="members" />
    </>
  );
}

/** Adds a link the server takes, picked from what is not linked yet. */
export function LinkControl({ options, run, refusal }: { options: LinkOption[]; run: Run; refusal: Refusal }) {
  const [pick, setPick] = useState('');
  const { busy, go } = useBusy(run);
  if (options.length === 0) return <p className="dt-none">더 이을 수 있는 항목이 없다.</p>;
  const chosen = options.find((o) => `${o.fromId}>${o.toId}` === pick);
  return (
    <>
      <form
        className="wd-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (chosen && !busy) go({ kind: 'link', fromId: chosen.fromId, toId: chosen.toId });
        }}
      >
        <label>
          이을 항목
          <span className="select">
            <select value={pick} onChange={(e) => setPick(e.target.value)}>
              <option value="">고른다</option>
              {options.map((o) => (
                <option key={`${o.fromId}>${o.toId}`} value={`${o.fromId}>${o.toId}`}>
                  {o.label}
                </option>
              ))}
            </select>
            <Icon name="chevron-down" className="chev" />
          </span>
        </label>
        <button type="submit" className="btn" disabled={!chosen || busy} aria-busy={busy || undefined}>
          잇기
        </button>
      </form>
      <Refused refusal={refusal} where="link" />
    </>
  );
}

/** Archives an item or brings it back; nothing is deleted. */
export function ArchiveControl({ archived, run, refusal }: { archived: boolean; run: Run; refusal: Refusal }) {
  const { busy, go } = useBusy(run);
  return (
    <>
      <div className="wd-acts">
        <button type="button" className="btn" disabled={busy} aria-busy={busy || undefined} onClick={() => go({ kind: 'archive', archived: !archived })}>
          {archived ? '되살리기' : '보관하기'}
        </button>
      </div>
      {!archived && <p className="org-note">보관한 항목은 지우지 않는다. 새 연결에 나오지 않을 뿐이다.</p>}
      <Refused refusal={refusal} where="archive" />
    </>
  );
}

/** An environment's kind (a fixed list) or a project's kind (the organisation's own words). */
export function KindControl({ value, envKinds, run, refusal }: { value: string; envKinds: string[] | null; run: Run; refusal: Refusal }) {
  const [next, setNext] = useState(value);
  const { busy, go } = useBusy(run);
  return (
    <>
      <form
        className="wd-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (next !== value && !busy) go({ kind: 'itemKind', value: next });
        }}
      >
        <label>
          {envKinds ? '환경 종류' : '프로젝트 종류(BE·FE·인프라 같은 조직의 말)'}
          {envKinds ? (
            <span className="select">
              <select value={next} onChange={(e) => setNext(e.target.value)}>
                {envKinds.map((k) => (
                  <option key={k} value={k}>
                    {ENV_LABELS[k] ?? k}
                  </option>
                ))}
              </select>
              <Icon name="chevron-down" className="chev" />
            </span>
          ) : (
            <input className="field" value={next} maxLength={50} onChange={(e) => setNext(e.target.value)} />
          )}
        </label>
        <button type="submit" className="btn" disabled={next === value || busy} aria-busy={busy || undefined}>
          종류 바꾸기
        </button>
      </form>
      <Refused refusal={refusal} where="itemKind" />
    </>
  );
}

/** A repository's remote: host and path only; the server drops anything that could hold a secret. */
export function RemoteControl({ value, run, refusal }: { value: string; run: Run; refusal: Refusal }) {
  const [next, setNext] = useState(value);
  const { busy, go } = useBusy(run);
  return (
    <>
      <form
        className="wd-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (next.trim() !== value && !busy) go({ kind: 'remote', value: next });
        }}
      >
        <label>
          원격 주소
          <input className="field" value={next} maxLength={500} placeholder="github.com/나/저장소" onChange={(e) => setNext(e.target.value)} />
        </label>
        <button type="submit" className="btn" disabled={next.trim() === value || busy} aria-busy={busy || undefined}>
          주소 바꾸기
        </button>
      </form>
      <Refused refusal={refusal} where="remote" />
    </>
  );
}
