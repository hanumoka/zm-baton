import { useId, useState } from 'react';
import { Icon } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import { isBlocked } from '../work/workView';
import type { Project, WorkItem } from '../workApi';
import { stageLabel } from '../workModel';
import { draftProblem, nextKey, supersedesLabel, type DecisionDraft, type DraftOption } from './decisionDraft';

export type FormMode = 'new' | 'edit' | 'supersede';

type Props = {
  mode: FormMode;
  start: DecisionDraft;
  projects: Project[];
  /** Work that can be linked; null while it is read. */
  work: WorkItem[] | null;
  /** Who accepts or rejects it, shown as a fact, not a field (stage 1: the owner). */
  approver: string;
  onSubmit: (draft: DecisionDraft) => Promise<void>;
  onCancel: () => void;
  /** The last refusal, with the server's reason. */
  error: string | null;
  /** In the narrow sheet the sheet's bar carries the heading. */
  inSheet?: boolean;
};

const HEADINGS: Record<FormMode, string> = { new: '새 결정', edit: '결정 고치기', supersede: '결정 대체하기' };
const SUBMIT: Record<FormMode, string> = { new: '결정 제안하기', edit: '고친 내용 저장', supersede: '대체 결정 제안하기' };

/**
 * A decision made here (FN-0604): the question, its context, the options and the one chosen, the
 * decision and why, the roles (DACI) and when to look again, with its projects and work. It is
 * proposed first; its approver accepts or rejects it in the detail. The Markdown fields are drawn
 * by Prose once saved.
 */
export function DecisionForm({ mode, start, projects, work, approver, onSubmit, onCancel, error, inSheet = false }: Props) {
  const [d, setD] = useState(start);
  const [busy, setBusy] = useState(false);
  /** Keys this form has handed out, so a removed letter is not given to a different option. */
  const [taken, setTaken] = useState(() => start.options.map((o) => o.key));
  const titleId = useId();
  const whyId = useId();
  const problem = draftProblem(d);
  const set = (k: keyof DecisionDraft) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });

  function setOption(i: number, patch: Partial<DraftOption>) {
    setD({ ...d, options: d.options.map((o, j) => (j === i ? { ...o, ...patch } : o)) });
  }
  function addOption() {
    const key = nextKey(taken);
    setTaken([...taken, key]);
    setD({ ...d, options: [...d.options, { key, title: '', body: '' }] });
  }
  function removeOption(i: number) {
    const gone = d.options[i];
    setD({ ...d, options: d.options.filter((_, j) => j !== i), chosen: d.chosen === gone.key ? '' : d.chosen });
  }
  const toggle = (list: string[], id: string) => (list.includes(id) ? list.filter((x) => x !== id) : [...list, id]);

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
        {HEADINGS[mode]}
      </h2>
      <p className="wn-lead">
        {mode === 'edit'
          ? '제안 상태인 동안만 고친다. 채택하거나 기각하면 고치지 않고, 바꾸려면 새 결정으로 대체한다.'
          : '여기서 만든 결정은 정본이 zm-baton이다. 제안으로 만들고 승인자가 채택하거나 기각한다.'}
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      {d.supersedes && (
        <div className="dform-sup">
          <span className="dc-link-k">채택하면 대체할 결정</span>
          <p>
            <span>{supersedesLabel(d.supersedes)}</span>
            {mode !== 'supersede' && (
              <button type="button" className="btn btn-quiet" onClick={() => setD({ ...d, supersedes: null })}>
                대체하지 않기
              </button>
            )}
          </p>
        </div>
      )}
      <label>
        질문
        <input className="field" value={d.question} onChange={set('question')} required autoFocus={mode !== 'edit'} placeholder="무엇을 정하나" />
      </label>
      <label>
        맥락
        <textarea className="field" value={d.context} onChange={set('context')} rows={3} placeholder="왜 지금 정해야 하나. Markdown으로 쓴다" />
      </label>
      <fieldset className="dform-set">
        <legend>선택지</legend>
        <p className="dform-none">
          {d.options.length === 0 ? '아직 없다. 견준 안이 있으면 더하고, 고른 안을 표시한다.' : '고른 안은 글자 앞의 동그라미로 표시한다.'}
        </p>
        <ol className="dform-opts">
          {d.options.map((o, i) => (
            <li key={o.key} className="dform-opt">
              <label className="check dform-pick">
                <input type="radio" name={`${titleId}-chosen`} checked={d.chosen === o.key} onChange={() => setD({ ...d, chosen: o.key })} />
                <span className="sr">이 안을 고른다:</span>
                <code>{o.key}</code>
              </label>
              <div className="dform-opt-fields">
                <input
                  className="field"
                  value={o.title}
                  onChange={(e) => setOption(i, { title: e.target.value })}
                  aria-label={`선택지 ${o.key} 이름`}
                  placeholder="선택지 이름"
                />
                <textarea
                  className="field"
                  value={o.body}
                  onChange={(e) => setOption(i, { body: e.target.value })}
                  rows={2}
                  aria-label={`선택지 ${o.key} 설명`}
                  placeholder="장점, 단점, 비용과 위험"
                />
              </div>
              <button type="button" className="btn btn-quiet" onClick={() => removeOption(i)} aria-label={`선택지 ${o.key} 빼기`}>
                빼기
              </button>
            </li>
          ))}
        </ol>
        <div className="wd-acts">
          <button type="button" className="btn" onClick={addOption}>
            <Icon name="plus" />
            선택지 더하기
          </button>
          {d.chosen !== '' && (
            <button type="button" className="btn btn-quiet" onClick={() => setD({ ...d, chosen: '' })}>
              고른 안 비우기
            </button>
          )}
        </div>
      </fieldset>
      <label>
        결정
        <textarea className="field" value={d.choice} onChange={set('choice')} rows={3} placeholder="고른 안과 적용 범위" />
      </label>
      <label>
        근거
        <textarea className="field" value={d.rationale} onChange={set('rationale')} rows={3} placeholder="판단 기준과 고른 이유" />
      </label>
      <label>
        재검토 조건
        <textarea className="field" value={d.review} onChange={set('review')} rows={2} placeholder="무엇이 일어나면 다시 보나, 판정일과 판정자" />
      </label>
      <fieldset className="dform-set">
        <legend>결정 역할</legend>
        <div className="wn-row">
          <label>
            주도(Driver)
            <input className="field" value={d.driver} onChange={set('driver')} />
          </label>
          <div className="dform-fixed">
            <span>승인(Approver)</span>
            <p>
              <code>{approver}</code>
              <small>1단계는 소유자가 승인한다</small>
            </p>
          </div>
          <label>
            기여(Contributors)
            <input className="field" value={d.contributors} onChange={set('contributors')} placeholder="쉼표로 나눈다" />
          </label>
          <label>
            알림(Informed)
            <input className="field" value={d.informed} onChange={set('informed')} placeholder="쉼표로 나눈다" />
          </label>
        </div>
      </fieldset>
      <fieldset className="dform-set">
        <legend>프로젝트</legend>
        {projects.length === 0 ? (
          <p className="dform-none">등록된 프로젝트가 없다.</p>
        ) : (
          <div className="dform-checks">
            {projects.map((p) => (
              <label key={p.id} className="check">
                <input type="checkbox" checked={d.projectIds.includes(p.id)} onChange={() => setD({ ...d, projectIds: toggle(d.projectIds, p.id) })} />
                {p.name}
                {p.external_ref && <code>{p.external_ref}</code>}
              </label>
            ))}
          </div>
        )}
      </fieldset>
      <WorkLinks ids={d.workIds} work={work} onChange={(workIds) => setD({ ...d, workIds })} />
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={problem !== null} aria-busy={busy || undefined} aria-describedby={problem ? whyId : undefined}>
          {SUBMIT[mode]}
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

