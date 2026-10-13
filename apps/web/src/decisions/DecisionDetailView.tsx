import { useId, type Ref } from 'react';
import { DecisionMark } from '../icons/DecisionMark';
import { StageGlyph } from '../icons/StageGlyph';
import { RowSkeleton } from '../ui/Group';
import { ReadOnly } from '../ui/ReadOnly';
import { isBlocked } from '../work/workView';
import type { Decision, DecisionDetail, DecisionRef, WorkItem } from '../workApi';
import { decisionStatusLabel, stageLabel } from '../workModel';
import { otherSections, workHref } from './decisionView';

type Props = {
  /** The decision as the list has it, drawn at once while its detail loads. */
  decision: Decision;
  /** The server's detail; ignored while it belongs to another decision. */
  detail: DecisionDetail | null;
  /** Work that writes this decision's number; null until read. */
  related: WorkItem[] | null;
  /** Go to another decision (a supersession link). */
  onJump: (id: string) => void;
  /** The question takes the focus when Enter opens the decision from the list. */
  titleRef?: Ref<HTMLHeadingElement>;
  /** In the narrow sheet the sheet's bar already says where this is. */
  inSheet?: boolean;
};

/**
 * One decision record (FN-0604): the question, its status, date and projects on one line, and
 * 「읽기만 한다」 with the Git file it comes from (FN-0605: stage 1 changes decisions in Git). Then
 * what was chosen, what replaced what, the rest of the file in its own order, and beside it the
 * work that writes this decision's number.
 */
export function DecisionDetailView({ decision, detail, related, onJump, titleRef, inSheet = false }: Props) {
  const titleId = useId();
  const d = detail !== null && detail.decision.id === decision.id ? detail : null;
  const it = d ? d.decision : decision;
  const work = d && related ? related : [];
  return (
    <article className="dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>결정</span>
          <span aria-hidden="true">/</span>
          {it.external_ref ? <code>{it.external_ref}</code> : <span>번호 없는 결정</span>}
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {it.question}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>상태</dt>
          <dd className="dc-status">
            <DecisionMark status={it.status} />
            {decisionStatusLabel(it.status)}
          </dd>
        </div>
        <div>
          <dt>결정일</dt>
          <dd className="num">{it.decided_on ?? '적혀 있지 않다'}</dd>
        </div>
        <div>
          <dt>프로젝트</dt>
          <dd className="dc-projects">
            {it.projects.length === 0
              ? '없음'
              : it.projects.map((p) => (
                  <span key={p.id}>
                    {p.name}
                    {p.external_ref && <code>{p.external_ref}</code>}
                  </span>
                ))}
          </dd>
        </div>
      </dl>
      {d && (
        <ReadOnly
          gitRef={d.git_ref}
          reason={d.source_of_truth === 'git' ? '정본이 Git 파일이라 그곳에서 고친 뒤 다시 가져온다.' : '1단계는 결정을 웹에서 고치지 않는다.'}
        />
      )}
      {d === null ? (
        <RowSkeleton rows={3} />
      ) : (
        <div className={work.length > 0 ? 'dt-grid' : 'dt-grid is-solo'}>
          <div className="dt-main">
            <section className="dt-sec">
              <h3>고른 안</h3>
              {d.choice ? <p className="dt-text">{d.choice}</p> : <p className="dt-none">「결정」 절을 찾지 못했다. 정본 파일에서 본다.</p>}
            </section>
            {(it.superseded_by || it.supersedes) && (
              <section className="dt-sec">
                <h3>대체 관계</h3>
                <ul className="dc-links">
                  {it.superseded_by && <Relation label="이 결정을 대체한 결정" target={it.superseded_by} onJump={onJump} />}
                  {it.supersedes && <Relation label="이 결정이 대체한 결정" target={it.supersedes} onJump={onJump} />}
                </ul>
              </section>
            )}
            {otherSections(d).map((s, i) => (
              <section key={`${i}-${s.heading}`} className="dt-sec">
                <h3>{s.heading}</h3>
                {s.body ? <p className="dt-text">{s.body}</p> : <p className="dt-none">비어 있다.</p>}
              </section>
            ))}
          </div>
          {work.length > 0 && (
            <aside className="dt-aside" aria-labelledby={`${titleId}-w`}>
              <h3 id={`${titleId}-w`}>
                이 번호를 적은 작업 <span className="num">{work.length}</span>
              </h3>
              <p className="dc-note">작업의 제목, 번호, 다음 행동에 이 결정의 번호가 나온다. 누르면 작업 화면에서 연다.</p>
              <ul className="dc-links">
                {work.map((w) => (
                  <li key={w.id}>
                    <a className="dc-link" href={workHref(w)}>
                      <span className="dc-link-l1">
                        <StageGlyph stage={w.stage} blocked={isBlocked(w)} />
                        <span className="lrow-state">{stageLabel(w.stage)}</span>
                        {w.external_ref && <code>{w.external_ref}</code>}
                      </span>
                      <span className="dc-link-t">{w.title}</span>
                    </a>
                  </li>
                ))}
              </ul>
            </aside>
          )}
        </div>
      )}
    </article>
  );
}

/** A supersession link: what it is, then the other decision's number and question. */
function Relation({ label, target, onJump }: { label: string; target: DecisionRef; onJump: (id: string) => void }) {
  return (
    <li>
      <span className="dc-link-k">{label}</span>
      <button type="button" className="dc-link" data-jump={target.id} onClick={() => onJump(target.id)}>
        {target.external_ref && <code>{target.external_ref}</code>}
        <span className="dc-link-t">{target.question}</span>
      </button>
    </li>
  );
}
