import { useId, useState } from 'react';
import { Icon } from '../icons/Icon';
import { sessionHref } from '../inboxModel';
import { sessionsApi, type InboxItem, type RelayAnswer } from '../sessionsApi';
import { whyMe } from './inboxView';
import {
  canAnswer,
  choose,
  permissionAnswer,
  permissionView,
  questionAnswer,
  questionsOf,
  relayed,
  relayStatus,
  type Choices,
  type PermissionView,
  type QuestionView,
} from './relayView';

/** What sends an answer or passes a relay to the terminal; tests put their own in. */
export type RelaySend = {
  answer: (id: string, answer: RelayAnswer) => Promise<unknown>;
  toTerminal: (id: string) => Promise<unknown>;
};

const SERVER: RelaySend = { answer: sessionsApi.answerRelay, toTerminal: sessionsApi.relayToTerminal };

type Props = {
  item: InboxItem;
  now: number;
  /** The server took an answer or a pass to the terminal: read the list again, the item stays. */
  onAnswered: () => void;
  /** A refusal, for the problem list at the top. */
  onError: (e: unknown) => void;
  /** Whether the session is still live, for the link to it. */
  live: boolean;
  send?: RelaySend;
};

/** The relay's state as a shape and words, for the facts line and the row (never colour alone). */
export function RelayState({ item, now }: { item: InboxItem; now: number }) {
  const s = relayStatus(item, now);
  return (
    <span className={`ib-rs is-${s.tone}`}>
      <Icon name={s.mark} />
      <span className="num">{s.label}</span>
    </span>
  );
}

/**
 * The body of a relayed permission request or question: what a person judges, in full (contract
 * relay 2.3: never a one-line summary alone), why it waits for me, and what I can do while the
 * deadline lasts. After an answer the item stays and says whether the answer reached the executor.
 */
export function RelayBody({ item, now, onAnswered, onError, live, send = SERVER }: Props) {
  const permission = item.kind === 'permission_request' ? permissionView(item) : null;
  const questions = item.kind === 'session_question' ? questionsOf(item) : [];
  return (
    <>
      {permission && <PermissionSections p={permission} />}
      {item.kind === 'session_question' && !canAnswer(item, now) && questions.length > 0 && <QuestionList questions={questions} />}
      <section className="dt-sec">
        <h3>할 수 있는 일</h3>
        {canAnswer(item, now) ? (
          item.kind === 'permission_request' ? (
            <AnswerPermission item={item} onAnswered={onAnswered} onError={onError} send={send} />
          ) : (
            <AnswerQuestion item={item} questions={questions} onAnswered={onAnswered} onError={onError} send={send} />
          )
        ) : (
          <Outcome item={item} now={now} live={live} />
        )}
      </section>
      {permission && <WorkPlace p={permission} />}
      <section className="dt-sec">
        <h3>왜 나를 기다리나</h3>
        <p className="dt-text">{whyMe(item)}</p>
      </section>
    </>
  );
}

/** The command in full, the file, the whole input for other tools and the executor's note. */
function PermissionSections({ p }: { p: PermissionView }) {
  return (
    <>
      {p.command !== null && (
        <section className="dt-sec">
          <h3>실행할 명령</h3>
          <pre className="ib-cmd">
            <code>{p.command}</code>
          </pre>
        </section>
      )}
      {p.command === null && p.filePath && (
        <section className="dt-sec">
          <h3>대상 파일</h3>
          <p className="ib-path">
            <code>{p.filePath}</code>
          </p>
        </section>
      )}
      {p.input && (
        <section className="dt-sec">
          <h3>도구 입력 전체</h3>
          <pre className="ib-cmd">
            <code>{p.input}</code>
          </pre>
        </section>
      )}
      {p.description && (
        <section className="dt-sec">
          <h3>실행기가 붙인 설명</h3>
          <p className="dt-text">{p.description}</p>
        </section>
      )}
    </>
  );
}

