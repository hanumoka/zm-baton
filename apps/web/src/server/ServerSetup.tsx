import { useRef, useState, type FormEvent } from 'react';
import { authApi, type AuthState } from '../authApi';
import { serverApi } from '../serverApi';

/** The password has already been set; this one-time form gives the installation its own names. */
export function ServerSetup({ auth, onChanged }: { auth: AuthState; onChanged: () => void }) {
  const [organization, setOrganization] = useState('');
  const [name, setName] = useState(auth.person?.display_name === 'owner' ? '' : (auth.person?.display_name ?? ''));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inFlight = useRef(false);
  const why = !organization.trim() ? '조직 이름을 입력한다' : !name.trim() ? '관리자 이름을 입력한다' : null;

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
      <form className="au-form wn" aria-labelledby="server-setup-title" onSubmit={(event) => void submit(event)}>
        <h1 id="server-setup-title" className="dt-title">내 조직의 서버 시작하기</h1>
        <p className="wn-lead">비밀번호 설정을 마쳤다. 함께 사용할 조직 이름과 관리자로 표시할 이름을 정한다.</p>
        <label className="wn-label">조직 이름
          <input className="field" value={organization} onChange={(event) => setOrganization(event.target.value)} maxLength={100} autoComplete="organization" autoFocus required disabled={busy} />
        </label>
        <label className="wn-label">관리자 이름
          <input className="field" value={name} onChange={(event) => setName(event.target.value)} maxLength={100} autoComplete="name" required disabled={busy} />
        </label>
        <p className="wn-lead">처음에는 내가 서버 관리자와 조직 소유자를 함께 맡는다. 서버 관리는 나중에 다른 구성원에게 넘길 수 있다.</p>
        {error && <p className="wd-refused" role="alert">{error}</p>}
        <div className="wd-acts">
          <button className="btn btn-primary" type="submit" disabled={busy || why !== null} aria-describedby={why ? 'server-setup-why' : undefined}>{busy ? '처리 중' : '조직 설정 마치기'}</button>
          {why && <span className="ctl-why" id="server-setup-why">{why}</span>}
        </div>
        <button className="btn" type="button" disabled={busy} onClick={() => void logout()}>로그아웃</button>
      </form>
    </div>
  </main>;
}
