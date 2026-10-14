import { useEffect, useId, useState, type Ref } from 'react';
import { Icon } from '../icons/Icon';
import { waited } from '../inboxModel';
import { questionsApi, type Question, type QuestionDetail as Detail } from '../questionsApi';
import type { InboxItem } from '../sessionsApi';
import { EmptyState } from '../ui/EmptyState';
import { RowSkeleton } from '../ui/Group';
import { at, STAGES } from '../workModel';
import { answerBody, askerLabel, kindLabel, roleLabel, stateLabel } from './questionModel';

type Props = {
  item: InboxItem;
  now: number;
  /** The server took an answer or a withdrawal: what happened, and the work item to open. */
  onDone: (id: string, text: string, href: string | null) => void;
  onError: (e: unknown) => void;
  titleRef?: Ref<HTMLHeadingElement>;
  inSheet?: boolean;
  api?: Pick<typeof questionsApi, 'detail' | 'answer' | 'withdraw'>;
};

const workHref = (id: string, stage: string) => `#work?${stage === 'done' || stage === 'cancelled' ? 'closed=1&' : ''}item=${encodeURIComponent(id)}`;

const stageLabel = (stage: string) => STAGES.find((s) => s.stage === stage)?.label ?? stage;

/**
 * A question on the first screen (FN-0210, contract bucket 2 「멈춘 AI의 질문」): what is asked, who
 * asked and why it came to me, how long it has waited and whether its due time passed, and the
 * answer. An answer can name the document change, decision or approval it led to.
 */
export function QuestionDetail({ item, now, onDone, onError, titleRef, inSheet = false, api = questionsApi }: Props) {
  const titleId = useId();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    const ctl = new AbortController();
    api
      .detail(item.source_id, ctl.signal)
      .then((d) => {
        setDetail(d);
        setProblem(null);
      })
      .catch((e: unknown) => {
        if (!ctl.signal.aborted) setProblem(e instanceof Error ? e.message : String(e));
      });
    return () => ctl.abort();
  }, [item.source_id, api]);

  if (detail === null) {
    return (
      <article className="dt ib-dt" aria-labelledby={titleId}>
        <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
          {item.summary}
        </h2>
        {problem ? (
          <EmptyState kind="error" title="질문을 읽지 못했다">
            {problem}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 목록에서 다시 고른다.
          </EmptyState>
        ) : (
          <RowSkeleton rows={3} />
        )}
      </article>
    );
  }
  return <QuestionBody detail={detail} item={item} now={now} onDone={onDone} onError={onError} titleRef={titleRef} inSheet={inSheet} api={api} />;
}

/** The loaded question, drawn from its detail alone so it can be drawn in tests. */
export function QuestionBody({ detail, item, now, onDone, onError, titleRef, inSheet = false, api = questionsApi }: Props & { detail: Detail }) {
  const titleId = useId();
  const q = detail.question;
  const work = detail.work_item;
  return (
    <article className="dt ib-dt qs-dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>나를 기다리는 것</span>
          <span aria-hidden="true">/</span>
          <span>멈춘 AI의 질문</span>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {q.asked_by_kind === 'person' ? '사람이 남긴 질문에 답한다' : 'AI 실행이 남긴 질문에 답한다'}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>종류</dt>
          <dd className="ib-kind">{kindLabel(q.kind)}</dd>
        </div>
        <div>
          <dt>막힘</dt>
          <dd>{q.blocking ? '답이 있어야 다음으로 간다' : '답 없이도 다른 단계를 한다'}</dd>
        </div>
        <div>
          <dt>기다린 시간</dt>
          <dd>
            <span className="num">{waited(item.waiting_since, now)}</span>
          </dd>
        </div>
        <div>
          <dt>기한</dt>
          <dd>
            <time className="num" dateTime={q.due_at}>
              {at(q.due_at)}
            </time>
            {(q.overdue || q.remind_count > 0) && <span className="qs-late">기한 지남{q.remind_count > 0 ? `, 다시 알림 ${q.remind_count}회` : ''}</span>}
          </dd>
        </div>
      </dl>
      <div className="dt-grid">
        <div className="dt-main">
          <section className="dt-sec">
            <h3>질문</h3>
            <p className="dt-text qs-body">{q.body}</p>
            {q.blocked_step && <p className="ib-sub">멈춘 단계: {q.blocked_step}</p>}
            {q.options.length > 0 && (
              <ul className="qs-options">
                {q.options.map((o) => (
                  <li key={o}>{o}</li>
                ))}
              </ul>
            )}
          </section>
          <section className="dt-sec">
            <h3>왜 나에게 왔나</h3>
            <p className="dt-text">
              「{kindLabel(q.kind)}」 질문이라 {roleLabel(q.routed_role)}에게 왔다(질문한 쪽: {askerLabel(q)}). 답이 오기 전까지 작업에 「질문 답 대기」가 붙는다.
              기한이 지나도 답이나 승인으로 치지 않고 다시 알린다.
            </p>
          </section>
          <section className="dt-sec">
            <h3>할 수 있는 일</h3>
            {detail.can_answer ? (
              <AnswerForm key={q.id} q={q} work={work.id} canWithdraw={detail.can_withdraw} onDone={onDone} onError={onError} api={api} />
            ) : (
              <p className="ib-term">
                <Icon name="lock" />
                <span>이 질문은 이미 {stateLabel(q)} 상태다.</span>
              </p>
            )}
          </section>
        </div>
        <aside className="dt-aside" aria-labelledby={`${titleId}-w`}>
          <h3 id={`${titleId}-w`}>작업</h3>
          <a className="ib-ses" href={workHref(work.id, work.stage)}>
            <span className="ib-ses-l1">
              <Icon name="work" />
              <span className="lrow-state">{stageLabel(work.stage)}</span>
              {work.external_ref && <code>{work.external_ref}</code>}
            </span>
            <span className="ib-ses-t">{work.title}</span>
          </a>
          {q.run_id && (
            <p className="ib-sub">
              실행 <code>{q.run_id}</code>
            </p>
          )}
          <h3>전달 이력</h3>
          <ol className="qs-hist">
            {detail.routing_history.map((h, i) => (
              <li key={i}>
                {h.at && (
                  <time className="num" dateTime={h.at}>
                    {at(h.at)}
                  </time>
                )}
                <span>{h.event === 'overdue' ? '기한 지남, 같은 사람에게 다시 알림' : `${h.to ?? ''}에게 보냄(${roleLabel(h.role ?? '')})`}</span>
              </li>
            ))}
          </ol>
        </aside>
      </div>
    </article>
  );
}