/** Where the session works: the repository, branch and folder, and its permission mode. */
function WorkPlace({ p }: { p: PermissionView }) {
  const where = p.location;
  return (
    <>
      {(where || p.permissionMode) && (
        <section className="dt-sec">
          <h3>작업 위치</h3>
          <dl className="ib-where">
            {where?.inRepo && (
              <div>
                <dt>저장소</dt>
                <dd>
                  <code>{where.remote ?? where.repoKey ?? '이름 없음'}</code>
                </dd>
              </div>
            )}
            {where?.branch && (
              <div>
                <dt>브랜치</dt>
                <dd>
                  <code>{where.branch}</code>
                </dd>
              </div>
            )}
            {where && (
              <div>
                <dt>{where.inRepo ? '저장소 안 위치' : '폴더'}</dt>
                <dd>{where.path === '' || where.path === null ? (where.inRepo ? '저장소 뿌리' : '알 수 없음') : <code>{where.path}</code>}</dd>
              </div>
            )}
            {p.permissionMode && (
              <div>
                <dt>권한 모드</dt>
                <dd>
                  <code>{p.permissionMode}</code>
                </dd>
              </div>
            )}
          </dl>
        </section>
      )}
    </>
  );
}

/** The questions as asked, read only: a question that was not relayed, or one whose time is over. */
function QuestionList({ questions }: { questions: QuestionView[] }) {
  return (
    <section className="dt-sec">
      <h3>묻는 것</h3>
      {questions.map((q) => (
        <div key={q.question} className="ib-q">
          <p className="ib-q-t">
            {q.header && <span className="ib-q-h">{q.header}</span>}
            {q.question}
          </p>
          <ul className="ib-opts is-read">
            {q.options.map((o) => (
              <li key={o.label}>
                <span className="ib-opt-l">{o.label}</span>
                {o.description && <span className="ib-opt-d">{o.description}</span>}
              </li>
            ))}
          </ul>
        </div>
      ))}
    </section>
  );
}

/** How a send went wrong: a late answer says what happened instead (「이미 터미널로 넘어갔다」). */
function Refused({ error }: { error: string }) {
  const late = error.startsWith('HTTP 409');
  return (
    <p role="alert" className="ib-refused">
      <Icon name="alert" />
      {late ? (
        <span>받지 않았다: {error.replace(/^HTTP 409: /, '')}.</span>
      ) : (
        <span>
          보내지 못했다: {error}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 누른다.
        </span>
      )}
    </p>
  );
}

function useSend(onAnswered: () => void, onError: (e: unknown) => void) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  async function run(what: string, fn: () => Promise<unknown>) {
    setBusy(what);
    try {
      await fn();
      setError(null);
      onAnswered();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      // A late answer is the relay's own outcome, not a broken server; the list reads again to show it.
      if (e instanceof Error && e.message.includes('HTTP 409')) onAnswered();
      else onError(e);
    } finally {
      setBusy(null);
    }
  }
  return { busy, error, run };
}

/** 허용, 거절 (a reason may go with it) and 터미널에서 답하기. Each sends once; the server takes the first in time. */
function AnswerPermission({ item, onAnswered, onError, send }: { item: InboxItem; onAnswered: () => void; onError: (e: unknown) => void; send: RelaySend }) {
  const [denying, setDenying] = useState(false);
  const [reason, setReason] = useState('');
  const { busy, error, run } = useSend(onAnswered, onError);
  const reasonId = useId();
  const answer = (decision: 'allow' | 'deny') => void run(decision, () => send.answer(item.id, permissionAnswer(decision, reason)));
  return (
    <>
      <div className="ib-acts" role="group" aria-label="권한 요청에 답하기">
        <button type="button" className={denying ? 'btn' : 'btn btn-primary'} disabled={busy !== null} aria-busy={busy === 'allow' ? 'true' : undefined} onClick={() => answer('allow')}>
          {busy === 'allow' ? '보내는 중' : '허용'}
          <small>이번 한 번 실행하게 한다</small>
        </button>
        <button
          type="button"
          className="btn"
          disabled={busy !== null}
          aria-expanded={denying}
          aria-controls={denying ? reasonId : undefined}
          onClick={() => setDenying(true)}
        >
          거절
          <small>이유를 붙여 보낼 수 있다</small>
        </button>
        <button type="button" className="btn" disabled={busy !== null} aria-busy={busy === 'terminal' ? 'true' : undefined} onClick={() => void run('terminal', () => send.toTerminal(item.id))}>
          {busy === 'terminal' ? '보내는 중' : '터미널에서 답하기'}
          <small>여기서는 답하지 않는다</small>
        </button>
      </div>
      {denying && (
        <form
          id={reasonId}
          className="ib-reason"
          onSubmit={(e) => {
            e.preventDefault();
            answer('deny');
          }}
        >
          <label>
            거절 이유(적지 않아도 된다)
            <input className="field" value={reason} maxLength={500} onChange={(e) => setReason(e.target.value)} placeholder="예: 빌드 폴더는 지우지 않는다" autoFocus />
          </label>
          <button type="submit" className="btn btn-primary" disabled={busy !== null} aria-busy={busy === 'deny' ? 'true' : undefined}>
            {busy === 'deny' ? '보내는 중' : '거절 보내기'}
          </button>
          <button type="button" className="btn" disabled={busy !== null} onClick={() => setDenying(false)}>
            그만두기
          </button>
          <span className="ctl-why">이유는 AI 세션에 그대로 전해진다.</span>
        </form>
      )}
      {error && <Refused error={error} />}
      <p className="ib-sub">터미널에도 같은 질문이 떠 있다. 먼저 답한 쪽이 쓰인다.</p>
    </>
  );
}

