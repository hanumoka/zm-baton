import { useId, type Ref } from 'react';
import { Icon } from '../icons/Icon';
import { executorLabel, inboxHref, repoName, shortId, since, stateLabel } from '../inboxModel';
import type { InboxItem, SessionView } from '../sessionsApi';
import { Path } from '../ui/Path';
import { at } from '../workModel';
import { conflictItem, workLine } from './sessionView';

type Props = {
  session: SessionView;
  /** What waits for me, to link a conflicting file to its item there; null before the first read. */
  inbox: InboxItem[] | null;
  now: number;
  /** What the session works on takes the focus when Enter opens it from the list. */
  titleRef?: Ref<HTMLHeadingElement>;
  /** In the narrow sheet the sheet's bar already says where this is. */
  inSheet?: boolean;
};

/**
 * One registered session (contract 「직접 연 AI 세션을 서버에 등록한다」): what it works on, its
 * state, executor and device, then where it works, when it registered and last signalled, the
 * files that overlap with another session (each linked to its item on the first screen) and the
 * files it touched last. The server only knows what the session reports, which the foot says.
 */
export function SessionDetail({ session: s, inbox, now, titleRef, inSheet = false }: Props) {
  const titleId = useId();
  const work = workLine(s);
  const conflicted = new Set(s.conflicted_files);
  const prefix = s.repository?.prefix?.replace(/\/+$/, '') ?? '';
  return (
    <article className="dt ss-dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>세션 현황</span>
          <span aria-hidden="true">/</span>
          <code>{shortId(s.id)}</code>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className={work.shown ? 'dt-title' : 'dt-title ss-none'} tabIndex={-1}>
        {work.text}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>상태</dt>
          <dd className="ss-st">{stateLabel(s.state)}</dd>
        </div>
        <div>
          <dt>실행기</dt>
          <dd>
            {executorLabel(s.executor)}
            {s.executor_version ? <code>{s.executor_version}</code> : <span className="ss-quiet">판을 알리지 않았다</span>}
          </dd>
        </div>
        <div>
          <dt>장치</dt>
          <dd>{s.device_name}</dd>
        </div>
      </dl>
      <div className="dt-grid">
        <div className="dt-main">
          <section className="dt-sec">
            <h3>세션 정보</h3>
            <dl className="ss-props">
              <div>
                <dt>세션 번호</dt>
                <dd>
                  <code className="ss-id">{s.id}</code>
                </dd>
              </div>
              <div>
                <dt>저장소</dt>
                <dd>
                  {repoName(s)}
                  {prefix && <code className="ss-quiet">{prefix}/</code>}
                </dd>
              </div>
              <div>
                <dt>브랜치</dt>
                <dd>{s.repository?.branch ? <code>{s.repository.branch}</code> : <span className="ss-quiet">알리지 않았다</span>}</dd>
              </div>
              <div>
                <dt>하는 일 올림</dt>
                <dd>
                  {s.shared && s.current_work_at ? (
                    <When iso={s.current_work_at} now={now} />
                  ) : (
                    <span className="ss-quiet">{s.shared ? '아직 올리지 않았다' : '공유하지 않아 보이지 않는다'}</span>
                  )}
                </dd>
              </div>
              <div>
                <dt>등록</dt>
                <dd>
                  <When iso={s.created_at} now={now} />
                </dd>
              </div>
              <div>
                <dt>마지막 신호</dt>
                <dd>
                  <When iso={s.last_seen_at} now={now} />
                </dd>
              </div>
            </dl>
          </section>
          <section className="dt-sec">
            <h3>
              겹친 파일 <span className="num">{s.conflicted_files.length}</span>
            </h3>
            {s.conflicted_files.length === 0 ? (
              <p className="dt-none">다른 세션과 겹친 파일이 없다.</p>
            ) : (
              <ul className="ss-files">
                {s.conflicted_files.map((f) => (
                  <li key={f}>
                    <Overlap path={f} item={conflictItem(inbox, s.id, f)} inboxRead={inbox !== null} />
                  </li>
                ))}
              </ul>
            )}
          </section>
          <p className="ss-note">
            <Icon name="alert" />
            <span>등록하지 않았거나 등록을 끈 세션은 여기 없고 충돌 판정에서도 빠진다. 셸 명령으로 바꾼 파일은 보고되지 않는다.</span>
          </p>
        </div>
        <aside className="dt-aside" aria-labelledby={`${titleId}-f`}>
          <h3 id={`${titleId}-f`}>
            최근 고친 파일 <span className="num">{s.touched_files.length}</span>
          </h3>
          {s.touched_files.length === 0 ? (
            <p className="dt-none">고친 파일 보고가 없다.</p>
          ) : (
            <ul className="ss-touched">
              {s.touched_files.map((f) => (
                <li key={f} className={conflicted.has(f) ? 'is-ovl' : undefined}>
                  <Path path={f} />
                  {conflicted.has(f) && (
                    <span className="ss-ovl">
                      <Icon name="conflict" />
                      겹침
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
          <p className="ss-sub">최근에 고친 것부터 20개까지 보인다.</p>
        </aside>
      </div>
    </article>
  );
}

/** 「3분 전」 and the time it happened. */
function When({ iso, now }: { iso: string; now: number }) {
  return (
    <>
      <span>{since(iso, now)}</span>
      <time className="ss-quiet num" dateTime={iso}>
        {at(iso)}
      </time>
    </>
  );
}

/** A file this session and another both touched, linked to its item on the first screen while it still waits there. */
function Overlap({ path, item, inboxRead }: { path: string; item: InboxItem | null; inboxRead: boolean }) {
  return (
    <div className="ss-ovl-row">
      <p className="ss-ovl-path">
        <span className="ss-ovl">
          <Icon name="conflict" />
          겹침
        </span>
        <Path path={path} />
      </p>
      {item ? (
        <a className="btn" href={inboxHref(item.id)}>
          나를 기다리는 것에서 판정하기
        </a>
      ) : (
        <p className="ss-sub">{inboxRead ? '확인함으로 남은 충돌이다. 나를 기다리는 것에는 열린 항목이 없다.' : '나를 기다리는 것을 읽는 중이다.'}</p>
      )}
    </div>
  );
}
