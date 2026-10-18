import { useEffect, useId, useState } from 'react';
import { orgApi, type LoginSetup, type PersonLogin } from '../orgApi';

/** 코드 원문은 이 화면의 메모리에만 둔다. 사람을 바꾸거나 화면을 닫으면 다시 표시하지 않는다. */
export function PersonLoginControl({ personId, name }: { personId: string; name: string }) {
  const [state, setState] = useState<PersonLogin | null>(null);
  const [issued, setIssued] = useState<LoginSetup | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [copied, setCopied] = useState(false);
  const [revision, setRevision] = useState(0);
  const noteId = useId();

  useEffect(() => {
    const controller = new AbortController();
    setState(null);
    setError(null);
    setConfirmed(false);
    void orgApi.personLogin(personId, controller.signal).then(setState).catch((e: unknown) => {
      if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '로그인 상태를 읽지 못했다');
    });
    return () => controller.abort();
  }, [personId, revision]);

  async function issue() {
    if (busy || !state?.can_issue || ((state.login || state.pending_until) && !confirmed)) return;
    setBusy(true);
    setError(null);
    // 요청 결과가 불확실해도 이전 코드를 계속 유효한 것처럼 보여 주지 않는다.
    setIssued(null);
    setCopied(false);
    try {
      const result = await orgApi.issueLoginSetup(personId);
      setIssued(result);
      setState({ ...state, pending_until: result.expires_at });
      setConfirmed(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : '코드를 발급하지 못했다');
    } finally {
      setBusy(false);
    }
  }

  async function copyCode() {
    if (!issued) return;
    try {
      await navigator.clipboard.writeText(issued.setup_token);
      setCopied(true);
    } catch {
      setError('복사하지 못했다. 코드 칸을 선택해 직접 복사한다.');
    }
  }

  return (
    <section className="dt-sec org-login" aria-busy={busy || (!state && !error) || undefined}>
      <h3>로그인 계정</h3>
      {state ? (
        <>
          <p className="org-note">{state.login ? <>아이디 <strong>{state.login}</strong></> : '아직 첫 비밀번호를 설정하지 않았다.'}</p>
          {state.pending_until && <p className="org-note">설정 코드 사용 대기 · <time dateTime={state.pending_until}>{date(state.pending_until)}</time>까지</p>}
          {state.can_issue ? (
            <>
              <p className="org-note" id={noteId}>코드는 24시간 동안 한 번 사용할 수 있다. 새로 발급하면 이전 코드는 만료된다. {state.login ? '본인이 코드를 사용하면 비밀번호가 바뀌고 기존 웹 로그인이 종료된다.' : `${name} 님이 로그인 화면의 ‘처음 비밀번호 정하기’에서 아이디와 비밀번호를 정한다.`}</p>
              {(state.login || state.pending_until) && (
                <label className="check org-login-confirm">
                  <input type="checkbox" checked={confirmed} disabled={busy} onChange={(e) => setConfirmed(e.target.checked)} />
                  <span>{state.login ? '본인의 요청을 확인했으며 재설정 코드를 전달한다' : '이전 코드를 만료시키고 새 코드를 발급한다'}</span>
                </label>
              )}
              <button type="button" className="btn" aria-describedby={noteId} disabled={busy || Boolean((state.login || state.pending_until) && !confirmed)} onClick={() => void issue()}>
                {busy ? '발급 중…' : state.login ? '비밀번호 재설정 코드 발급' : state.pending_until ? '설정 코드 다시 발급' : '첫 비밀번호 설정 코드 발급'}
              </button>
            </>
          ) : <p className="org-note">{state.reason}</p>}
        </>
      ) : !error && <p className="org-note" role="status">로그인 상태를 읽는 중…</p>}
      {issued && (
        <div className="org-login-code">
          <label>한 번만 표시하는 설정 코드
            <textarea className="field" readOnly rows={3} value={issued.setup_token} spellCheck={false} onFocus={(e) => e.target.select()} />
          </label>
          <p className="org-note" role="status">{name} 님에게 이 서버 주소와 코드를 직접 전달한다. <time dateTime={issued.expires_at}>{date(issued.expires_at)}</time>까지 사용할 수 있다. 화면을 닫으면 코드를 다시 볼 수 없다.</p>
          <div className="org-login-actions">
            <button type="button" className="btn" onClick={() => void copyCode()}>{copied ? '복사함' : '코드 복사'}</button>
            <button type="button" className="btn btn-quiet" onClick={() => setIssued(null)}>코드 숨기기</button>
          </div>
        </div>
      )}
      {error && <p className="org-note org-refused" role="alert">{error}</p>}
      <button type="button" className="btn btn-quiet" disabled={busy} onClick={() => { setIssued(null); setRevision((r) => r + 1); }}>로그인 상태 새로고침</button>
    </section>
  );
}

function date(value: string) {
  return new Date(value).toLocaleString('ko-KR');
}
