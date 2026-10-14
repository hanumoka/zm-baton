import { useId, useState, type Ref } from 'react';
import type { Doc, DocDetail, DocVersion } from '../documentsApi';
import { DecisionMark } from '../icons/DecisionMark';
import { Icon } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import { RowSkeleton } from '../ui/Group';
import { OpenContradictions } from '../ui/OpenContradictions';
import { Prose } from '../ui/Prose';
import { visibilityLabel } from '../ui/visibility';
import { isBlocked } from '../work/workView';
import { at, decisionStatusLabel, stageLabel } from '../workModel';
import { workHref } from '../decisions/decisionView';
import { commitProblem, docHistoryLine, linkedDecisionHref, SOURCE_LABELS, sourceIcon, versionLabel } from './documentView';

/** What a person can do from the detail; the screen runs them against the server. */
export type DocActions = {
  /** Open the writer for a product document's next version. */
  write: () => void;
  /** Record the commit a Git document's next version points at. */
  record: (commit: string, note?: string) => Promise<void>;
  approve: (number: number, note?: string) => Promise<void>;
  edit: () => void;
  /** Show another version's body (a step in the address). */
  show: (number: number) => void;
};

type Props = {
  /** The document as the list has it, drawn at once while its detail loads. */
  doc: Doc;
  detail: DocDetail | null;
  actions?: DocActions;
  error?: string | null;
  titleRef?: Ref<HTMLHeadingElement>;
  inSheet?: boolean;
};

/**
 * One document (FN-0601, FN-0602; 화면 초안 「문서와 기억」: the source of truth, the current version
 * and its approval at the top). Then what may happen next, the body of the version shown, every
 * version with its approval, and beside it the work it affects, the decisions it rests on and its
 * history. A Git document says 「읽기만 한다」 with its repository, path and commit; its body stays there.
 */
export function DocumentDetailView({ doc, detail, actions, error = null, titleRef, inSheet = false }: Props) {
  const titleId = useId();
  const d = detail !== null && detail.document.id === doc.id ? detail : null;
  const it = d ? d.document : doc;
  const git = it.source_of_truth === 'git';
  return (
    <article className="dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>문서</span>
          <span aria-hidden="true">/</span>
          <span>{SOURCE_LABELS[it.source_of_truth]}</span>
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {it.title}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>정본</dt>
          <dd className="doc-source">
            <Icon name={sourceIcon(it.source_of_truth)} />
            {SOURCE_LABELS[it.source_of_truth]}
          </dd>
        </div>
        <div>
          <dt>현재 판</dt>
          <dd>{versionLabel(it)}</dd>
        </div>
        <div>
          <dt>승인된 판</dt>
          <dd>{it.approved_number === null ? '없음' : `판 ${it.approved_number}`}</dd>
        </div>
        <div>
          <dt>프로젝트</dt>
          <dd>
            {it.project ? it.project.name : '없음'}
            {it.project?.external_ref && <code>{it.project.external_ref}</code>}
          </dd>
        </div>
        <div>
          <dt>소유자</dt>
          <dd>
            <code>{it.owner_user_id}</code>
          </dd>
        </div>
        <div>
          <dt>공유 범위</dt>
          <dd>{visibilityLabel(it.visibility)}</dd>
        </div>
      </dl>
      {git && (
        <p className="dt-ro">
          <Icon name="lock" />
          <strong>읽기만 한다</strong>
          <span>
            정본은 Git 파일 <code>{it.git_ref?.path ?? '?'}</code>
            {it.git_ref?.repo && (
              <>
                {' '}
                저장소 <code>{it.git_ref.repo}</code>
              </>
            )}
            {it.git_ref?.branch && (
              <>
                {' '}
                브랜치 <code>{it.git_ref.branch}</code>
              </>
            )}
          </span>
        </p>
      )}
      {d === null ? <RowSkeleton rows={3} /> : <Body d={d} actions={actions} error={error} />}
    </article>
  );
}

