import { useEffect, useState, type ReactNode } from 'react';
import type { AuthState } from '../authApi';
import { LOCAL_FALLBACK } from '../auth/AuthGate';
import { Icon } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import { meApi, type ManagerNote, type ManagerRecord } from '../orgApi';
import { rolesLine } from '../org/orgView';
import { EmptyState } from '../ui/EmptyState';
import { RowSkeleton } from '../ui/Group';
import { usePoll } from '../usePoll';
import { isBlocked } from '../work/workView';
import { at, stageLabel, workTitle } from '../workModel';
import { actionLabel, noteProblem, splitWork, STATE_LABELS, targetHref, workLine } from './meView';
import { codeInAddress, DeviceApproval, MyDevices, SignedIn } from './MyAccess';

type Props = {
  onError: (e: unknown) => void;
  onRead: () => void;
  /** How the server signs people in (AuthGate). `local` shows my devices; `required` adds sign-in and device approval. */
  auth?: AuthState;
  /** Ask the server again who is signed in, after signing out. */
  onAuthChanged?: () => void;
};

const message = (e: unknown) => (e instanceof Error ? e.message : String(e));

/**
 * My manager record (FN-0402, DEC-0099 3): not an AI that keeps running, but the one record my AI
 * sessions belong to. Who I am, the card my next session loads at start, the work I hold, my
 * sessions, my own notes and what I did. The notes are mine alone: the organisation's operators do
 * not see them and the export leaves them out (DEC-0103).
 */
export function Me({ onError, onRead, auth = LOCAL_FALLBACK, onAuthChanged = () => {} }: Props) {
  const [record, setRecord] = useState<ManagerRecord | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refresh, setRefresh] = useState(0);
  const [code, setCode] = useState(() => (typeof window === 'undefined' ? null : codeInAddress(window.location.hash)));

  useEffect(() => {
    const follow = () => setCode(codeInAddress(window.location.hash));
    window.addEventListener('hashchange', follow);
    window.addEventListener('popstate', follow);
    return () => {
      window.removeEventListener('hashchange', follow);
      window.removeEventListener('popstate', follow);
    };
  }, []);

  const required = auth.mode === 'required';
  const top = required && code !== null ? <DeviceApproval key={code} code={code} /> : null;
  const access = (
    <>
      {required && <SignedIn auth={auth} onAuthChanged={onAuthChanged} />}
      {auth.server_admin && <section className="dt-sec"><h2 className="me-h">서버 관리자</h2><a className="btn" href="#server">서버 관리 열기</a></section>}
      <MyDevices
        auth={auth}
        onEnterCode={(c) => {
          window.history.replaceState(window.history.state, '', `#me?device_login=${c}`);
          setCode(c);
        }}
      />
    </>
  );

  usePoll(
    async (alive, signal) => {
      const r = await meApi.record(signal);
      if (!alive()) return;
      setRecord(r);
      setError(null);
      onRead();
    },
    15000,
    [refresh],
    (e) => {
      setError(message(e));
      onError(e);
    },
  );

  return (
    <main className="me-page">
      {record === null ? (
        error === null ? (
          <div className="dt">
            <h1 className="sr">나</h1>
            <RowSkeleton rows={4} />
          </div>
        ) : (
          <div className="dt">
            <h1 className="sr">나</h1>
            <EmptyState
              kind="error"
              title="내 기록을 읽지 못했다"
              action={
                <button type="button" className="btn" onClick={() => setRefresh((r) => r + 1)}>
                  다시 읽기
                </button>
              }
            >
              {error}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 읽는다.
            </EmptyState>
          </div>
        )
      ) : (
        <MeView record={record} onChanged={() => setRefresh((r) => r + 1)} top={top} access={access} />
      )}
    </main>
  );
}

/**
 * The record as drawn; notes change through the server and the record is read again. [top] goes first
 * in the main column (a device login waiting for me), [access] after my sessions (sign-in and devices).
 */
