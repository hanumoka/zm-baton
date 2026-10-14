import { useEffect, useId, useState, type Ref } from 'react';
import { Icon } from '../icons/Icon';
import { BUCKETS, waited } from '../inboxModel';
import { reviewsApi, type ReviewDecision, type ReviewDecisionOut, type ReviewDetail as Detail, type ReviewOutcome } from '../reviewsApi';
import type { InboxItem } from '../sessionsApi';
import { EmptyState } from '../ui/EmptyState';
import { RowSkeleton } from '../ui/Group';
import { at, STAGES } from '../workModel';
import { autoLabel, decisionBody, humanLabel, matches, reasonLabel, reviewTitle, reviewWhy } from './reviewModel';

type Props = {
  item: InboxItem;
  now: number;
  /** The server took a verdict: what it did, and the work item it concerns (or the fixing one it made). */
  onDecided: (id: string, effect: ReviewOutcome, href: string | null) => void;
  onError: (e: unknown) => void;
  titleRef?: Ref<HTMLHeadingElement>;
  inSheet?: boolean;
  /** For tests: what reads the review and sends a verdict. */
  api?: Pick<typeof reviewsApi, 'detail' | 'decide'>;
};

const workHref = (id: string, stage: string) => `#work?${stage === 'done' || stage === 'cancelled' ? 'closed=1&' : ''}item=${encodeURIComponent(id)}`;

const stageLabel = (stage: string) => STAGES.find((s) => s.stage === stage)?.label ?? stage;

/**
 * A review request on the first screen (FN-0411): why it came to me (the risk patterns and the
 * files they caught, or the sample), the result it is about (the run's branch, its pinned snapshot
 * and changed files), and the verdict. Rejecting needs a reason; for a sample it can also put a
 * path forward as a risk path candidate, which a person adds to the list later.
 */
export function ReviewDetail({ item, now, onDecided, onError, titleRef, inSheet = false, api = reviewsApi }: Props) {
  const titleId = useId();
  const [detail, setDetail] = useState<Detail | null>(null);
  const [problem, setProblem] = useState<string | null>(null);
  const bucket = BUCKETS.find((b) => b.bucket === item.bucket)?.label ?? '나를 기다리는 것';

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

  const crumb = !inSheet && (
    <nav className="dt-crumb" aria-label="위치">
      <span>나를 기다리는 것</span>
      <span aria-hidden="true">/</span>
      <span>{item.promoted_at ? '오래 기다린 검토' : bucket}</span>
    </nav>
  );

  if (detail === null) {
    return (
      <article className="dt ib-dt rv-dt" aria-labelledby={titleId}>
        {crumb}
        <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
          {item.summary}
        </h2>
        {problem ? (
          <EmptyState kind="error" title="검토 요청을 읽지 못했다">
            {problem}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 목록에서 다시 고른다.
          </EmptyState>
        ) : (
          <RowSkeleton rows={3} />
        )}
      </article>
    );
  }

  return <ReviewBody detail={detail} item={item} now={now} onDecided={onDecided} onError={onError} titleRef={titleRef} inSheet={inSheet} send={api.decide} />;
}

