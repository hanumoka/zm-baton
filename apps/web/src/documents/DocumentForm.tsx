import { useId, useState } from 'react';
import { DecisionMark } from '../icons/DecisionMark';
import { Icon } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import { LinkPicker, type Pickable } from '../ui/LinkPicker';
import { MarkdownField } from '../ui/MarkdownField';
import { isBlocked } from '../work/workView';
import type { Decision, Project, WorkItem } from '../workApi';
import { decisionStatusLabel, stageLabel } from '../workModel';
import { docDraftProblem, SOURCE_LABELS, type DocDraft } from './documentView';

/** A first body's hint, two lines apart. */
const BODY_HINT = ['# 제목', '', 'Markdown으로 쓴다'].join(String.fromCharCode(10));

type Props = {
  mode: 'new' | 'edit';
  start: DocDraft;
  projects: Project[];
  /** Work and decisions that can be linked; null while they are read. */
  work: WorkItem[] | null;
  decisions: Decision[] | null;
  onSubmit: (draft: DocDraft) => Promise<void>;
  onCancel: () => void;
  error: string | null;
  inSheet?: boolean;
};

export const workPickable = (w: WorkItem): Pickable => ({
  id: w.id,
  label: `${w.external_ref ?? ''} ${w.title}`.trim(),
  node: (
    <span className="dc-link-l1">
      <StageGlyph stage={w.stage} blocked={isBlocked(w)} />
      <span className="lrow-state">{stageLabel(w.stage)}</span>
      {w.external_ref && <code>{w.external_ref}</code>}
      <span className="dc-link-t">{w.title}</span>
    </span>
  ),
});

export const decisionPickable = (d: Decision): Pickable => ({
  id: d.id,
  label: `${d.ref ?? ''} ${d.question}`.trim(),
  node: (
    <span className="dc-link-l1">
      <DecisionMark status={d.status} />
      <span className="lrow-state">{decisionStatusLabel(d.status)}</span>
      {d.ref && <code>{d.ref}</code>}
      <span className="dc-link-t">{d.question}</span>
    </span>
  ),
});

/**
 * Registering a document (FN-0601) or changing its own fields. Registering picks the one source
 * of truth: in the product, with an optional first body written as Markdown, or in Git, with the
 * repository, the path from its root and, when known, the commit of its first version. A change
 * never moves the source of truth; it changes the title, project, Git place and links.
 */
export function DocumentForm({ mode, start, projects, work, decisions, onSubmit, onCancel, error, inSheet = false }: Props) {
  const [d, setD] = useState(start);
  const [busy, setBusy] = useState(false);
  const titleId = useId();
  const whyId = useId();
  const problem = docDraftProblem(d);
  const set = (k: keyof DocDraft) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });
  return (
    <form
      className="wn dform"
      aria-labelledby={titleId}
      aria-busy={busy || undefined}
      onSubmit={(e) => {
        e.preventDefault();
        if (problem !== null || busy) return;
        setBusy(true);
        void onSubmit(d).finally(() => setBusy(false));
      }}
    >
      <h2 id={titleId} className={inSheet ? 'sr' : 'dt-title'}>
        {mode === 'new' ? '새 문서' : '문서 정보 고치기'}
      </h2>
      <p className="wn-lead">
        {mode === 'new'
          ? '문서마다 정본 위치를 하나로 정한다. 제품 안이면 여기서 판을 쌓고, Git이면 본문은 저장소에 두고 여기서는 경로와 커밋만 기록한다.'
          : `정본 위치(${SOURCE_LABELS[d.source]})는 바꾸지 않는다. ${d.source === 'git' ? '본문은 Git에서 고치고 그 커밋을 「새 커밋 기록」으로 남긴다.' : '본문은 「새 판 쓰기」로 바꾼다.'}`}
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      <label>
        제목
        <input className="field" value={d.title} onChange={set('title')} required autoFocus={mode === 'new'} />
      </label>
      {mode === 'new' && (
        <fieldset className="dform-set">
          <legend>정본 위치</legend>
          <div className="dform-checks">
            {(['product', 'git'] as const).map((s) => (
              <label key={s} className="check">
                <input type="radio" name={`${titleId}-source`} checked={d.source === s} onChange={() => setD({ ...d, source: s })} />
                <Icon name={s === 'git' ? 'git-file' : 'doc'} />
                {s === 'product' ? '제품 안: 여기서 본문과 판을 쓴다' : 'Git: 저장소 파일이 정본이다'}
              </label>
            ))}
          </div>
        </fieldset>
      )}
      <label>
        프로젝트
        <span className="select">
          <select value={d.projectId} onChange={set('projectId')}>
            <option value="">없음</option>
            {projects.map((p) => (
              <option key={p.id} value={p.id}>
                {`${p.external_ref ?? ''} ${p.name}`.trim()}
              </option>
            ))}
          </select>
          <Icon name="chevron-down" className="chev" />
        </span>
      </label>
      {d.source === 'git' ? (
        <fieldset className="dform-set">
          <legend>Git 위치</legend>
          <div className="wn-row">
            <label>
              저장소
              <input className="field" value={d.repo} onChange={set('repo')} placeholder="예: github.com/hanumoka/zm-docs" />
            </label>
            <label>
              브랜치(적지 않아도 된다)
              <input className="field" value={d.branch} onChange={set('branch')} placeholder="예: main" />
            </label>
          </div>
          <label>
            저장소 뿌리에서의 경로
            <input className="field" value={d.path} onChange={set('path')} placeholder="예: docs/governance/decisions/README.md" />
          </label>
          {mode === 'new' && (
            <label>
              첫 판의 커밋(적지 않아도 된다)
              <input className="field mono" value={d.commit} onChange={set('commit')} placeholder="예: 29d648a" />
            </label>
          )}
        </fieldset>
      ) : (
        mode === 'new' && (
          <>
            <MarkdownField label="첫 판 본문(적지 않으면 판 없이 등록한다)" value={d.body} onChange={(body) => setD({ ...d, body })} rows={10} placeholder={BODY_HINT} />
            <label>
              판 메모
              <input className="field" value={d.note} onChange={set('note')} placeholder="무엇을 썼나" />
            </label>
          </>
        )
      )}
      <LinkPicker
        legend="영향 받는 작업"
        empty="이은 작업이 없다. 이으면 이 문서의 판이 바뀔 때 다시 볼 작업으로 보인다."
        pickLabel="이을 작업"
        addLabel="작업 잇기"
        items={work?.map(workPickable) ?? null}
        ids={d.workIds}
        onChange={(workIds) => setD({ ...d, workIds })}
      />
      <LinkPicker
        legend="관련 결정"
        empty="이은 결정이 없다."
        pickLabel="이을 결정"
        addLabel="결정 잇기"
        items={decisions?.map(decisionPickable) ?? null}
        ids={d.decisionIds}
        onChange={(decisionIds) => setD({ ...d, decisionIds })}
      />
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={problem !== null} aria-busy={busy || undefined} aria-describedby={problem ? whyId : undefined}>
          {mode === 'new' ? '문서 등록하기' : '고친 내용 저장'}
        </button>
        <button type="button" className="btn" onClick={onCancel}>
          그만두기
        </button>
      </div>
      {problem && (
        <span id={whyId} className="ctl-why">
          {problem}
        </span>
      )}
    </form>
  );
}
