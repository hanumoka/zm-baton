import { useId, useState, type Ref } from 'react';
import type { Account, AccountActionName, AccountDetail as Detail } from '../accountsApi';
import { Icon } from '../icons/Icon';
import { RowSkeleton } from '../ui/Group';
import { at } from '../workModel';
import { endLabel } from '../work/RunRow';
import {
  AFFILIATION_LABELS,
  causeLabel,
  count,
  dailyShare,
  duration,
  KIND_LABELS,
  METHOD_LABELS,
  runLimitsLine,
  SOURCE_LABELS,
  tokensLine,
  usd,
} from './accountView';

/** What a person can do from the detail; the screen runs them against the server. */
export type AccountActions = {
  edit: () => void;
  run: (name: Exclude<AccountActionName, 'edit'>) => Promise<void>;
};

type Props = {
  /** The account as the list has it, drawn at once while its detail loads. */
  account: Account;
  detail: Detail | null;
  actions?: AccountActions;
  error?: string | null;
  titleRef?: Ref<HTMLHeadingElement>;
  inSheet?: boolean;
};

const HISTORY: Record<string, string> = {
  'account.registered': '등록',
  'account.updated': '고침',
  'account.disabled': '비활성화',
  'account.enabled': '다시 쓰기',
  'account.billing_stopped': '추가 과금 신호로 멈춤',
  'account.billing_resumed': '추가 과금 멈춤을 풂',
  'account.limit_reached': '공급자 사용 한도에 닿음',
};

/**
 * One AI account (FN-0501, FN-0409): what kind it is and where the device keeps its sign-in, why it
 * is stopped when it is, what it may do, today's use against its daily limit, its limits, the last
 * seven days and its newest runs, and beside them its history. Never a key: there is none to show.
 */
export function AccountDetailView({ account, detail, actions, error = null, titleRef, inSheet = false }: Props) {
  const titleId = useId();
  const d = detail !== null && detail.account.id === account.id ? detail : null;
  const a = d ? d.account : account;
  return (
    <article className="dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>AI 계정</span>
          <span aria-hidden="true">/</span>
          <span>{KIND_LABELS[a.auth_kind]}</span>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {a.name}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>종류</dt>
          <dd>{KIND_LABELS[a.auth_kind]}</dd>
        </div>
        <div>
          <dt>인증 방식</dt>
          <dd>{METHOD_LABELS[a.auth_method] ?? a.auth_method}</dd>
        </div>
        <div>
          <dt>소속</dt>
          <dd>{AFFILIATION_LABELS[a.affiliation]}</dd>
        </div>
        <div>
          <dt>자격 위치</dt>
          <dd>
            {SOURCE_LABELS[a.auth_source] ?? a.auth_source}
            <span className="hr-dim">{a.device_name ? `PC ${a.device_name}` : '맡긴 PC'}</span>
          </dd>
        </div>
      </dl>
      {a.block && (
        <p className={a.status === 'disabled' ? 'acc-off' : 'hr-warn acc-block'} role="status">
          <Icon name={a.status === 'disabled' ? 'lock' : 'alert'} />
          <span>
            <strong>{a.status === 'disabled' ? '비활성화했다' : `멈춤: ${causeLabel(a.block.cause)}`}</strong> {a.block.reason}
          </span>
        </p>
      )}
      {d === null ? <RowSkeleton rows={3} /> : <Body d={d} actions={actions} error={error} />}
    </article>
  );
}