/** The loaded request, drawn from its detail alone so it can be drawn in tests. */
export function ReviewBody({
  detail,
  item,
  now,
  onDecided,
  onError,
  titleRef,
  inSheet = false,
  send,
}: Omit<Props, 'api'> & { detail: Detail; send: (id: string, body: ReviewDecision) => Promise<ReviewDecisionOut> }) {
  const titleId = useId();
  const bucket = BUCKETS.find((b) => b.bucket === item.bucket)?.label ?? '나를 기다리는 것';
  const crumb = !inSheet && (
    <nav className="dt-crumb" aria-label="위치">
      <span>나를 기다리는 것</span>
      <span aria-hidden="true">/</span>
      <span>{item.promoted_at ? '오래 기다린 검토' : bucket}</span>
    </nav>
  );
  const run = detail.run;
  const work = detail.work_item;
  const found = matches(detail);
  const caught = new Set(found.flatMap((m) => m.files));
  const human = humanLabel(run?.human_review_state, []);
  const auto = autoLabel(run?.auto_check_state);
  return (
    <article className="dt ib-dt rv-dt" aria-labelledby={titleId}>
      {crumb}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {reviewTitle(detail)}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>종류</dt>
          <dd className="ib-kind">{reasonLabel(detail.reason)}</dd>
        </div>
        <div>
          <dt>기다린 시간</dt>
          <dd>
            <span className="num">{waited(item.waiting_since, now)}</span>
            <time className="ib-when num" dateTime={item.waiting_since}>
              {at(item.waiting_since)}부터
            </time>
          </dd>
        </div>
        {(human || auto) && (
          <div>
            <dt>검토 상태</dt>
            <dd>{[human && `사람 ${human}`, auto].filter(Boolean).join(', ')}</dd>
          </div>
        )}
        {work && (
          <div>
            <dt>작업 단계</dt>
            <dd>{stageLabel(work.stage)}</dd>
          </div>
        )}
      </dl>
      <div className="dt-grid">
        <div className="dt-main">
          <section className="dt-sec">
            <h3>왜 나에게 왔나</h3>
            <p className="dt-text">{reviewWhy(detail)}</p>
            {found.length > 0 && (
              <ul className="rv-matches">
                {found.map((m) => (
                  <li key={m.pattern}>
                    <span className="rv-pat">
                      <span className="rv-pat-l">패턴</span>
                      <code>{m.pattern}</code>
                    </span>
                    <ul className="hr-files">
                      {m.files.map((f) => (
                        <li key={f}>
                          <code>{f}</code>
                        </li>
                      ))}
                    </ul>
                  </li>
                ))}
              </ul>
            )}
          </section>
          <section className="dt-sec">
            <h3>할 수 있는 일</h3>
            {detail.state === 'open' && detail.effects ? (
              <Verdict key={detail.id} detail={detail} onDecided={onDecided} onError={onError} send={send} />
            ) : (
              <p className="ib-term">
                <Icon name="lock" />
                <span>이 요청은 이미 {detail.state === 'superseded' ? '새 실행 결과로 대체됐다' : detail.state === 'approved' ? '승인됐다' : '반려됐다'}.</span>
              </p>
            )}
          </section>
          {run && (
            <section className="dt-sec">
              <h3>검토할 결과</h3>
              <dl className="rv-target">
                {run.workspace?.branch && (
                  <div>
                    <dt>실행 브랜치</dt>
                    <dd>
                      <code>{run.workspace.branch}</code>
                    </dd>
                  </div>
                )}
                {run.workspace?.base_commit && (
                  <div>
                    <dt>기준</dt>
                    <dd>
                      {run.workspace.base_ref ?? '지금 커밋'} <code>{run.workspace.base_commit.slice(0, 10)}</code>
                    </dd>
                  </div>
                )}
                <div>
                  <dt>검토 대상</dt>
                  <dd>
                    {detail.target_kind === 'run_snapshot' ? (
                      <>
                        스냅샷 커밋 <code>{detail.target_ref.slice(0, 12)}</code>
                      </>
                    ) : (
                      <span className="rv-unpinned">
                        <Icon name="alert" />
                        대상을 고정할 수 없음. 승인 기록에 「대상 고정 안 됨」이 함께 남는다
                      </span>
                    )}
                  </dd>
                </div>
                {(run.repo_name || run.device_name) && (
                  <div>
                    <dt>저장소</dt>
                    <dd>
                      {run.repo_name}
                      {run.device_name && <span className="hr-dim">{run.device_name}</span>}
                    </dd>
                  </div>
                )}
              </dl>
              <p className="hr-label">
                바뀐 파일 <span className="num">{run.files.length}</span>개{run.truncated ? ' (앞부분만)' : ''}
              </p>
              {run.files.length > 0 && (
                <ul className="hr-files">
                  {run.files.map((f) => (
                    <li key={f}>
                      <code>{f}</code>
                      {caught.has(f) && <small className="hr-flag">위험 경로</small>}
                    </li>
                  ))}
                </ul>
              )}
              {detail.target_kind === 'run_snapshot' && (
                <p className="ib-sub">
                  변경은 그 PC에서 <code>git diff {run.workspace?.base_commit?.slice(0, 10) ?? '<기준>'} {detail.target_ref.slice(0, 10)}</code>로 본다. 커밋하지 않은 변경도
                  스냅샷에 들어 있다.
                </p>
              )}
            </section>
          )}
        </div>
        {work && (
          <aside className="dt-aside" aria-labelledby={`${titleId}-w`}>
            <h3 id={`${titleId}-w`}>작업</h3>
            <a className="ib-ses" href={workHref(work.id, work.stage)}>
              <span className="ib-ses-l1">
                <Icon name="work" />
                <span className="lrow-state">{stageLabel(work.stage)}</span>
                {work.external_ref && <code>{work.external_ref}</code>}
              </span>
              <span className="ib-ses-t">{work.title}</span>
              {work.project && <span className="ib-sub">{work.project.name}</span>}
            </a>
            {run && <p className="ib-sub">실행 <code>{run.id}</code></p>}
          </aside>
        )}
      </div>
    </article>
  );
}