/** One choice per question (several where the question allows), then 답 보내기, or 터미널로 넘기기. */
function AnswerQuestion({
  item,
  questions,
  onAnswered,
  onError,
  send,
}: {
  item: InboxItem;
  questions: QuestionView[];
  onAnswered: () => void;
  onError: (e: unknown) => void;
  send: RelaySend;
}) {
  const [choices, setChoices] = useState<Choices>({});
  const { busy, error, run } = useSend(onAnswered, onError);
  const name = useId();
  const ready = questionAnswer(questions, choices);
  return (
    <>
      {questions.map((q, qi) => (
        <fieldset key={q.question} className="ib-q">
          <legend className="ib-q-t">
            {q.header && <span className="ib-q-h">{q.header}</span>}
            {q.question}
          </legend>
          {q.multiSelect && <p className="ib-sub">여러 개를 고를 수 있다</p>}
          <ul className="ib-opts">
            {q.options.map((o) => (
              <li key={o.label}>
                <label className="ib-opt">
                  <input
                    type={q.multiSelect ? 'checkbox' : 'radio'}
                    name={`${name}-${qi}`}
                    checked={(choices[q.question] ?? []).includes(o.label)}
                    disabled={busy !== null}
                    onChange={() => setChoices((c) => choose(c, q, o.label))}
                  />
                  <span className="ib-opt-l">{o.label}</span>
                  {o.description && <span className="ib-opt-d">{o.description}</span>}
                </label>
              </li>
            ))}
          </ul>
        </fieldset>
      ))}
      <div className="ib-acts" role="group" aria-label="질문에 답하기">
        <button
          type="button"
          className="btn btn-primary"
          disabled={busy !== null || 'missing' in ready}
          aria-busy={busy === 'answer' ? 'true' : undefined}
          onClick={() => 'answer' in ready && void run('answer', () => send.answer(item.id, ready.answer))}
        >
          {busy === 'answer' ? '보내는 중' : '답 보내기'}
          <small>고른 것을 세션에 넘긴다</small>
        </button>
        <button type="button" className="btn" disabled={busy !== null} aria-busy={busy === 'terminal' ? 'true' : undefined} onClick={() => void run('terminal', () => send.toTerminal(item.id))}>
          {busy === 'terminal' ? '보내는 중' : '터미널로 넘기기'}
          <small>터미널에 질문 창이 뜬다</small>
        </button>
      </div>
      {'missing' in ready && <span className="ctl-why">「{ready.missing}」의 답을 골라야 보낼 수 있다.</span>}
      {error && <Refused error={error} />}
    </>
  );
}

/** A relay that cannot be answered here (any more): what happened, and the session to go to. */
function Outcome({ item, now, live }: { item: InboxItem; now: number; live: boolean }) {
  const s = relayStatus(item, now);
  const terminal = s.tone === 'off' && (item.state ?? 'open') === 'open';
  return (
    <>
      <p className="ib-term">
        <Icon name={s.mark} />
        <span>
          <strong>{s.label}</strong>
          {s.note && <> {s.note}</>}
        </span>
      </p>
      {(terminal || !relayed(item)) && (
        <p className="ib-acts-row">
          <a className="btn" href={sessionHref(item.source_id, live)}>
            세션 현황에서 그 세션 열기
          </a>
        </p>
      )}
    </>
  );
}