export function MeView({ record: r, onChanged, top, access }: { record: ManagerRecord; onChanged: () => void; top?: ReactNode; access?: ReactNode }) {
  const { open, closed } = splitWork(r.work);
  return (
    <article className="dt me" aria-labelledby="me-title">
      <h1 id="me-title" className="dt-title">
        {r.person.display_name}
      </h1>
      <dl className="dt-facts">
        <div>
          <dt>조직</dt>
          <dd>{r.org.name}</dd>
        </div>
        <div>
          <dt>역할</dt>
          <dd>{rolesLine({ roles: r.roles, owner: false })}</dd>
        </div>
        {r.teams.length > 0 && (
          <div>
            <dt>팀</dt>
            <dd>{r.teams.map((t) => t.name).join(', ')}</dd>
          </div>
        )}
        <div>
          <dt>매니저</dt>
          <dd>
            <code>{r.manager_id}</code>
          </dd>
        </div>
      </dl>
      <div className="dt-grid">
        <div className="dt-main">
          {top}
          <section className="dt-sec">
            <h2 className="me-h">상태 카드 미리보기</h2>
            <p className="me-note">다음 세션이 시작할 때 받는 카드. 저장소에서 열면 그 저장소의 최근 결정과 다른 세션이 붙는다.</p>
            <pre className="me-card">{r.status_card}</pre>
          </section>
          <section className="dt-sec">
            <h2 className="me-h">
              내 열린 작업 <span className="n num">{open.length}</span>
            </h2>
            {open.length === 0 ? (
              <p className="dt-none">책임지거나 맡은 열린 작업이 없다.</p>
            ) : (
              <WorkRows work={open} />
            )}
            {closed.length > 0 && (
              <details className="me-more">
                <summary>끝내거나 취소한 작업 {closed.length}건</summary>
                <WorkRows work={closed} />
              </details>
            )}
          </section>
          <section className="dt-sec">
            <h2 className="me-h">
              연결된 세션 <span className="n num">{r.sessions.length}</span>
            </h2>
            {r.sessions.length === 0 ? (
              <p className="dt-none">
                등록된 세션이 없다. 터미널에서 연 AI 세션은 시작 훅이 <code>zm-baton session register</code>로 등록한다.
              </p>
            ) : (
              <ul className="me-rows">
                {r.sessions.map((s) => {
                  const line = workLine(s);
                  return (
                    <li key={s.id}>
                      <a className="me-row" href={`#sessions?item=${encodeURIComponent(s.id)}`}>
                        <span className="me-l1">
                          <Icon name="session" />
                          <span className="me-state">{STATE_LABELS[s.state] ?? s.state}</span>
                          <span className="me-title">{line.text}</span>
                        </span>
                        <span className="me-sub">
                          {line.private && <span className="me-private">공유 안 함(나만 봄)</span>}
                          {[s.executor, s.branch, s.device_name].filter(Boolean).join(', ')}, 마지막 신호 {at(s.last_seen_at)}
                        </span>
                      </a>
                    </li>
                  );
                })}
              </ul>
            )}
          </section>
          {access}
        </div>
        <aside className="dt-aside me-aside" aria-label="개인 메모와 작업 기록">
          <Notes notes={r.notes} onChanged={onChanged} />
          <section className="me-activity">
            <h2 className="me-h">작업 기록</h2>
            {r.activity.length === 0 ? (
              <p className="dt-none">아직 없다.</p>
            ) : (
              <ol className="wd-hist">
                {r.activity.map((a, i) => {
                  const href = targetHref(a);
                  return (
                    <li key={i}>
                      <span>
                        {actionLabel(a.action)}
                        {a.actor_kind === 'session' && <small className="me-by"> (세션)</small>}
                      </span>
                      {a.target_id && (href ? <a href={href}><code>{a.target_id}</code></a> : <code>{a.target_id}</code>)}
                      <time dateTime={a.at}>{at(a.at)}</time>
                    </li>
                  );
                })}
              </ol>
            )}
          </section>
        </aside>
      </div>
    </article>
  );
}