/** The answer, whether it needs a document change, and the records it led to; or taking the question back. */
function AnswerForm({
  q,
  work,
  canWithdraw,
  onDone,
  onError,
  api,
}: {
  q: Question;
  work: string;
  canWithdraw: boolean;
  onDone: Props['onDone'];
  onError: (e: unknown) => void;
  api: NonNullable<Props['api']>;
}) {
  const [text, setText] = useState('');
  const [needs, setNeeds] = useState(false);
  const [doc, setDoc] = useState('');
  const [decision, setDecision] = useState('');
  const [approval, setApproval] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const refsId = useId();
  const href = `#work?item=${encodeURIComponent(work)}`;
  const body = answerBody(text, needs, { doc, decision, approval });

  async function run(what: () => Promise<Question>, done: (q: Question) => string) {
    setBusy(true);
    try {
      const out = await what();
      setError(null);
      onDone(q.id, done(out), href);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      onError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <form
      className="ib-reason qs-answer"
      onSubmit={(e) => {
        e.preventDefault();
        if (body) void run(() => api.answer(q.id, body), (out) => `답했다. 질문은 ${stateLabel(out)}`);
      }}
    >
      <label>
        답
        <textarea className="field" rows={4} maxLength={4000} value={text} onChange={(e) => setText(e.target.value)} placeholder="예: 90일이다. 근거는 보존 정책 문서 판 3" />
      </label>
      <label className="check">
        <input type="checkbox" checked={needs} onChange={(e) => setNeeds(e.target.checked)} />
        <span>이 답 때문에 요구·정책·계약 문서를 바꿔야 한다(바꾼 판을 기록하면 해결된다)</span>
      </label>
      <p className="qs-refs-t" id={refsId}>
        이어진 기록 번호(선택)
      </p>
      <div className="qs-refs" role="group" aria-labelledby={refsId}>
        <label>
          문서판·문서
          <input className="field rv-mono" value={doc} onChange={(e) => setDoc(e.target.value)} placeholder="dvr_… 또는 doc_…" />
        </label>
        <label>
          결정
          <input className="field rv-mono" value={decision} onChange={(e) => setDecision(e.target.value)} placeholder="dcs_…" />
        </label>
        <label>
          승인
          <input className="field rv-mono" value={approval} onChange={(e) => setApproval(e.target.value)} placeholder="apv_…" />
        </label>
      </div>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={busy || body === null} aria-busy={busy || undefined}>
          {busy ? '보내는 중' : '답 보내기'}
        </button>
        {canWithdraw && (
          <button type="button" className="btn" disabled={busy} onClick={() => void run(() => api.withdraw(q.id), () => '질문을 거뒀다')}>
            질문 거두기
          </button>
        )}
      </div>
      {body === null && <span className="ctl-why">답을 적어야 보낼 수 있다.</span>}
      {q.run_id && <span className="ctl-why">AI 실행의 질문이다. 답한 뒤 작업 상세에서 「이어서 맡기기」를 누르면 답이 다음 실행의 지시에 들어간다.</span>}
      {error && (
        <p role="alert" className="ib-refused">
          <Icon name="alert" />
          <span>보내지 못했다: {error}</span>
        </p>
      )}
    </form>
  );
}
