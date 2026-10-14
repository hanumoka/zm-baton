import { useId, useState } from 'react';
import { Icon } from '../icons/Icon';
import { inboxHref } from '../inboxModel';
import { questionsApi, type AskBody, type Question, type QuestionKind } from '../questionsApi';
import { usePoll } from '../usePoll';
import type { WorkItemDetail } from '../workApi';
import { at } from '../workModel';
import { askBody, askerLabel, deliveryLabel, isWaiting, KINDS, kindLabel, roleLabel, stateLabel } from './questionModel';

/*
 * The questions of a work item in its detail (FN-0210): each with who asked, who answers, where it
 * stands and the answer; a way to record the document change an answer waited for; and leaving a
 * new question. The server says whether a question can be left now and why not.
 */

type Props = {
  detail: WorkItemDetail;
  /** Leaves a question; the work item comes back with its new version and wait reasons. */
  onAsk: (body: AskBody) => Promise<void>;
  api?: Pick<typeof questionsApi, 'list' | 'docChange'>;
};

export function Questions({ detail, onAsk, api = questionsApi }: Props) {
  const id = detail.item.id;
  const [list, setList] = useState<{ id: string; questions: Question[] } | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const [bump, setBump] = useState(0);
  usePoll(
    async (alive, signal) => {
      const questions = await api.list(id, signal);
      if (!alive()) return;
      setList({ id, questions });
      setProblem(null);
    },
    5000,
    [id, detail.item.version, bump],
    (e) => setProblem(e instanceof Error ? e.message : String(e)),
  );
  const shown = list?.id === id ? list.questions : null;
  return (
    <section className="dt-sec">
      <h3>질문</h3>
      {problem && <p className="ctl-err">질문을 읽지 못했다. {problem}</p>}
      <QuestionList questions={shown} onDocChange={async (q, ref) => {
        await api.docChange(q, ref);
        setBump((b) => b + 1);
      }} />
      <AskForm detail={detail} onAsk={onAsk} />
    </section>
  );
}

/** The questions, oldest first. */
export function QuestionList({ questions, onDocChange }: { questions: Question[] | null; onDocChange: (id: string, ref: string) => Promise<void> }) {
  if (questions === null) return <p className="dt-none">읽는 중</p>;
  if (questions.length === 0) return <p className="dt-none">아직 남은 질문이 없다.</p>;
  return (
    <ol className="qs-list">
      {questions.map((q) => (
        <li key={q.id} className="qs-item" data-question={q.id}>
          <p className="qs-head">
            <strong className={isWaiting(q) ? 'is-wait' : undefined}>{stateLabel(q)}</strong>
            <span>{kindLabel(q.kind)}</span>
            {q.blocking && <span>막힘</span>}
            <span>
              묻는 쪽 {askerLabel(q)}, 답하는 쪽 {roleLabel(q.routed_role)} <code>{q.routed_to_id}</code>
            </span>
            {isWaiting(q) && (
              <span className={q.overdue || q.remind_count > 0 ? 'qs-late' : 'hr-dim'}>
                기한 <time dateTime={q.due_at}>{at(q.due_at)}</time>
                {q.remind_count > 0 && `, 다시 알림 ${q.remind_count}회`}
              </span>
            )}
          </p>
          <p className="dt-text qs-body">{q.body}</p>
          {q.answer && (
            <p className="qs-answer-text">
              <span className="qs-label">답</span>
              <span className="dt-text">{q.answer}</span>
            </p>
          )}
          {(q.decision_ref || q.doc_change_ref || q.approval_ref) && (
            <p className="ib-sub">
              이어진 기록 {[q.doc_change_ref, q.decision_ref, q.approval_ref].filter(Boolean).map((r) => (
                <code key={r!}>{r}</code>
              ))}
            </p>
          )}
          {deliveryLabel(q) && <p className="ib-sub">{deliveryLabel(q)}</p>}
          {q.state === 'answered' && q.run_id && <p className="ib-sub">「이어서 맡기기」를 누르면 이 답이 다음 실행의 지시에 들어간다.</p>}
          {q.inbox_item_id && (
            <p className="ib-sub">
              <a href={inboxHref(q.inbox_item_id)}>나를 기다리는 것에서 답하기</a>
            </p>
          )}
          {q.state === 'awaiting_doc_change' && <DocChange q={q} onDocChange={onDocChange} />}
        </li>
      ))}
    </ol>
  );
}

/** Records the document version the answer waited for; the question is then settled or goes to the next run. */
function DocChange({ q, onDocChange }: { q: Question; onDocChange: (id: string, ref: string) => Promise<void> }) {
  const [ref, setRef] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fieldId = useId();
  return (
    <form
      className="qs-doc"
      onSubmit={(e) => {
        e.preventDefault();
        if (!ref.trim()) return;
        setBusy(true);
        onDocChange(q.id, ref.trim())
          .then(() => setError(null))
          .catch((err: unknown) => setError(err instanceof Error ? err.message : String(err)))
          .finally(() => setBusy(false));
      }}
    >
      <label htmlFor={fieldId}>바꾼 문서판 번호</label>
      <input id={fieldId} className="field rv-mono" value={ref} onChange={(e) => setRef(e.target.value)} placeholder="dvr_… 또는 doc_…" />
      <button type="submit" className="btn" disabled={busy || !ref.trim()}>
        문서 반영 기록
      </button>
      {error && <span className="ctl-err">{error}</span>}
    </form>
  );
}

