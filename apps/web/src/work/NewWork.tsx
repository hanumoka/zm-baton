import { useId, useState } from 'react';
import { Icon } from '../icons/Icon';
import type { NewWorkItem, Project } from '../workApi';

type Props = {
  projects: Project[];
  defaultProject: string;
  onCreate: (body: NewWorkItem) => void;
  onCancel: () => void;
  /** In the narrow sheet the sheet's bar carries the heading. */
  inSheet?: boolean;
};

/** A work item made here; its source of truth is zm-baton, so it is edited and moved here. */
export function NewWork({ projects, defaultProject, onCreate, onCancel, inSheet = false }: Props) {
  const [title, setTitle] = useState('');
  const [nextAction, setNextAction] = useState('');
  const [project, setProject] = useState(defaultProject);
  const [stage, setStage] = useState<'draft' | 'ready'>('ready');
  const titleId = useId();
  const whyId = useId();
  const empty = title.trim() === '';
  return (
    <form
      className="wn"
      aria-labelledby={titleId}
      onSubmit={(e) => {
        e.preventDefault();
        if (empty) return;
        onCreate({
          title: title.trim(),
          next_action: nextAction.trim() || undefined,
          project_id: project || undefined,
          initial_stage: stage,
        });
      }}
    >
      <h2 id={titleId} className={inSheet ? 'sr' : 'dt-title'}>
        새 작업
      </h2>
      <p className="wn-lead">여기서 만든 작업은 정본이 zm-baton이라 여기서 고치고 단계를 넘긴다.</p>
      <label>
        제목
        <input className="field" value={title} onChange={(e) => setTitle(e.target.value)} required autoFocus />
      </label>
      <label>
        다음 행동
        <textarea className="field" value={nextAction} onChange={(e) => setNextAction(e.target.value)} rows={2} />
      </label>
      <div className="wn-row">
        <label>
          프로젝트
          <span className="select">
            <select value={project} onChange={(e) => setProject(e.target.value)}>
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
        <label>
          처음 단계
          <span className="select">
            <select value={stage} onChange={(e) => setStage(e.target.value as 'draft' | 'ready')}>
              <option value="ready">수행 준비</option>
              <option value="draft">초안</option>
            </select>
            <Icon name="chevron-down" className="chev" />
          </span>
        </label>
      </div>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={empty} aria-describedby={empty ? whyId : undefined}>
          작업 만들기
        </button>
        <button type="button" className="btn" onClick={onCancel}>
          그만두기
        </button>
      </div>
      {empty && (
        <span id={whyId} className="ctl-why">
          제목을 쓰면 만들 수 있다.
        </span>
      )}
    </form>
  );
}
