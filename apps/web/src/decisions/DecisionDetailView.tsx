import { useId, useState, type ReactNode, type Ref } from 'react';
import { DecisionMark } from '../icons/DecisionMark';
import { Icon } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import { RowSkeleton } from '../ui/Group';
import { OpenContradictions } from '../ui/OpenContradictions';
import { Prose } from '../ui/Prose';
import { ReadOnly } from '../ui/ReadOnly';
import { isBlocked } from '../work/workView';
import type { Decision, DecisionDetail, DecisionRef, LinkedWork } from '../workApi';
import { at, decisionStatusLabel, stageLabel } from '../workModel';
import { decisionHistoryLine } from './decisionDraft';
import { actionOf, otherSections, refOf, workHref } from './decisionView';

/** What a person can do from the detail; the screen runs them against the server. */
export type DecisionActions = {
  edit: () => void;
  accept: () => Promise<void>;
  reject: (note?: string) => Promise<void>;
  supersede: () => void;
};

type Props = {
  /** The decision as the list has it, drawn at once while its detail loads. */
  decision: Decision;
  /** The server's detail; ignored while it belongs to another decision. */
  detail: DecisionDetail | null;
  /** Work that writes this decision's number; null until read. */
  related: LinkedWork[] | null;
  /** Go to another decision (a supersession link, or a link to its file in the text). */
  onJump: (id: string) => void;
  /** A decision number in the text (DEC-0048) to its id on this screen; null when not read. */
  resolveDecision?: (ref: string) => string | null;
  /** Edit, accept, reject and supersede a decision made here; absent where the screen does not offer them. */
  actions?: DecisionActions;
  /** The last refusal of an action on this decision, with the server's reason. */
  error?: string | null;
  /** The question takes the focus when Enter opens the decision from the list. */
  titleRef?: Ref<HTMLHeadingElement>;
  /** In the narrow sheet the sheet's bar already says where this is. */
  inSheet?: boolean;
};

/**
 * One decision record (FN-0604). The question, its status, date and projects on one line. A copy
 * of a zm-docs decision says 「읽기만 한다」 with its Git file (FN-0605) and reads in the file's own
 * order after what was chosen; beside it, the work that writes its number. A decision made here
 * shows what its approver may do with it, then what was chosen, what it replaces, the options,
 * context, rationale, roles and review conditions; beside it, its linked work, approvals and history.
 */
export function DecisionDetailView({ decision, detail, related, onJump, resolveDecision, actions, error = null, titleRef, inSheet = false }: Props) {
  const titleId = useId();
  const d = detail !== null && detail.decision.id === decision.id ? detail : null;
  const it = d ? d.decision : decision;
  const ref = refOf(it);
  const product = it.source_of_truth === 'product';
  return (
    <article className="dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>결정</span>
          <span aria-hidden="true">/</span>
          {ref ? <code>{ref}</code> : <span>번호 없는 결정</span>}
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
          <dd className="num">{it.decided_on ?? (it.status === 'proposed' ? '아직' : '적혀 있지 않다')}</dd>
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
        {product && (
          <div>
            <dt>정본</dt>
            <dd>여기서 만든 결정</dd>
          </div>
        )}
      </dl>
      {d && !product && <ReadOnly gitRef={d.git_ref} reason={d.read_only_reason ?? '1단계는 결정을 웹에서 고치지 않는다.'} />}
      {d === null ? <RowSkeleton rows={3} /> : product ? <Made d={d} actions={actions} error={error} onJump={onJump} /> : <Copied d={d} related={related} onJump={onJump} resolveDecision={resolveDecision} titleId={titleId} />}
    </article>
  );
}

/** A copy of a zm-docs decision: what was chosen, what replaced what, the rest of the file, the work that writes its number. */
function Copied({
  d,
  related,
  onJump,
  resolveDecision,
  titleId,
}: {
  d: DecisionDetail;
  related: LinkedWork[] | null;
  onJump: (id: string) => void;
  resolveDecision?: (ref: string) => string | null;
  titleId: string;
}) {
  const it = d.decision;
  const work = related ?? [];
  return (
    <div className={work.length > 0 ? 'dt-grid' : 'dt-grid is-solo'}>
      <div className="dt-main">
        <OpenContradictions about={it.id} />
        <section className="dt-sec">
          <h3>고른 안</h3>
          {d.choice ? <Prose text={d.choice} resolveDecision={resolveDecision} onJump={onJump} /> : <p className="dt-none">「결정」 절을 찾지 못했다. 정본 파일에서 본다.</p>}
        </section>
        <Supersession it={it} onJump={onJump} />
        {otherSections(d).map((s, i) => (
          <section key={`${i}-${s.heading}`} className="dt-sec">
            <h3>{s.heading}</h3>
            {s.body ? <Prose text={s.body} resolveDecision={resolveDecision} onJump={onJump} /> : <p className="dt-none">비어 있다.</p>}
          </section>
        ))}
      </div>
      {work.length > 0 && (
        <aside className="dt-aside" aria-labelledby={`${titleId}-w`}>
          <h3 id={`${titleId}-w`}>
            이 번호를 적은 작업 <span className="num">{work.length}</span>
          </h3>
          <p className="dc-note">작업의 제목, 번호, 다음 행동에 이 결정의 번호가 나온다. 누르면 작업 화면에서 연다.</p>
          <WorkLinks work={work} />
        </aside>
      )}
    </div>
  );
}