/** A new question from a person. Locked with the server's reason when the work item takes none. */
export function AskForm({ detail, onAsk }: { detail: WorkItemDetail; onAsk: (body: AskBody) => Promise<void> }) {
  const availability = detail.question ?? { allowed: false, reason: '이 서버는 질문을 아직 모른다. 서버를 새 판으로 올린다' };
  const whyId = useId();
  const [open, setOpen] = useState(false);
  const [text, setText] = useState('');
  const [kind, setKind] = useState<QuestionKind>('interpretation');
  const [blocking, setBlocking] = useState(true);
  const [busy, setBusy] = useState(false);
  const body = askBody(text, kind, blocking);
  if (!availability.allowed || !open) {
    return (
      <div className="qs-ask">
        <button type="button" className="btn" disabled={!availability.allowed} aria-describedby={availability.allowed ? undefined : whyId} onClick={() => setOpen(true)}>
          <Icon name="plus" />
          질문 남기기
        </button>
        {!availability.allowed && (
          <p className="wd-why" id={whyId}>
            <Icon name="lock" />
            <span>{availability.reason ?? '이유를 받지 못했다'}</span>
          </p>
        )}
      </div>
    );
  }
  const to = KINDS.find((k) => k.kind === kind)?.to ?? '';
  return (
    <form
      className="wd-edit qs-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (!body) return;
        setBusy(true);
        onAsk(body)
          .then(() => {
            setText('');
            setOpen(false);
          })
          .catch(() => undefined) // The parent shows the refusal; retain the question.
          .finally(() => setBusy(false));
      }}
    >
      <label>
        질문
        <textarea className="field" rows={3} maxLength={4000} value={text} onChange={(e) => setText(e.target.value)} placeholder="예: 보존 기간은 30일인가 90일인가?" autoFocus />
      </label>
      <label>
        종류
        <span className="select">
          <select value={kind} onChange={(e) => setKind(e.target.value as QuestionKind)}>
            {KINDS.map((k) => (
              <option key={k.kind} value={k.kind}>
                {k.label}
              </option>
            ))}
          </select>
          <Icon name="chevron-down" className="chev" />
        </span>
        <span className="ctl-why">받는 사람: {to}</span>
      </label>
      <label className="check">
        <input type="checkbox" checked={blocking} onChange={(e) => setBlocking(e.target.checked)} />
        <span>답이 있어야 다음으로 간다(바로 알림). 끄면 묶어 알린다</span>
      </label>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={busy || body === null} aria-busy={busy || undefined}>
          질문 보내기
        </button>
        <button type="button" className="btn" onClick={() => setOpen(false)}>
          그만두기
        </button>
      </div>
      {body === null && <span className="ctl-why">질문을 적어야 보낼 수 있다.</span>}
    </form>
  );
}

/** A run's question, recorded by a person from the run's result. The run's last answer starts the text. */
export function RunQuestionForm({ runId, start, onAsk, onClose }: { runId: string; start: string; onAsk: (body: AskBody) => Promise<void>; onClose: () => void }) {
  const [text, setText] = useState(start);
  const [kind, setKind] = useState<QuestionKind>('interpretation');
  const [blocking, setBlocking] = useState(true);
  const [busy, setBusy] = useState(false);
  const body = askBody(text, kind, blocking, runId);
  return (
    <form
      className="wd-edit qs-form"
      onSubmit={(e) => {
        e.preventDefault();
        if (!body) return;
        setBusy(true);
        onAsk(body)
          .then(onClose)
          .catch(() => undefined)
          .finally(() => setBusy(false));
      }}
    >
      <p className="wn-lead">이 실행의 AI가 물은 것을 질문으로 남긴다. 결과 검토에 있던 결과면 작업이 수행으로 돌아가고, 답한 뒤 「이어서 맡기기」로 답을 넣는다.</p>
      <label>
        AI가 물은 것
        <textarea className="field" rows={4} maxLength={4000} value={text} onChange={(e) => setText(e.target.value)} />
      </label>
      <label>
        종류
        <span className="select">
          <select value={kind} onChange={(e) => setKind(e.target.value as QuestionKind)}>
            {KINDS.map((k) => (
              <option key={k.kind} value={k.kind}>
                {k.label}
              </option>
            ))}
          </select>
          <Icon name="chevron-down" className="chev" />
        </span>
      </label>
      <label className="check">
        <input type="checkbox" checked={blocking} onChange={(e) => setBlocking(e.target.checked)} />
        <span>답이 있어야 다음으로 간다</span>
      </label>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={busy || body === null} aria-busy={busy || undefined}>
          질문으로 남기기
        </button>
        <button type="button" className="btn" onClick={onClose}>
          그만두기
        </button>
      </div>
    </form>
  );
}
