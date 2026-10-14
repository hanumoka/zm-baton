import { useState, type FormEvent } from 'react';
import { authApi, DEVICE_LOGIN_LABELS, normalCode, passwordProblem, type AuthState, type DeviceLoginView, type MyDevice } from '../authApi';
import { Icon } from '../icons/Icon';
import { usePoll } from '../usePoll';
import { at } from '../workModel';

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/** The `device_login` code in the address (`#me?device_login=BCDF-GHJK`), which `zm-baton login` shows. */
export function codeInAddress(hash: string): string | null {
  const q = hash.split('?')[1];
  return q ? normalCode(new URLSearchParams(q).get('device_login') ?? '') : null;
}

/** Drops the code from the address once it is dealt with, so a reload does not show it again. */
function forgetCode() {
  if (typeof window !== 'undefined') window.history.replaceState(window.history.state, '', '#me');
}

/** Who I am signed in as, sign-out and changing my password; drawn in `required` mode only. */
export function SignedIn({ auth, onAuthChanged }: { auth: AuthState; onAuthChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function logout() {
    setBusy(true);
    try {
      await authApi.logout();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
      onAuthChanged();
    }
  }
  return (
    <section className="dt-sec me-access" aria-labelledby="me-signin">
      <h2 id="me-signin" className="me-h">
        로그인
      </h2>
      <p className="me-note">
        아이디 <b>{auth.person?.login ?? auth.person?.id}</b>로 로그인했다.{auth.secure ? ' 이 연결은 TLS로 암호화된다.' : ''}
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      <div className="wd-acts">
        <button type="button" className="btn" onClick={() => void logout()} disabled={busy} aria-busy={busy || undefined}>
          로그아웃
        </button>
      </div>
      <details className="me-more">
        <summary>비밀번호 바꾸기</summary>
        <PasswordForm login={auth.person?.login ?? ''} />
      </details>
    </section>
  );
}

function PasswordForm({ login }: { login: string }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);
  const problem = current === '' ? '지금 비밀번호를 넣는다' : passwordProblem(next, again, login);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (problem !== null || busy) return;
    setBusy(true);
    try {
      const r = await authApi.changePassword(current, next);
      setDone(r.other_logins_ended > 0 ? `바꿨다. 다른 곳의 로그인 ${r.other_logins_ended}개를 끝냈다.` : '바꿨다.');
      setError(null);
      setCurrent('');
      setNext('');
      setAgain('');
    } catch (err) {
      setError(message(err));
      setDone(null);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="me-pw" onSubmit={submit}>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      {done && (
        <p role="status" className="me-done">
          {done}
        </p>
      )}
      <label>
        <span>지금 비밀번호</span>
        <input className="field" type="password" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" />
      </label>
      <label>
        <span>새 비밀번호(12자 이상)</span>
        <input className="field" type="password" value={next} onChange={(e) => setNext(e.target.value)} autoComplete="new-password" />
      </label>
      <label>
        <span>새 비밀번호 한 번 더</span>
        <input className="field" type="password" value={again} onChange={(e) => setAgain(e.target.value)} autoComplete="new-password" />
      </label>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={problem !== null || busy} aria-busy={busy || undefined}>
          비밀번호 바꾸기
        </button>
        {problem !== null && <span className="ctl-why">{problem}</span>}
      </div>
    </form>
  );
}

/**
 * A device login waiting for me (`zm-baton login` on a server in `required` mode shows its code and
 * this page). I compare the code, then let the device in or refuse it.
 */
