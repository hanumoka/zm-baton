import { useId, useState, type Ref } from 'react';
import { Icon } from '../icons/Icon';
import { BUCKETS, executorLabel, sessionHref, shortId, since, waited } from '../inboxModel';
import type { InboxItem, NoticeStatus, SessionView } from '../sessionsApi';
import { QuestionDetail } from '../question/QuestionDetail';
import { ReviewDetail } from '../review/ReviewDetail';
import { outcomeText } from '../review/reviewModel';
import { at } from '../workModel';
import {
  detailTitle,
  filePath,
  isUsageAlert,
  itemSessions,
  judgement,
  judgementsFor,
  kindOf,
  liveSession,
  notifyLabel,
  sendJudgement,
  sharedWork,
  waitMessage,
  whyMe,
  type ItemSession,
  type Send,
} from './inboxView';
import { RelayBody, RelayState, type RelaySend } from './RelayDetail';
import { isRelay } from './relayView';

type Props = {
  item: InboxItem;
  /** Live sessions, to name what a related session works on and whether it is still live. */
  sessions: SessionView[] | null;
  now: number;
  /** The server took a judgement of this item's notice. */
  onJudged: (id: string) => void;
  /** A judgement the server refused, for the problem list at the top. */
  onError: (e: unknown) => void;
  /** The title takes the focus when Enter opens the item from the list. */
  titleRef?: Ref<HTMLHeadingElement>;
  /** In the narrow sheet the sheet's bar already says where this is. */
  inSheet?: boolean;
  /** For tests: what sends a judgement. */
  send?: Send;
  /** A review verdict (FN-0411) or an answer (FN-0210) went through: what happened; without it, [onJudged] hears it. */
  onDone?: (id: string, text: string, href: string | null) => void;
  /** A relay's answer went through: read the list again. The item stays and shows how it went. */
  onAnswered?: () => void;
  /** For tests: what sends a relay's answer. */
  relaySend?: RelaySend;
};

/**
 * One item that waits for me: what waits, how long, why it is mine, the file and the sessions it
 * names, and what I can do here. A conflict is judged here (확인, 충돌 아님 with a reason, 해결);
 * a waiting session is answered in its own terminal, which closes the item by itself.
 */