function Body({ d, actions, error }: { d: DocDetail; actions?: DocActions; error: string | null }) {
  const doc = d.document;
  const git = doc.source_of_truth === 'git';
  const shown = d.shown;
  const latest = d.versions[0] ?? null;
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
        <OpenContradictions about={doc.id} />
        <section className="dt-sec">
          <h3>
            {shown ? `판 ${shown.number} 본문` : '본문'}
            {shown && <StateTag state={shown.state} />}
            {shown && latest && shown.number !== latest.number && <span className="doc-old">최신은 판 {latest.number}</span>}
          </h3>
          {shown === null ? (
            <p className="dt-none">{git ? '기록한 커밋이 없다. Git에서 커밋한 뒤 「새 커밋 기록」으로 첫 판을 남긴다.' : '아직 판이 없다. 「새 판 쓰기」로 첫 판을 쓴다.'}</p>
          ) : git ? (
            <p className="dt-text doc-git">
              본문은 Git 파일 <code>{doc.git_ref?.path}</code>의 커밋 <code>{shown.git_commit_sha}</code>에 있다. 고치려면 Git에서 고치고 커밋한 뒤 「새 커밋 기록」으로 새 판을 남긴다.
            </p>
          ) : shown.body_markdown ? (
            <Prose text={shown.body_markdown} />
          ) : (
            <p className="dt-none">비어 있다.</p>
          )}
        </section>
        <section className="dt-sec">
          <h3>
            판 <span className="num">{d.versions.length}</span>
          </h3>
          {d.versions.length === 0 ? (
            <p className="dt-none">아직 없다.</p>
          ) : (
            <ol className="doc-vers">
              {d.versions.map((v) => (
                <VersionRow key={v.id} v={v} shown={shown?.number === v.number} git={git} actions={actions} />
              ))}
            </ol>
          )}
        </section>
      </div>
      <aside className="dt-aside" aria-label="영향 받는 작업과 기록">
        <h3>
          영향 받는 작업 <span className="num">{d.work_items.length}</span>
        </h3>
        {d.work_items.length === 0 ? (
          <p className="dt-none dc-gap">이은 작업이 없다.</p>
        ) : (
          <>
            <p className="dc-note">판이 바뀌면 다시 볼 작업이다. 누르면 작업 화면에서 연다.</p>
            <ul className="dc-links">
              {d.work_items.map((w) => (
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
          </>
        )}
        <h3 className="dc-aside-h">
          관련 결정 <span className="num">{d.decisions.length}</span>
        </h3>
        {d.decisions.length === 0 ? (
          <p className="dt-none dc-gap">이은 결정이 없다.</p>
        ) : (
          <ul className="dc-links">
            {d.decisions.map((x) => (
              <li key={x.id}>
                <a className="dc-link" href={linkedDecisionHref(x)}>
                  <span className="dc-link-l1">
                    <DecisionMark status={x.status} />
                    <span className="lrow-state">{decisionStatusLabel(x.status)}</span>
                    {x.ref && <code>{x.ref}</code>}
                  </span>
                  <span className="dc-link-t">{x.question}</span>
                </a>
              </li>
            ))}
          </ul>
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
                <span>{docHistoryLine(h)}</span>
              </li>
            ))}
          </ol>
        )}
      </aside>
    </div>
  );
}

/**
 * The main action is the next version: writing it for a product document, recording its commit
 * for a Git one. Approving the version shown sits beside it, locked with the server's reason when
 * it may not be approved; changing the document's own fields comes last.
 */