/**
 * Approve or reject. Rejecting asks for the reason first and sends nothing without one; a sample's
 * rejection offers a risk path candidate, prefilled with the server's suggestion and left out when cleared.
 */
function Verdict({
  detail,
  onDecided,
  onError,
  send,
}: {
  detail: Detail;
  onDecided: Props['onDecided'];
  onError: (e: unknown) => void;
  send: (id: string, body: ReviewDecision) => Promise<ReviewDecisionOut>;
}) {
  const [rejecting, setRejecting] = useState(false);
  const [reason, setReason] = useState('');
  const [proposal, setProposal] = useState(detail.suggested_pattern ?? '');
  const [busy, setBusy] = useState<'approved' | 'rejected' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const formId = useId();
  const effects = detail.effects!;
  const sample = detail.reason === 'sample';

  async function decide(decision: 'approved' | 'rejected') {
    const body = decisionBody(detail, decision, reason, sample ? proposal : '');
    if (body === null) {
      setRejecting(true);
      return;
    }
    setBusy(decision);
    try {
      const out = await send(detail.id, body);
      setError(null);
      const work = out.fix_work_item_id ?? detail.work_item?.id ?? null;
      onDecided(detail.id, out.effect, work ? `#work?item=${encodeURIComponent(work)}` : null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      onError(e);
    } finally {
      setBusy(null);
    }
  }

  const blank = reason.trim() === '';
  return (
    <>
      <div className="ib-acts" role="group" aria-label="검토 판정">
        <button
          type="button"
          className={rejecting ? 'btn' : 'btn btn-primary'}
          disabled={busy !== null}
          aria-busy={busy === 'approved' ? 'true' : undefined}
          onClick={() => void decide('approved')}
        >
          {busy === 'approved' ? '보내는 중' : '승인하기'}
          <small>{effects.approve}</small>
        </button>
        <button
          type="button"
          className="btn"
          disabled={busy !== null}
          aria-expanded={rejecting}
          aria-controls={rejecting ? formId : undefined}
          onClick={() => setRejecting(true)}
        >
          반려하기
          <small>{effects.reject}</small>
        </button>
      </div>
      {rejecting && (
        <form
          id={formId}
          className="ib-reason rv-reject"
          onSubmit={(e) => {
            e.preventDefault();
            if (!blank) void decide('rejected');
          }}
        >
          <label>
            반려 이유(고칠 점)
            <textarea
              className="field"
              rows={3}
              maxLength={1000}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="예: 권한 설정 파일은 고치지 않는다. 화면 글만 바꾼다"
              autoFocus
            />
          </label>
          {sample && (
            <label>
              위험 목록 후보(선택)
              <input className="field rv-mono" value={proposal} onChange={(e) => setProposal(e.target.value)} placeholder="예: src/pay/**" />
              <span className="ctl-why">
                AI가 놓친 종류면 이 경로를 후보로 남긴다. 저절로 더하지 않는다. 위험 목록 화면에서 「목록에 더하기」를 누를 때 더한다. 비우면 남기지 않는다.
              </span>
            </label>
          )}
          <div className="wd-acts">
            <button type="submit" className="btn btn-primary" disabled={busy !== null || blank} aria-busy={busy === 'rejected' ? 'true' : undefined}>
              {busy === 'rejected' ? '보내는 중' : '반려로 남기기'}
            </button>
            <button type="button" className="btn" onClick={() => setRejecting(false)}>
              그만두기
            </button>
          </div>
          {blank && <span className="ctl-why">이유를 적어야 보낼 수 있다. 서버가 이유 없는 반려를 받지 않는다.</span>}
        </form>
      )}
      {error && (
        <p role="alert" className="ib-refused">
          <Icon name="alert" />
          <span>판정을 보내지 못했다: {error}. 다른 화면에서 이미 처리했으면 목록을 다시 읽는다.</span>
        </p>
      )}
      <p className="ib-sub">판정하면 이 목록에서 빠지고 다음 항목이 열린다. AI는 이 요청을 닫을 수 없다.</p>
    </>
  );
}