/** A decision made here: its actions first, then its fields, and beside them links, approvals and history. */
function Made({ d, actions, error, onJump }: { d: DecisionDetail; actions?: DecisionActions; error: string | null; onJump: (id: string) => void }) {
  const it = d.decision;
  const chosen = d.options.find((o) => o.key === d.chosen_option) ?? null;
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
        <OpenContradictions about={it.id} />
        <section className="dt-sec">
          <h3>고른 안</h3>
          {chosen === null && !d.choice ? (
            <p className="dt-none">{it.status === 'proposed' ? '아직 고르지 않았다. 내용 고치기에서 고른 안이나 결정을 적는다.' : '적혀 있지 않다.'}</p>
          ) : (
            <>
              {chosen && (
                <p className="dc-chosen">
                  <code>{chosen.key}</code>
                  <strong>{chosen.title}</strong>
                </p>
              )}
              {d.choice && <Prose text={d.choice} />}
            </>
          )}
        </section>
        <Supersession it={it} onJump={onJump} />
        {d.options.length > 0 && (
          <section className="dt-sec">
            <h3>선택지</h3>
            <ol className="dc-opts">
              {d.options.map((o) => (
                <li key={o.key} className={o.key === d.chosen_option ? 'is-chosen' : undefined}>
                  <p className="dc-opt-t">
                    <code>{o.key}</code>
                    <span>{o.title}</span>
                    {o.key === d.chosen_option && <small>고른 안</small>}
                  </p>
                  {o.body && <Prose text={o.body} />}
                </li>
              ))}
            </ol>
          </section>
        )}
        <Field title="맥락" text={d.context} />
        <Field title="근거" text={d.rationale} />
        <section className="dt-sec">
          <h3>결정 역할</h3>
          <dl className="dc-roles">
            <div>
              <dt>주도</dt>
              <dd>{d.roles.driver ? <code>{d.roles.driver}</code> : '없음'}</dd>
            </div>
            <div>
              <dt>승인</dt>
              <dd>
                <code>{d.roles.approver ?? d.approver}</code>
              </dd>
            </div>
            <div>
              <dt>기여</dt>
              <dd>{names(d.roles.contributors)}</dd>
            </div>
            <div>
              <dt>알림</dt>
              <dd>{names(d.roles.informed)}</dd>
            </div>
          </dl>
        </section>
        <Field title="재검토 조건" text={d.review_conditions} />
      </div>
      <aside className="dt-aside" aria-label="관련 작업과 기록">
        <h3>
          관련 작업 <span className="num">{d.work_items.length}</span>
        </h3>
        {d.work_items.length === 0 ? <p className="dt-none dc-gap">이은 작업이 없다.</p> : <WorkLinks work={d.work_items} />}
        <h3 className="dc-aside-h">승인 기록</h3>
        {d.approvals.length === 0 ? (
          <p className="dt-none dc-gap">아직 없다. 승인자가 채택하거나 기각하면 남는다.</p>
        ) : (
          <ol className="wd-hist dc-gap">
            {d.approvals.map((a) => (
              <li key={a.id}>
                <time className="num" dateTime={a.decided_at}>
                  {at(a.decided_at)}
                </time>
                <span>
                  {a.decision === 'approved' ? '채택' : a.decision === 'rejected' ? '기각' : '철회'}, <code>{a.approver_id}</code>
                  {a.note && ` — ${a.note}`}
                </span>
              </li>
            ))}
          </ol>
        )}
        <h3 className="dc-aside-h">이력</h3>
        {d.history.length === 0 ? (
          <p className="dt-none">아직 남은 이력이 없다.</p>
        ) : (
          <ol className="wd-hist">
            {d.history.map((h, i) => (
              <li key={`${h.at}-${i}`}>
                <time className="num" dateTime={h.at}>
                  {at(h.at)}
                </time>
                <span>{decisionHistoryLine(h)}</span>
              </li>
            ))}
          </ol>
        )}
      </aside>
    </div>
  );
}