/** The work a decision is about: what is linked, and a list of the rest to link one more. */
function WorkLinks({ ids, work, onChange }: { ids: string[]; work: WorkItem[] | null; onChange: (ids: string[]) => void }) {
  const [pick, setPick] = useState('');
  const byId = new Map((work ?? []).map((w) => [w.id, w]));
  const rest = (work ?? []).filter((w) => !ids.includes(w.id));
  return (
    <fieldset className="dform-set">
      <legend>관련 작업</legend>
      {ids.length === 0 ? (
        <p className="dform-none">이은 작업이 없다. 이으면 작업 상세에도 이 결정이 보인다.</p>
      ) : (
        <ul className="dc-links">
          {ids.map((id) => {
            const w = byId.get(id);
            return (
              <li key={id} className="dform-work">
                {w ? (
                  <span className="dc-link-l1">
                    <StageGlyph stage={w.stage} blocked={isBlocked(w)} />
                    <span className="lrow-state">{stageLabel(w.stage)}</span>
                    {w.external_ref && <code>{w.external_ref}</code>}
                    <span className="dc-link-t">{w.title}</span>
                  </span>
                ) : (
                  <code>{id}</code>
                )}
                <button type="button" className="btn btn-quiet" onClick={() => onChange(ids.filter((x) => x !== id))}>
                  빼기
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <div className="wd-form">
        <label>
          이을 작업
          <span className="select">
            <select value={pick} onChange={(e) => setPick(e.target.value)} disabled={work === null} aria-busy={work === null || undefined}>
              <option value="">{work === null ? '작업을 읽는 중' : rest.length === 0 ? '더 이을 작업이 없다' : '작업 고르기'}</option>
              {rest.map((w) => (
                <option key={w.id} value={w.id}>
                  {`${w.external_ref ?? ''} ${w.title}`.trim()}
                </option>
              ))}
            </select>
            <Icon name="chevron-down" className="chev" />
          </span>
        </label>
        <button
          type="button"
          className="btn"
          disabled={pick === ''}
          onClick={() => {
            onChange([...ids, pick]);
            setPick('');
          }}
        >
          작업 잇기
        </button>
      </div>
    </fieldset>
  );
}