export function DeviceApproval({ code }: { code: string }) {
  const [view, setView] = useState<DeviceLoginView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  usePoll(
    async (alive, signal) => {
      const v = await authApi.deviceLogin(code, signal);
      if (alive()) {
        setView(v);
        setError(null);
      }
    },
    5000,
    [code],
    (e) => setError(message(e)),
  );

  async function act(fn: (c: string) => Promise<DeviceLoginView>) {
    setBusy(true);
    try {
      setView(await fn(code));
      setError(null);
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }

  const pending = view?.state === 'pending';
  return (
    <section className="dt-sec me-approve" aria-labelledby="me-approve">
      <h2 id="me-approve" className="me-h">
        기기 연결 요청
      </h2>
      <p className="me-code num">{code}</p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      {view === null ? (
        error === null && <p className="me-note">요청을 읽는 중이다.</p>
      ) : (
        <>
          <p className="me-note">
            {view.device_id ? (
              <>
                내 기기 <b>{view.device_name ?? view.device_id}</b>가 새 기기 토큰을 달라고 한다(이 기기의 저장소와 기록은 그대로다).
              </>
            ) : (
              <>
                새 기기 <b>{view.name}</b>
                {view.os ? `(${view.os})` : ''}가 내 기기로 들어오려 한다.
              </>
            )}{' '}
            터미널에 보이는 코드가 위와 같을 때만 허락한다. {at(view.expires_at)}까지 쓸 수 있다.
          </p>
          <p className="me-note">
            상태: <b>{DEVICE_LOGIN_LABELS[view.state]}</b>
          </p>
          <div className="wd-acts">
            {pending ? (
              <>
                <button type="button" className="btn btn-primary" onClick={() => void act(authApi.approve)} disabled={busy} aria-busy={busy || undefined}>
                  연결 허락
                </button>
                <button type="button" className="btn" onClick={() => void act(authApi.deny)} disabled={busy}>
                  거절
                </button>
              </>
            ) : (
              <button type="button" className="btn btn-quiet" onClick={forgetCode}>
                이 요청 닫기
              </button>
            )}
          </div>
        </>
      )}
    </section>
  );
}

/** My devices: whether each holds a device token, when it was seen, and revoking a lost one. */
export function MyDevices({ auth, onEnterCode }: { auth: AuthState; onEnterCode: (code: string) => void }) {
  const [devices, setDevices] = useState<MyDevice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [code, setCode] = useState('');

  usePoll(
    async (alive, signal) => {
      const list = await authApi.devices(signal);
      if (alive()) {
        setDevices(list);
        setError(null);
      }
    },
    30_000,
    [refresh],
    (e) => setError(message(e)),
  );

  const live = (devices ?? []).filter((d) => d.revoked_at === null);
  const code8 = normalCode(code);
  return (
    <section className="dt-sec me-access" aria-labelledby="me-devices">
      <h2 id="me-devices" className="me-h">
        내 기기 <span className="n num">{live.length}</span>
      </h2>
      <p className="me-note">
        <code>zm-baton login</code>으로 등록한 PC다. 기기 토큰은 그 PC의 zm-baton 설정 폴더에만 있다. 잃어버린 기기는 폐기하면 토큰이 멈추고 그 기기의 세션이 끝난다.
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      {devices === null ? (
        error === null && <p className="dt-none">읽는 중이다.</p>
      ) : devices.length === 0 ? (
        <p className="dt-none">
          등록한 기기가 없다. 그 PC에서 <code>zm-baton login</code>을 실행한다.
        </p>
      ) : (
        <ul className="me-rows">
          {devices.map((d) => (
            <DeviceRow key={`${d.id}:${d.revoked_at ?? ''}`} device={d} required={auth.mode === 'required'} onChanged={() => setRefresh((r) => r + 1)} />
          ))}
        </ul>
      )}
      {auth.mode === 'required' && (
        <form
          className="me-code-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (code8 !== null) {
              onEnterCode(code8);
              setCode('');
            }
          }}
        >
          <label>
            <span>
              <code>zm-baton login</code>이 보여 준 코드
            </span>
            <input className="field mono" value={code} onChange={(e) => setCode(e.target.value)} placeholder="예: BCDF-GHJK" maxLength={12} autoComplete="off" spellCheck={false} />
          </label>
          <button type="submit" className="btn" disabled={code8 === null}>
            요청 보기
          </button>
          {code8 === null && <span className="ctl-why">코드 여덟 글자를 넣는다(대시는 없어도 된다)</span>}
        </form>
      )}
    </section>
  );
}

export function DeviceRow({ device: d, required, onChanged }: { device: MyDevice; required: boolean; onChanged: () => void }) {
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const revoked = d.revoked_at !== null;
  const state = revoked ? '폐기함' : d.has_token ? '기기 토큰 있음' : '기기 토큰 없음';
  const hint = revoked || d.has_token
    ? null
    : required
      ? '이 서버가 받지 않는다. 그 PC에서 zm-baton login을 실행한다'
      : 'required로 바꾸기 전에 그 PC에서 zm-baton login을 실행한다';

  async function revoke() {
    setBusy(true);
    try {
      await authApi.revoke(d.id);
      setError(null);
      onChanged();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
      setConfirm(false);
    }
  }

  return (
    <li className={revoked ? 'me-dev is-revoked' : 'me-dev'}>
      <span className="me-l1">
        <span className="me-title">{d.name}</span>
        <span className={d.has_token || revoked ? 'me-tag' : 'me-tag me-warn'}>{state}</span>
      </span>
      <span className="me-sub">
        {[d.os, `등록 ${at(d.created_at)}`, d.last_seen_at ? `마지막 신호 ${at(d.last_seen_at)}` : null, d.revoked_at ? `폐기 ${at(d.revoked_at)}` : null]
          .filter(Boolean)
          .join(', ')}{' '}
        <code>{d.id}</code>
      </span>
      {hint && <span className="me-sub">{hint}</span>}
      {error && <span className="ctl-err">{error}</span>}
      {!revoked && (
        <span className="me-dev-act">
          {confirm ? (
            <>
              <span>토큰이 멈추고 이 기기의 세션이 끝난다. 되돌릴 수 없다.</span>
              <button type="button" className="btn" onClick={() => void revoke()} disabled={busy} aria-busy={busy || undefined}>
                폐기하기
              </button>
              <button type="button" className="btn btn-quiet" onClick={() => setConfirm(false)}>
                그만두기
              </button>
            </>
          ) : (
            <button type="button" className="btn btn-quiet" onClick={() => setConfirm(true)}>
              폐기하기
            </button>
          )}
        </span>
      )}
    </li>
  );
}
