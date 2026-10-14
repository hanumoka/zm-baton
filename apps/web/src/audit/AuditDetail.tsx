import { useId, type Ref } from 'react';
import { EXPORT_HREF, type AuditEntry, type RetentionView } from '../auditApi';
import { Icon } from '../icons/Icon';
import { RowSkeleton } from '../ui/Group';
import { at } from '../workModel';
import { ACTOR_LABELS, actionLabel, actorClass, actorIcon, actorNote, detailRows, keepLabel, targetHref, targetLabel, targetLinkLabel } from './auditView';

type Props = {
  entry: AuditEntry;
  /** Narrows the list to this row's target (the chip 「대상 번호」). */
  onTarget: (targetId: string) => void;
  titleRef?: Ref<HTMLHeadingElement>;
  inSheet?: boolean;
};

/**
 * One audit row: what happened as the title, then when, who, on whose behalf and the action's code,
 * the target with a link to its own screen, and the details the server kept as a table. A row is
 * never changed; only a retention period the person set removes old ones.
 */
export function AuditDetail({ entry: e, onTarget, titleRef, inSheet = false }: Props) {
  const titleId = useId();
  const who = actorClass(e);
  const href = targetHref(e);
  const rows = detailRows(e.details);
  return (
    <article className="dt au-dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>감사 기록</span>
          <span aria-hidden="true">/</span>
          <span>{targetLabel(e.target_kind)}</span>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {actionLabel(e.action)}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>시각</dt>
          <dd>
            <time className="num" dateTime={e.at}>
              {at(e.at)}
            </time>
          </dd>
        </div>
        <div>
          <dt>행위자</dt>
          <dd className="au-who">
            <Icon name={actorIcon(who)} />
            {ACTOR_LABELS[who]}
            <code>{e.actor_id}</code>
          </dd>
        </div>
        {/* Who called it, when someone else acted for them (an AI, the server); the same person is said once. */}
        {e.on_behalf_of_user_id && e.on_behalf_of_user_id !== e.actor_id && (
          <div>
            <dt>부른 사람</dt>
            <dd>
              <code>{e.on_behalf_of_user_id}</code>
            </dd>
          </div>
        )}
        <div>
          <dt>동작</dt>
          <dd>
            <code>{e.action}</code>
          </dd>
        </div>
      </dl>
      <div className="dt-grid is-solo">
        <div className="dt-main">
          <section className="dt-sec">
            <h3>대상</h3>
            {e.target_id === null ? (
              <p className="dt-none">대상이 없는 기록이다(가져오기, 내보내기, 보존 규칙처럼 서버 전체에 한 일).</p>
            ) : (
              <>
                <p className="au-target">
                  {targetLabel(e.target_kind)} <code>{e.target_id}</code>
                </p>
                <p className="au-acts">
                  {href && (
                    <a className="btn" href={href}>
                      {targetLinkLabel(e.target_kind)}
                    </a>
                  )}
                  <button type="button" className="btn" onClick={() => onTarget(e.target_id as string)}>
                    이 대상의 기록만 보기
                  </button>
                </p>
              </>
            )}
          </section>
          <section className="dt-sec">
            <h3>
              남긴 내용 <span className="num">{rows.length}</span>
            </h3>
            {rows.length === 0 ? (
              <p className="dt-none">남긴 내용이 없다.</p>
            ) : (
              <dl className="au-kv">
                {rows.map((r) => (
                  <div key={r.key}>
                    <dt>
                      <code>{r.key}</code>
                    </dt>
                    <dd>{r.json ? <pre className="au-json">{r.value}</pre> : r.value}</dd>
                  </div>
                ))}
              </dl>
            )}
          </section>
          <section className="dt-sec">
            <h3>행위자 종류</h3>
            <p className="dt-text">
              {ACTOR_LABELS[who]}: {actorNote(who)}.
            </p>
            <p className="au-note">감사 기록은 고치지 않는다. 보존 기간을 정했을 때만 그보다 오래된 기록이 지워지고, 지운 일도 한 줄로 남는다.</p>
          </section>
        </div>
      </div>
    </article>
  );
}

/**
 * The retention settings, read only, and the export. Periods are server settings (environment
 * variables): with no person authentication yet, nothing on this screen changes them.
 */
export function RetentionPane({ view, error, titleRef, inSheet = false }: { view: RetentionView | null; error: string | null; titleRef?: Ref<HTMLHeadingElement>; inSheet?: boolean }) {
  const titleId = useId();
  return (
    <article className="dt au-dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>감사 기록</span>
          <span aria-hidden="true">/</span>
          <span>설정</span>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        보존 규칙과 내보내기
      </h2>
      <div className="dt-grid is-solo">
        <div className="dt-main">
          <section className="dt-sec">
            <h3>내보내기</h3>
            <p className="dt-text">서버의 모든 기록을 JSON 파일 하나로 받는다. 기록마다 어디서 왔는지(정본 위치)를 함께 적고, 세션 토큰의 해시만 뺀다. 받은 일도 감사 기록에 남는다.</p>
            <p className="au-acts">
              <a className="btn btn-primary" href={EXPORT_HREF} download>
                내보내기 파일 받기
              </a>
            </p>
          </section>
          <section className="dt-sec">
            <h3>보존 규칙</h3>
            {error ? (
              <p role="alert" className="wd-refused">
                <Icon name="alert" />
                <span>보존 규칙을 읽지 못했다: {error}</span>
              </p>
            ) : view === null ? (
              <RowSkeleton rows={3} />
            ) : (
              <>
                <table className="au-keep">
                  <thead>
                    <tr>
                      <th scope="col">종류</th>
                      <th scope="col">보존</th>
                      <th scope="col">서버 설정</th>
                    </tr>
                  </thead>
                  <tbody>
                    {view.kinds.map((k) => (
                      <tr key={k.kind}>
                        <td>{k.label}</td>
                        <td className="num">{keepLabel(k.days)}</td>
                        <td>
                          <code>{k.setting}</code>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="au-note">
                  {view.last_run ? (
                    <>
                      마지막으로 지운 때 <time dateTime={view.last_run.ran_at}>{at(view.last_run.ran_at)}</time>:{' '}
                      {Object.entries(view.last_run.deleted)
                        .map(([table, n]) => `${table} ${n}건`)
                        .join(', ') || '지운 것 없음'}
                    </>
                  ) : (
                    '아직 지운 적이 없다.'
                  )}
                </p>
                <p className="ib-term">
                  <Icon name="lock" />
                  <span>
                    바꾸기는 서버 설정(환경 변수)으로만 한다. 사람 인증이 생기기 전에는 화면에서 바꾸지 않는다. 방법은 <code>docs/stage1/audit-retention-export.md</code>에 있다.
                  </span>
                </p>
              </>
            )}
          </section>
        </div>
      </div>
    </article>
  );
}