/**
 * What may happen next, as the server judged it. A proposal: accept (the main action), reject
 * (with an optional reason) and edit; an accepted decision: replace it. A refused action stays
 * visible with the server's reason next to it (DESIGN.md: a button that cannot be used says why).
 */
function Actions({ d, actions }: { d: DecisionDetail; actions: DecisionActions }) {
  const whyId = useId();
  const [asking, setAsking] = useState(false);
  const [note, setNote] = useState('');
  const accept = actionOf(d, 'accept');
  const reject = actionOf(d, 'reject');
  const edit = actionOf(d, 'edit');
  const supersede = actionOf(d, 'supersede');
  const proposed = d.decision.status === 'proposed';
  const shown = proposed ? [accept, reject, edit] : [supersede];
  const reasons = [...new Set(shown.filter((a) => !a.allowed).map((a) => a.reason ?? '이유를 받지 못했다'))];
  if (!proposed && d.decision.status !== 'accepted') return null;
  const button = (a: typeof accept, label: string, run: () => void, primary = false): ReactNode => (
    <button type="button" className={primary ? 'btn btn-primary' : 'btn'} disabled={!a.allowed} aria-describedby={a.allowed ? undefined : whyId} onClick={run}>
      {label}
    </button>
  );
  return (
    <section className="dt-sec">
      <h3>할 수 있는 일</h3>
      <div className="wd-moves dc-acts">
        {proposed ? (
          <>
            {button(accept, '채택하기', () => void actions.accept(), true)}
            {button(reject, '기각하기', () => {
              setAsking(true);
              setNote('');
            })}
            {button(edit, '내용 고치기', actions.edit)}
          </>
        ) : (
          button(supersede, '새 결정으로 대체하기', actions.supersede)
        )}
      </div>
      {proposed && (
        <p className="dc-who">
          승인하는 사람 <code>{d.approver}</code>
          {d.decision.supersedes && <span>, 채택하면 {refOf(d.decision.supersedes)}이(가) 대체된다</span>}
        </p>
      )}
      {reasons.length > 0 && (
        <p className="wd-why" id={whyId}>
          <Icon name="lock" />
          <span>{reasons.join(' ')}</span>
        </p>
      )}
      {asking && (
        <form
          className="wd-form wd-ask"
          onSubmit={(e) => {
            e.preventDefault();
            void actions.reject(note.trim() || undefined).then(() => setAsking(false));
          }}
        >
          <label>
            기각하는 이유(적지 않아도 된다)
            <input className="field" value={note} onChange={(e) => setNote(e.target.value)} autoFocus />
          </label>
          <button type="submit" className="btn btn-primary">
            기각하기
          </button>
          <button type="button" className="btn" onClick={() => setAsking(false)}>
            그만두기
          </button>
        </form>
      )}
    </section>
  );
}

/** Both directions of a supersession as buttons that name the other decision. */
function Supersession({ it, onJump }: { it: Decision; onJump: (id: string) => void }) {
  if (!it.superseded_by && !it.supersedes) return null;
  return (
    <section className="dt-sec">
      <h3>대체 관계</h3>
      <ul className="dc-links">
        {it.superseded_by && <Relation label="이 결정을 대체한 결정" target={it.superseded_by} onJump={onJump} />}
        {it.supersedes && (
          <Relation label={it.status === 'proposed' ? '채택하면 대체할 결정' : '이 결정이 대체한 결정'} target={it.supersedes} onJump={onJump} />
        )}
      </ul>
    </section>
  );
}

/** A Markdown field of a decision made here; an empty one says so. */
function Field({ title, text }: { title: string; text: string | null }) {
  return (
    <section className="dt-sec">
      <h3>{title}</h3>
      {text ? <Prose text={text} /> : <p className="dt-none">적혀 있지 않다.</p>}
    </section>
  );
}

const names = (list: string[]): ReactNode => (list.length === 0 ? '없음' : list.join(', '));

/** Work linked to a decision or writing its number, each opening on the work screen. */
function WorkLinks({ work }: { work: LinkedWork[] }) {
  return (
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
  );
}

/** A supersession link: what it is, then the other decision's number and question. */
function Relation({ label, target, onJump }: { label: string; target: DecisionRef; onJump: (id: string) => void }) {
  const ref = refOf(target);
  return (
    <li>
      <span className="dc-link-k">{label}</span>
      <button type="button" className="dc-link" data-jump={target.id} onClick={() => onJump(target.id)}>
        {ref && <code>{ref}</code>}
        <span className="dc-link-t">{target.question}</span>
      </button>
    </li>
  );
}