export function InboxDetail({ item, sessions, now, onJudged, onError, titleRef, inSheet = false, send, onDone, onAnswered = () => {}, relaySend }: Props) {
  const titleId = useId();
  const done = (text: string, href: string | null) => (onDone ? onDone(item.id, text, href) : onJudged(item.id));
  if (item.kind === 'run_question') {
    return <QuestionDetail item={item} now={now} onDone={(_q, text, href) => done(text, href)} onError={onError} titleRef={titleRef} inSheet={inSheet} />;
  }
  if (item.kind === 'risk_review' || item.kind === 'sample_review') {
    return (
      <ReviewDetail
        item={item}
        now={now}
        onDecided={(_review, effect, href) => done(outcomeText(effect), href)}
        onError={onError}
        titleRef={titleRef}
        inSheet={inSheet}
      />
    );
  }
  const kind = kindOf(item);
  const path = filePath(item);
  const message = waitMessage(item);
  const related = itemSessions(item);
  const repoKey = typeof item.detail?.repo_key === 'string' ? item.detail.repo_key : null;
  const bucket = BUCKETS.find((b) => b.bucket === item.bucket)?.label ?? '나를 기다리는 것';
  const relay = isRelay(item);
  return (
    <article className="dt ib-dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>나를 기다리는 것</span>
          <span aria-hidden="true">/</span>
          <span>{bucket}</span>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {detailTitle(item)}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>종류</dt>
          <dd className="ib-kind">{kind.label}</dd>
        </div>
        {relay && (
          <div>
            <dt>상태</dt>
            <dd>
              <RelayState item={item} now={now} />
            </dd>
          </div>
        )}
        <div>
          <dt>기다린 시간</dt>
          <dd>
            <span className="num">{waited(item.waiting_since, now)}</span>
            <time className="ib-when num" dateTime={item.waiting_since}>
              {at(item.waiting_since)}부터
            </time>
          </dd>
        </div>
        <div>
          <dt>알리는 방식</dt>
          <dd>{notifyLabel(item)}</dd>
        </div>
        {item.repeat_count > 0 && (
          <div>
            <dt>반복</dt>
            <dd className="num">{item.repeat_count}회</dd>
          </div>
        )}
      </dl>
      <div className={related.length > 0 ? 'dt-grid' : 'dt-grid is-solo'}>
        <div className="dt-main">
          {relay && (
            <RelayBody
              key={item.id}
              item={item}
              now={now}
              onAnswered={onAnswered}
              onError={onError}
              live={related[0] ? liveSession(sessions, related[0].id) !== null : false}
              send={relaySend}
            />
          )}
          {!relay && path && (
            <section className="dt-sec">
              <h3>겹친 파일</h3>
              <p className="ib-path">
                <code>{path}</code>
              </p>
              {repoKey && (
                <p className="ib-sub">
                  저장소 <code>{repoKey}</code>
                </p>
              )}
            </section>
          )}
          {isUsageAlert(item) && <UsageLinks item={item} />}
          {!relay && message && (
            <section className="dt-sec">
              <h3>실행기가 보낸 문구</h3>
              <p className="dt-text">{message}</p>
            </section>
          )}
          {!relay && (
            <>
              <section className="dt-sec">
                <h3>왜 나를 기다리나</h3>
                <p className="dt-text">{whyMe(item)}</p>
              </section>
              <section className="dt-sec">
                <h3>할 수 있는 일</h3>
                {item.kind === 'notice' ? (
                  <Judge key={item.id} item={item} onJudged={onJudged} onError={onError} send={send} />
                ) : item.kind === 'session_waiting' ? (
                  <InTerminal session={related[0] ?? null} live={related[0] ? liveSession(sessions, related[0].id) !== null : false} />
                ) : (
                  <p className="ib-term">
                    <Icon name="lock" />
                    <span>이 종류에 여기서 답하는 기능은 아직 없다. 그 세션의 터미널에서 답한다.</span>
                  </p>
                )}
              </section>
            </>
          )}
        </div>
        {related.length > 0 && (
          <aside className="dt-aside" aria-labelledby={`${titleId}-s`}>
            <h3 id={`${titleId}-s`}>
              관련 세션 <span className="num">{related.length}</span>
            </h3>
            <p className="ib-note">누르면 세션 현황에서 그 세션을 연다.</p>
            <ul className="ib-sessions">
              {related.map((s) => (
                <li key={s.id}>
                  <RelatedSession s={s} live={liveSession(sessions, s.id)} known={sessions !== null} now={now} />
                </li>
              ))}
            </ul>
          </aside>
        )}
      </div>
    </article>
  );
}

/** Where a usage notice points: the work item whose run stopped and the AI account, each opened on its own screen. */
function UsageLinks({ item }: { item: InboxItem }) {
  const work = typeof item.detail?.work_item_id === 'string' ? item.detail.work_item_id : null;
  const account = typeof item.detail?.account_id === 'string' ? item.detail.account_id : null;
  const name = typeof item.detail?.account_name === 'string' ? item.detail.account_name : account;
  if (!work && !account) return null;
  return (
    <section className="dt-sec">
      <h3>멈춘 곳</h3>
      <ul className="ib-sessions">
        {work && (
          <li>
            <a href={`#work?item=${encodeURIComponent(work)}`}>작업과 실행 기록 열기</a>
          </li>
        )}
        {account && (
          <li>
            <a href={`#accounts?item=${encodeURIComponent(account)}`}>AI 계정 {name} 열기</a>
          </li>
        )}
      </ul>
    </section>
  );
}

/** A session the item names, as a link to it on the session screen. */
function RelatedSession({ s, live, known, now }: { s: ItemSession; live: SessionView | null; known: boolean; now: number }) {
  const work = sharedWork(live);
  const line = live ? (work ?? (live.shared ? '하는 일을 아직 올리지 않았다' : '하는 일을 공유하지 않는다')) : known ? '지금은 살아 있지 않다' : '세션 목록을 읽는 중';
  return (
    <a className="ib-ses" href={sessionHref(s.id, live !== null || !known)}>
      <span className="ib-ses-l1">
        <Icon name="session" />
        <span className="lrow-state">{executorLabel(live?.executor ?? s.executor)}</span>
        <code>{shortId(s.id)}</code>
        {s.branch && <span className="ib-branch">{s.branch}</span>}
      </span>
      <span className="ib-ses-t">{line}</span>
      {s.touchedAt && <span className="ib-sub">이 파일을 고친 때 {since(s.touchedAt, now)}</span>}
    </a>
  );
}

