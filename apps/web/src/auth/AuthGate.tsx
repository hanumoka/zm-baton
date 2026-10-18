import { useEffect, useRef, useState, type ReactNode } from 'react';
import { authApi, type AuthState } from '../authApi';
import { onUnauthorized, setCsrf } from '../http';
import { ApiError } from '../workApi';
import { Login } from './Login';
import { ServerSetup } from '../server/ServerSetup';

/** What the screen assumes until the server says: `local`, the way stage 1 always worked. */
export const LOCAL_FALLBACK: AuthState = { mode: 'local', secure: false, person: null, csrf_token: null, setup_pending: false };

type Props = {
  /** The screen, drawn once the server's mode is known and, in `required` mode, someone is signed in. */
  children: (auth: AuthState, reread: () => void) => ReactNode;
};

/**
 * Asks the server how it signs people in before the screen draws (docs/stage1/auth.md):
 * 1. `local` (or a server from before sign-in, or one that does not answer yet): the screen as it was.
 * 2. `required` with nobody signed in: the sign-in screen.
 * 3. `required` and signed in: the screen, with the login's CSRF token kept for changing requests.
 * When any request is answered 401 later (the login ended), it asks again, which brings back the
 * sign-in screen. A server that does not answer is asked again every 5 seconds until it does.
 */
export function AuthGate({ children }: Props) {
  const [state, setState] = useState<AuthState | null>(null);
  const reading = useRef(false);
  const shownLogin = useRef(false);

  async function read() {
    if (reading.current) return;
    reading.current = true;
    try {
      const s = await authApi.state();
      setCsrf(s.csrf_token);
      setState(s);
    } catch (e) {
      // A server from before sign-in has no such endpoint: it is local.
      setState((old) => old ?? LOCAL_FALLBACK);
      if (!(e instanceof ApiError && e.status === 404)) setTimeout(() => void read(), 5000);
    } finally {
      reading.current = false;
    }
  }

  useEffect(() => {
    void read();
    // A 401 means the login ended; the sign-in screen itself expects them and does not ask again.
    return onUnauthorized(() => {
      if (!shownLogin.current) void read();
    });
  }, []);

  if (state === null) return <main className="au-page" aria-busy="true" aria-label="서버에 묻는 중" />;
  shownLogin.current = state.mode === 'required' && state.person === null;
  if (shownLogin.current) {
    return (
      <Login
        state={state}
        onSignedIn={(s) => {
          setCsrf(s.csrf_token);
          setState(s);
        }}
      />
    );
  }
  if (state.mode === 'required' && state.server_admin && state.server_setup_pending) {
    return <ServerSetup auth={state} onChanged={() => void read()} />;
  }
  return <>{children(state, () => void read())}</>;
}