/** My notes: add, change and delete; a delete asks once more. */
export function Notes({ notes, onChanged }: { notes: ManagerNote[]; onChanged: () => void }) {
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const problem = noteProblem(draft);
  async function act(fn: () => Promise<unknown>, after?: () => void) {
    setBusy(true);
    try {
      await fn();
      setError(null);
      after?.();
      onChanged();
    } catch (e) {
      setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section className="me-notes">
      <h2 className="me-h">
        개인 메모 <span className="n num">{notes.length}</span>
      </h2>
      <p className="me-note me-private-line">
        <Icon name="lock" />
        <span>나만 본다. 조직 운영자도 못 보고, 내보내기에도 들어가지 않는다.</span>
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      <form
        className="me-add"
        onSubmit={(e) => {
          e.preventDefault();
          if (problem === null && !busy) void act(() => meApi.addNote(draft.trim()), () => setDraft(''));
        }}
      >
        <label className="sr" htmlFor="me-note-new">
          새 메모
        </label>
        <textarea id="me-note-new" className="field" rows={3} maxLength={2000} value={draft} placeholder="다음에 이어 갈 것, 기억해 둘 것" onChange={(e) => setDraft(e.target.value)} />
        <div className="wd-acts">
          <button type="submit" className="btn btn-primary" disabled={problem !== null || busy} aria-busy={busy || undefined}>
            메모 더하기
          </button>
        </div>
      </form>
      {notes.length === 0 ? (
        <p className="dt-none">아직 메모가 없다.</p>
      ) : (
        <ol className="me-note-list">
          {notes.map((n) => (
            <NoteItem key={`${n.id}:${n.updated_at}`} note={n} busy={busy} act={act} />
          ))}
        </ol>
      )}
    </section>
  );
}

function NoteItem({ note, busy, act }: { note: ManagerNote; busy: boolean; act: (fn: () => Promise<unknown>, after?: () => void) => Promise<void> }) {
  const [mode, setMode] = useState<'read' | 'edit' | 'delete'>('read');
  const [body, setBody] = useState(note.body);
  const problem = noteProblem(body);
  return (
    <li className="me-note-item">
      {mode === 'edit' ? (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (problem === null && !busy) void act(() => meApi.changeNote(note.id, body.trim()), () => setMode('read'));
          }}
        >
          <label className="sr" htmlFor={`note-${note.id}`}>
            메모 고치기
          </label>
          <textarea id={`note-${note.id}`} className="field" rows={3} maxLength={2000} value={body} onChange={(e) => setBody(e.target.value)} />
          <div className="wd-acts">
            <button type="submit" className="btn btn-primary" disabled={problem !== null || body.trim() === note.body || busy}>
              저장하기
            </button>
            <button type="button" className="btn" onClick={() => setMode('read')}>
              그만두기
            </button>
          </div>
        </form>
      ) : (
        <>
          <p className="me-note-body">{note.body}</p>
          <p className="me-note-meta">
            <time dateTime={note.updated_at}>{at(note.updated_at)}</time>
            {mode === 'delete' ? (
              <>
                <span>이 메모를 지운다. 되돌릴 수 없다.</span>
                <button type="button" className="btn" disabled={busy} onClick={() => void act(() => meApi.deleteNote(note.id))}>
                  지우기
                </button>
                <button type="button" className="btn btn-quiet" onClick={() => setMode('read')}>
                  그만두기
                </button>
              </>
            ) : (
              <>
                <button type="button" className="btn btn-quiet" onClick={() => setMode('edit')}>
                  고치기
                </button>
                <button type="button" className="btn btn-quiet" onClick={() => setMode('delete')}>
                  지우기
                </button>
              </>
            )}
          </p>
        </>
      )}
    </li>
  );
}

/** Work rows as links to the work screen: the stage, the title, the next action in two lines at most. */
function WorkRows({ work }: { work: ManagerRecord['work'] }) {
  return (
    <ul className="me-rows">
      {work.map((w) => (
        <li key={w.id}>
          <a className="me-row" href={`#work?item=${encodeURIComponent(w.id)}`}>
            <span className="me-l1">
              <StageGlyph stage={w.stage} blocked={isBlocked(w)} />
              <span className="me-state">{stageLabel(w.stage)}</span>
              <span className="me-title">{workTitle(w)}</span>
            </span>
            {w.next_action && <span className="me-sub">다음: {w.next_action}</span>}
          </a>
        </li>
      ))}
    </ul>
  );
}