function Body({ d, actions, error }: { d: Detail; actions?: AccountActions; error: string | null }) {
  const a = d.account;
  const share = dailyShare(a);
  return (
    <div className="dt-grid">
      <div className="dt-main">
        {error && (
          <p role="alert" className="wd-refused">
            <Icon name="alert" />
            <span>{error}</span>
          </p>
        )}
        {actions && <Actions d={d} actions={actions} />}
        <section className="dt-sec">
          <h3>오늘 사용량</h3>
          <p className="acc-today">
            <strong className="num">{usd(a.today.cost_usd)}</strong>
            <span>
              {a.limits.daily_cost_limit_usd === null ? '하루 상한 없음' : `하루 상한 ${usd(a.limits.daily_cost_limit_usd)}의 ${share}%`}, 실행{' '}
              <span className="num">{a.today.runs}</span>회, 턴 <span className="num">{count(a.today.turns)}</span>
            </span>
          </p>
          {a.today.runs > 0 && <p className="acc-dim num">{tokensLine(a.today)}</p>}
          <p className="acc-note">비용은 실행기가 가격표로 환산한 값이다. 청구액이 아니며, 구독 계정이면 돈이 나가지 않는다. 상한을 재는 데만 쓴다.</p>
        </section>
        <section className="dt-sec">
          <h3>상한</h3>
          <dl className="dt-facts acc-limits">
            <div>
              <dt>실행마다</dt>
              <dd>{runLimitsLine(a.limits)}</dd>
            </div>
            <div>
              <dt>하루</dt>
              <dd>{usd(a.limits.daily_cost_limit_usd)}</dd>
            </div>
          </dl>
          <ul className="acc-rules">
            <li>실행마다의 턴과 비용은 Claude Code가 스스로 지키고(--max-turns, --max-budget-usd), 시간은 연결 프로그램이 잰다. 닿으면 그 실행이 멈추고 작업에 「한도 리셋 대기」가 붙는다.</li>
            <li>하루 상한에 닿으면 그 계정으로 새 실행을 시작하지 않고, 돌던 실행도 멈춘다. 실행의 비용 상한은 그날 남은 만큼으로 줄어든다.</li>
            <li>최근 60분에 하루 상한의 절반 넘게 쓰면 비정상 소비로 보고 같은 방법으로 멈춘다.</li>
          </ul>
        </section>
        <section className="dt-sec">
          <h3>최근 7일</h3>
          {d.days.length === 0 ? (
            <p className="dt-none">아직 이 계정으로 사용량을 보고한 실행이 없다.</p>
          ) : (
            <ol className="acc-days">
              {d.days.map((day) => (
                <li key={day.day}>
                  <span className="acc-day">{day.day}</span>
                  <span className="num">
                    {usd(day.totals.cost_usd)}, 실행 {day.totals.runs}회, 턴 {count(day.totals.turns)}, {duration(day.totals.duration_ms)}
                  </span>
                  <small className="acc-dim num">{tokensLine(day.totals)}</small>
                </li>
              ))}
            </ol>
          )}
        </section>
        <section className="dt-sec">
          <h3>최근 실행</h3>
          {d.runs.length === 0 ? (
            <p className="dt-none">이 계정으로 맡긴 실행이 없다.</p>
          ) : (
            <ol className="acc-runs">
              {d.runs.map((r) => (
                <li key={r.id}>
                  <a href={`#work?item=${encodeURIComponent(r.work_item_id)}`}>{r.work_item_title}</a>
                  <span className="acc-dim">
                    {r.state === 'ended' ? endLabel(r.end_reason) : '진행 중'}
                    {r.cost_usd !== null && `, ${usd(r.cost_usd)}`}
                    {r.turns !== null && `, 턴 ${r.turns}`}
                  </span>
                  {r.stopped_by && <small className="hr-flag">{causeLabel(r.stopped_by)}</small>}
                </li>
              ))}
            </ol>
          )}
        </section>
        {(a.purpose || a.data_terms) && (
          <section className="dt-sec">
            <h3>목적과 자료 처리 조건</h3>
            {a.purpose && <p className="dt-text">{a.purpose}</p>}
            {a.data_terms && <p className="dt-text acc-dim">{a.data_terms}</p>}
          </section>
        )}
      </div>
      <aside className="dt-aside" aria-label="이력">
        <h3>이력</h3>
        {d.history.length === 0 ? (
          <p className="dt-none">아직 없다.</p>
        ) : (
          <ol className="wd-hist">
            {d.history.map((h, i) => (
              <li key={i}>
                <span>{HISTORY[h.action] ?? h.action}</span>
                <time dateTime={h.at}>{at(h.at)}</time>
              </li>
            ))}
          </ol>
        )}
      </aside>
    </div>
  );
}

const ACTION_TEXT: Record<Exclude<AccountActionName, 'edit'>, string> = {
  disable: '비활성화하기',
  enable: '다시 쓰기',
  resume_billing: '추가 과금 멈춤 풀기',
};

/**
 * The account's next steps. Disabling and lifting an extra-billing stop ask once more. A step the
 * server does not allow now is not drawn: each pair (disable and enable) has one that applies.
 */
function Actions({ d, actions }: { d: Detail; actions: AccountActions }) {
  const [asking, setAsking] = useState<'disable' | 'resume_billing' | null>(null);
  const [busy, setBusy] = useState(false);
  const allowed = (n: AccountActionName) => d.actions.find((x) => x.name === n)?.allowed === true;
  const go = (n: Exclude<AccountActionName, 'edit'>) => {
    setBusy(true);
    setAsking(null);
    void actions.run(n).finally(() => setBusy(false));
  };
  return (
    <section className="dt-sec">
      <h3>할 수 있는 일</h3>
      {asking ? (
        <div className="hr-wait">
          <p className="dt-none">
            {asking === 'disable'
              ? '이 계정으로는 새 일을 맡기지 않는다. 지금 도는 실행은 그대로 끝까지 돈다. 기록은 남는다.'
              : '공급자 쪽에서 추가 과금이 꺼져 있는지 확인했을 때만 푼다. 풀면 이 계정으로 다시 맡길 수 있다. 멈췄던 작업은 「이어서 맡기기」로 다시 맡긴다.'}
          </p>
          <button type="button" className="btn btn-primary" onClick={() => go(asking)}>
            {ACTION_TEXT[asking]}
          </button>
          <button type="button" className="btn" onClick={() => setAsking(null)}>
            그만두기
          </button>
        </div>
      ) : (
        <div className="wd-acts">
          <button type="button" className="btn" onClick={actions.edit} disabled={busy}>
            고치기
          </button>
          {allowed('resume_billing') && (
            <button type="button" className="btn btn-primary" onClick={() => setAsking('resume_billing')} disabled={busy}>
              {ACTION_TEXT.resume_billing}
            </button>
          )}
          {allowed('enable') ? (
            <button type="button" className="btn btn-primary" onClick={() => go('enable')} disabled={busy} aria-busy={busy || undefined}>
              {ACTION_TEXT.enable}
            </button>
          ) : (
            <button type="button" className="btn" onClick={() => setAsking('disable')} disabled={busy || !allowed('disable')}>
              {ACTION_TEXT.disable}
            </button>
          )}
        </div>
      )}
    </section>
  );
}
