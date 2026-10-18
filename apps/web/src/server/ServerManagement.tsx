import { useRef, useState, type FormEvent } from 'react';
import type { AuthState } from '../authApi';
import { orgApi, type OrgPerson } from '../orgApi';
import { serverApi, type ServerAdministrationView } from '../serverApi';
import { EmptyState } from '../ui/EmptyState';
import { RowSkeleton } from '../ui/Group';
import { usePoll } from '../usePoll';
import { at } from '../workModel';
import { ApiError } from '../workApi';
import './server.css';

export function ServerManagement({ auth, onAuthChanged }: { auth: AuthState; onAuthChanged: () => void }) {
  const [view, setView] = useState<ServerAdministrationView | null>(null);
  const [people, setPeople] = useState<OrgPerson[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [target, setTarget] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [busy, setBusy] = useState(false);
  const inFlight = useRef(false);

  function report(e: unknown) {
    setError(e instanceof Error ? e.message : String(e));
    if (e instanceof ApiError && e.status === 403) {
      setView(null);
      setPeople([]);
      onAuthChanged();
    }
  }

  usePoll(async (alive, signal) => {
    if (!auth.server_admin) return;
    const [server, org] = await Promise.all([serverApi.view(signal), orgApi.view(signal)]);
    if (alive()) { setView(server); setPeople(org.people); setError(null); }
  }, 30000, [refresh, auth.server_admin], report);

  const candidates = people.filter((person) => person.status === 'active' && person.id !== view?.administrator_user_id);
  const valid = candidates.some((person) => person.id === target);
  const why = auth.mode !== 'required' ? '로그인을 사용하는 서버에서 변경할 수 있다' : !view?.initialized_at ? '최초 설정을 먼저 마친다' : candidates.length === 0 ? '먼저 조직에 다른 구성원을 등록한다' : !valid ? '넘겨받을 구성원을 고른다' : !confirmed ? '권한 이전 결과를 확인한다' : null;

  async function transfer(event: FormEvent) {
    event.preventDefault();
    if (why || inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    try {
      await serverApi.transfer(target);
      window.location.hash = 'me';
      onAuthChanged();
    } catch (e) { report(e); }
    finally { inFlight.current = false; setBusy(false); }
  }

  return <main className="me-page server-management"><article className="dt me">
    <nav className="server-location" aria-label="현재 위치"><a href="#me">내 계정</a><span aria-current="page">서버 관리</span></nav>
    <h1 className="dt-title">서버 관리</h1>
    {!auth.server_admin ? <EmptyState kind="empty" title="서버 관리자만 볼 수 있다"><a className="btn" href="#me">내 화면으로 돌아가기</a></EmptyState> : <>
      {error && <div className="wd-refused" role="alert">{error} <button className="btn" onClick={() => setRefresh((value) => value + 1)}>다시 읽기</button></div>}
      {view === null ? <RowSkeleton rows={4} /> : <>
        <dl className="dt-facts">
          <div><dt>조직</dt><dd>{view.organization_name}</dd></div>
          <div><dt>서버 관리자</dt><dd>{view.administrator_name}</dd></div>
          <div><dt>최초 설정</dt><dd>{view.initialized_at ? at(view.initialized_at) : '아직 마치지 않았다'}</dd></div>
          <div><dt>로그인</dt><dd>{view.auth_mode === 'required' ? '계정으로 로그인' : '이 PC에서 로그인 없이 사용'}</dd></div>
        </dl>
        <div className="dt-grid"><div className="dt-main">
          <section className="dt-sec">
            <h2 className="me-h">서버 관리자 변경</h2>
            <p className="me-note">서버 관리는 한 사람이 맡는다. 조직 소유자와 구성원의 업무 역할은 바뀌지 않는다.</p>
            <form className="wn" aria-busy={busy} onSubmit={(event) => void transfer(event)}>
              <label className="wn-label">넘겨받을 구성원
                <select className="field" value={target} disabled={busy || auth.mode !== 'required'} onChange={(event) => { setTarget(event.target.value); setConfirmed(false); }}>
                  <option value="">구성원 선택</option>
                  {candidates.map((person) => <option key={person.id} value={person.id}>{person.display_name}</option>)}
                </select>
              </label>
              <p className="me-note">비밀번호 설정을 마친 활성 구성원에게만 넘길 수 있다.</p>
              <label className="server-confirm"><input type="checkbox" checked={confirmed} disabled={busy || !valid} onChange={(event) => setConfirmed(event.target.checked)} /><span>변경하면 내 서버 관리 권한이 즉시 사라지는 것을 확인했다.</span></label>
              <div className="wd-acts"><button type="submit" className="btn btn-primary" disabled={busy || why !== null} aria-describedby={why ? 'server-transfer-why' : undefined}>{busy ? '넘기는 중' : '서버 관리 넘기기'}</button>
                {why && <span className="ctl-why" id="server-transfer-why">{why}</span>}
              </div>
            </form>
          </section>
        </div><aside className="dt-aside"><section className="dt-sec">
          <h2 className="me-h">조직과 계정</h2>
          <p className="me-note">구성원과 업무 역할은 조직 화면에서 관리한다. 서버 관리자여도 개인 메모나 남의 비공개 업무를 읽을 수 있는 권한이 추가되지는 않는다.</p>
          <div className="wd-acts"><a className="btn" href="#org">조직 열기</a>
          <a className="btn" href="#me">내 계정과 기기</a></div>
        </section></aside></div>
      </>}
    </>}
  </article></main>;
}