/** A waiting session is answered in its terminal; the item closes by itself once it moves again. */
function InTerminal({ session, live }: { session: ItemSession | null; live: boolean }) {
  return (
    <>
      <p className="ib-term">
        <Icon name="session" />
        <span>그 세션의 터미널에서 이어 입력하면 저절로 닫힌다. 직접 연 세션이라 여기서는 답을 넣지 않는다.</span>
      </p>
      {session && (
        <p className="ib-acts-row">
          <a className="btn" href={sessionHref(session.id, live)}>
            세션 현황에서 그 세션 열기
          </a>
        </p>
      )}
    </>
  );
}

/**
 * The three judgements of a conflict. 「충돌 아님」 first asks for the reason and sends nothing
 * until there is one. Once the server takes a judgement the list reads again and the next item opens.
 */
function Judge({ item, onJudged, onError, send }: { item: InboxItem; onJudged: (id: string) => void; onError: (e: unknown) => void; send?: Send }) {
  const [asking, setAsking] = useState(false);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState<NoticeStatus | null>(null);
  const [error, setError] = useState<string | null>(null);
  const reasonId = useId();

  async function judge(status: NoticeStatus) {
    if (judgement(status, reason) === null) {
      setAsking(true);
      return;
    }
    setBusy(status);
    try {
      if ((await sendJudgement(item, status, reason, send)) === 'needs-reason') return;
      setError(null);
      onJudged(item.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      onError(e);
    } finally {
      setBusy(null);
    }
  }

  return (
    <>
      <div className="ib-acts" role="group" aria-label="충돌 판정">
        {judgementsFor(item).map((j) => (
          <button
            key={j.status}
            type="button"
            // 확인 is the main action, until the reason form opens and its own button takes that place.
            className={j.status === 'acknowledged' && !asking ? 'btn btn-primary' : 'btn'}
            disabled={busy !== null}
            aria-busy={busy === j.status ? 'true' : undefined}
            aria-expanded={j.status === 'not_a_conflict' ? asking : undefined}
            aria-controls={j.status === 'not_a_conflict' && asking ? reasonId : undefined}
            onClick={() => void judge(j.status)}
          >
            {busy === j.status ? '보내는 중' : j.label}
            <small>{j.result}</small>
          </button>
        ))}
      </div>
      {asking && (
        <ReasonForm
          id={reasonId}
          reason={reason}
          onReason={setReason}
          busy={busy !== null}
          onSubmit={() => void judge('not_a_conflict')}
          onCancel={() => setAsking(false)}
        />
      )}
      {error && (
        <p role="alert" className="ib-refused">
          <Icon name="alert" />
          <span>판정을 보내지 못했다: {error}. 서버가 켜져 있는지 <code>zm-baton status</code>로 본 뒤 다시 누른다.</span>
        </p>
      )}
      <p className="ib-sub">판정하면 이 목록에서 빠지고 다음 항목이 열린다.</p>
    </>
  );
}

/** The reason 「충돌 아님」 needs. Its button stays locked, with the reason why, until something is written. */
export function ReasonForm({
  id,
  reason,
  onReason,
  busy,
  onSubmit,
  onCancel,
}: {
  id?: string;
  reason: string;
  onReason: (reason: string) => void;
  busy: boolean;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const blank = reason.trim() === '';
  return (
    <form
      id={id}
      className="ib-reason"
      onSubmit={(e) => {
        e.preventDefault();
        if (!blank) onSubmit();
      }}
    >
      <label>
        충돌이 아닌 이유
        <input className="field" value={reason} onChange={(e) => onReason(e.target.value)} placeholder="예: 같은 파일의 다른 절을 고친다" autoFocus />
      </label>
      <button type="submit" className="btn btn-primary" disabled={busy || blank}>
        충돌 아님으로 남기기
      </button>
      <button type="button" className="btn" onClick={onCancel}>
        그만두기
      </button>
      {blank && <span className="ctl-why">이유를 적어야 보낼 수 있다. 서버가 이유 없는 「충돌 아님」을 받지 않는다.</span>}
    </form>
  );
}
