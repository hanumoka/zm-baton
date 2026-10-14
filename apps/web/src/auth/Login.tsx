import { useState, type FormEvent } from 'react';
import { authApi, passwordProblem, type AuthState } from '../authApi';
import { Icon } from '../icons/Icon';

type Props = {
  /** What the server said before anyone signed in: whether an owner still needs a first password. */
  state: AuthState;
  onSignedIn: (state: AuthState) => void;
};

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * The sign-in screen of a server in `required` mode (docs/stage1/auth.md): a login name and a
 * password. While no owner has a password, it opens on 「처음 비밀번호 정하기」 with the one-time setup
 * token the server wrote to its log. Nothing else of the screen is drawn until someone signs in.
 */
export function Login({ state, onSignedIn }: Props) {
  const [mode, setMode] = useState<'login' | 'setup'>(state.setup_pending ? 'setup' : 'login');
  return (
    <main className="au-page">
      <div className="au-col">
        <p className="au-brand">
          <Icon name="lock" />
          <span>zm-baton</span>
        </p>
        {mode === 'login' ? <SignIn onSignedIn={onSignedIn} /> : <Setup onSignedIn={onSignedIn} />}
        <p className="au-switch">
          {mode === 'login' ? (
            <>
              <span>아직 비밀번호가 없거나 잊었다.</span>
              <button type="button" className="btn btn-quiet" onClick={() => setMode('setup')}>
                설정 토큰으로 비밀번호 정하기
              </button>
            </>
          ) : (
            <>
              <span>이미 비밀번호가 있다.</span>
              <button type="button" className="btn btn-quiet" onClick={() => setMode('login')}>
                로그인으로 돌아가기
              </button>
            </>
          )}
        </p>
      </div>
    </main>
  );
}

function SignIn({ onSignedIn }: { onSignedIn: (s: AuthState) => void }) {
  const [login, setLogin] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const missing = login.trim() === '' ? '아이디를 넣는다' : password === '' ? '비밀번호를 넣는다' : null;

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (missing !== null || busy) return;
    setBusy(true);
    try {
      onSignedIn(await authApi.login(login.trim(), password));
    } catch (err) {
      setError(message(err));
      setPassword('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="au-form wn" onSubmit={submit} aria-labelledby="au-title">
      <h1 id="au-title" className="dt-title">
        로그인
      </h1>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      <label>
        <span>아이디</span>
        <input className="field" value={login} onChange={(e) => setLogin(e.target.value)} autoComplete="username" autoFocus maxLength={50} />
      </label>
      <label>
        <span>비밀번호</span>
        <input
          className="field"
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
          aria-invalid={error !== null || undefined}
        />
      </label>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={missing !== null || busy} aria-busy={busy || undefined}>
          로그인
        </button>
        {missing !== null && <span className="ctl-why">{missing}</span>}
      </div>
    </form>
  );
}

function Setup({ onSignedIn }: { onSignedIn: (s: AuthState) => void }) {
  const [token, setToken] = useState('');
  const [login, setLogin] = useState('owner');
  const [password, setPassword] = useState('');
  const [again, setAgain] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const problem = token.trim() === '' ? '설정 토큰을 넣는다' : login.trim().length < 2 ? '아이디는 2자 이상이다' : passwordProblem(password, again, login);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (problem !== null || busy) return;
    setBusy(true);
    try {
      onSignedIn(await authApi.setup(token.trim(), login.trim(), password));
    } catch (err) {
      setError(message(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="au-form wn" onSubmit={submit} aria-labelledby="au-title">
      <h1 id="au-title" className="dt-title">
        처음 비밀번호 정하기
      </h1>
      <p className="wn-lead">
        서버를 띄운 PC의 서버 기록에서 한 번 쓰는 설정 토큰(<code>zmbt_</code>로 시작)을 찾아 넣는다. Docker로 띄웠으면{' '}
        <code>docker compose -f deploy/compose/compose.yaml logs server</code>에 있다. 토큰은 한 번만 쓰이고 24시간 뒤나 서버를 다시 띄우면 바뀐다.
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      <label>
        <span>설정 토큰</span>
        <input className="field mono" value={token} onChange={(e) => setToken(e.target.value)} autoComplete="off" spellCheck={false} autoFocus />
      </label>
      <label>
        <span>아이디</span>
        <input className="field" value={login} onChange={(e) => setLogin(e.target.value)} autoComplete="username" maxLength={50} />
      </label>
      <label>
        <span>새 비밀번호(12자 이상)</span>
        <input className="field" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" />
      </label>
      <label>
        <span>새 비밀번호 한 번 더</span>
        <input className="field" type="password" value={again} onChange={(e) => setAgain(e.target.value)} autoComplete="new-password" />
      </label>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={problem !== null || busy} aria-busy={busy || undefined}>
          비밀번호 정하고 로그인
        </button>
        {problem !== null && <span className="ctl-why">{problem}</span>}
      </div>
    </form>
  );
}
