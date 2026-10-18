import { useEffect, useRef, useState, type FormEvent } from 'react';
import { authApi, type AuthState } from '../authApi';
import { serverApi } from '../serverApi';
import { ApiError } from '../workApi';
import { RowSkeleton } from '../ui/Group';
import './server.css';

/** Establishes server administration for a new or upgraded installation, preserving existing names. */
export function ServerSetup({ auth, onChanged }: { auth: AuthState; onChanged: () => void }) {
  const [organization, setOrganization] = useState('');
  const [name, setName] = useState(auth.person?.display_name === 'owner' ? '' : (auth.person?.display_name ?? ''));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const inFlight = useRef(false);
  const changed = useRef(onChanged);
  changed.current = onChanged;
  const organizationInput = useRef<HTMLInputElement>(null);
  const why = !loaded ? '기존 설정을 먼저 불러온다' : !organization.trim() ? '조직 이름을 입력한다' : !name.trim() ? '관리자 이름을 입력한다' : null;

  useEffect(() => {
    const controller = new AbortController();
    setLoaded(false);
    setError(null);
    void serverApi.view(controller.signal).then((view) => {
      if (controller.signal.aborted) return;
      if (view.initialized_at) { changed.current(); return; }
      setOrganization(view.organization_name === 'owner' ? '' : view.organization_name);
      setName(view.administrator_name === 'owner' ? '' : view.administrator_name);
      setLoaded(true);
    }).catch((e: unknown) => {
      if (controller.signal.aborted) return;
      setError(e instanceof Error ? e.message : String(e));
      if (e instanceof ApiError && e.status === 403) changed.current();
    });
    return () => controller.abort();
  }, [refresh]);

  useEffect(() => { if (loaded) organizationInput.current?.focus(); }, [loaded]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (why || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      await serverApi.initialize(organization.trim(), name.trim());
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      if (e instanceof ApiError && (e.status === 409 || e.status === 403)) onChanged();
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  async function logout() {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    try { await authApi.logout(); onChanged(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { inFlight.current = false; setBusy(false); }
  }

  return <main className="au-page">
    <div className="au-col">
      <p className="au-brand">zm-baton</p>
      <form className="au-form wn server-setup" aria-labelledby="server-setup-title" aria-busy={busy || (!loaded && !error)} onSubmit={(event) => void submit(event)}>
        <h1 id="server-setup-title" className="dt-title">내 조직의 서버 시작하기</h1>
        <p className="wn-lead">조직과 관리자 이름을 확인하고 이 서버의 관리를 시작한다. 필요한 이름만 바꿀 수 있다.</p>
        {!loaded && !error && <RowSkeleton rows={2} />}
        {loaded && <><label className="wn-label">조직 이름
          <input ref={organizationInput} className="field" value={organization} onChange={(event) => setOrganization(event.target.value)} maxLength={100} autoComplete="organization" required disabled={busy || !loaded} />
        </label>
        <label className="wn-label">관리자 이름
          <input className="field" value={name} onChange={(event) => setName(event.target.value)} maxLength={100} autoComplete="name" required disabled={busy || !loaded} />
        </label></>}
        <p className="wn-lead">처음에는 내가 서버 관리자와 조직 소유자를 함께 맡는다. 서버 관리는 나중에 다른 구성원에게 넘길 수 있다.</p>
        {error && <div className="wd-refused" role="alert">{error}{!loaded && <button className="btn" type="button" onClick={() => setRefresh((value) => value + 1)}>설정 다시 읽기</button>}</div>}
        <div className="wd-acts">
          <button className="btn btn-primary" type="submit" disabled={busy || why !== null} aria-describedby={why ? 'server-setup-why' : undefined}>{busy ? '처리 중' : '조직 설정 마치기'}</button>
          {why && <span className="ctl-why" id="server-setup-why">{why}</span>}
        </div>
        <p className="au-switch"><button className="btn btn-quiet" type="button" disabled={busy} onClick={() => void logout()}>로그아웃</button></p>
      </form>
    </div>
  </main>;
}