function Actions({ d, actions }: { d: DocDetail; actions: DocActions }) {
  const whyId = useId();
  const [recording, setRecording] = useState(false);
  const [commit, setCommit] = useState('');
  const [note, setNote] = useState('');
  const git = d.document.source_of_truth === 'git';
  const shown = d.shown;
  const problem = commit.trim() === '' ? null : commitProblem(commit);
  return (
    <section className="dt-sec">
      <h3>할 수 있는 일</h3>
      <div className="wd-moves dc-acts">
        {git ? (
          <button type="button" className="btn btn-primary" onClick={() => setRecording(true)}>
            새 커밋 기록
          </button>
        ) : (
          <button type="button" className="btn btn-primary" onClick={actions.write}>
            새 판 쓰기
          </button>
        )}
        {shown && (
          <button
            type="button"
            className="btn"
            disabled={!shown.approve.allowed}
            aria-describedby={shown.approve.allowed ? undefined : whyId}
            onClick={() => void actions.approve(shown.number)}
          >
            판 {shown.number} 승인하기
          </button>
        )}
        <button type="button" className="btn" onClick={actions.edit}>
          문서 정보 고치기
        </button>
      </div>
      <p className="dc-who">
        승인하는 사람 <code>{d.approver}</code>
      </p>
      {shown && !shown.approve.allowed && (
        <p className="wd-why" id={whyId}>
          <Icon name="lock" />
          <span>{shown.approve.reason}</span>
        </p>
      )}
      {recording && (
        <form
          className="wd-form wd-ask"
          onSubmit={(e) => {
            e.preventDefault();
            if (commit.trim() === '' || problem) return;
            void actions.record(commit.trim(), note.trim() || undefined).then(() => {
              setRecording(false);
              setCommit('');
              setNote('');
            });
          }}
        >
          <label>
            Git에서 커밋한 판의 커밋
            <input className="field mono" value={commit} onChange={(e) => setCommit(e.target.value)} placeholder="예: 29d648a" autoFocus aria-invalid={problem ? true : undefined} />
            {problem && <span className="ctl-err">{problem}</span>}
          </label>
          <label>
            판 메모
            <input className="field" value={note} onChange={(e) => setNote(e.target.value)} placeholder="무엇을 바꿨나" />
          </label>
          <button type="submit" className="btn btn-primary" disabled={commit.trim() === '' || problem !== null}>
            커밋 기록
          </button>
          <button type="button" className="btn" onClick={() => setRecording(false)}>
            그만두기
          </button>
        </form>
      )}
    </section>
  );
}

/** One version: its number and state, who wrote it when and why, its approval, and buttons to see or approve it. */
function VersionRow({ v, shown, git, actions }: { v: DocVersion; shown: boolean; git: boolean; actions?: DocActions }) {
  return (
    <li className={shown ? 'is-shown' : undefined} aria-current={shown ? 'true' : undefined}>
      <p className="doc-ver-t">
        <strong>판 {v.number}</strong>
        <StateTag state={v.state} />
        {git && v.git_commit_sha && <code>{v.git_commit_sha.slice(0, 12)}</code>}
        {shown && <small>보는 판</small>}
      </p>
      <p className="doc-ver-sub">
        <time className="num" dateTime={v.created_at}>
          {at(v.created_at)}
        </time>{' '}
        <code>{v.created_by}</code>
        {v.note && <span> — {v.note}</span>}
      </p>
      {v.approved_at && (
        <p className="doc-ver-sub">
          승인 <code>{v.approved_by}</code>{' '}
          <time className="num" dateTime={v.approved_at}>
            {at(v.approved_at)}
          </time>
        </p>
      )}
      {actions && (!shown || v.approve.allowed) && (
        <p className="doc-ver-acts">
          {!shown && (
            <button type="button" className="btn btn-quiet" onClick={() => actions.show(v.number)}>
              판 {v.number} 보기
            </button>
          )}
          {v.approve.allowed && (
            <button type="button" className="btn btn-quiet" onClick={() => void actions.approve(v.number)}>
              판 {v.number} 승인하기
            </button>
          )}
        </p>
      )}
    </li>
  );
}

/** 「초안」 or 「승인됨」 as a small label beside a version, in words and not colour alone. */
function StateTag({ state }: { state: DocVersion['state'] }) {
  return <span className={`doc-state is-${state}`}>{state === 'approved' ? '승인됨' : '초안'}</span>;
}
