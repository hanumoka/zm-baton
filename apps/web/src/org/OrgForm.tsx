import { useId, useState } from 'react';
import { Icon } from '../icons/Icon';
import { draftProblem, ENV_LABELS, KIND_LABELS, ROLE_LABELS, ROLE_NOTES, ROLE_ORDER, type NewDraft } from './orgView';

type Props = {
  start: NewDraft;
  envKinds: string[];
  onSubmit: (draft: NewDraft) => Promise<void>;
  onCancel: () => void;
  error: string | null;
  inSheet?: boolean;
};

const KINDS: NewDraft['kind'][] = ['person', 'team', 'product', 'project', 'repository', 'environment'];

const LEADS: Record<NewDraft['kind'], string> = {
  person: '사람을 더한 뒤 조직 소유자가 상세 화면의 로그인 계정에서 첫 비밀번호 설정 코드를 발급한다. 개인 로그인이 켜진 서버에서 사용할 수 있다.',
  team: '팀은 프로젝트를 맡는다. 팀 공유 범위의 기록은 그 프로젝트를 맡은 팀의 사람이 본다.',
  product: '제품은 프로젝트 여럿으로 이뤄진다.',
  project: '여기서 만든 프로젝트다. zm-docs에서 가져온 프로젝트는 가져오기가 만든다.',
  repository: '조직의 저장소다. PC가 zm-baton repo add로 더한 저장소는 저절로 여기에도 생긴다. 같은 저장소 키로 잇는다.',
  environment: '프로젝트가 돌아가는 곳이다. 개발·검증·운영 같은 종류로 나눈다.',
};

/** Adding a person or an item to the organisation (FN-0101). The kind decides the fields. */
export function OrgForm({ start, envKinds, onSubmit, onCancel, error, inSheet = false }: Props) {
  const [d, setD] = useState(start);
  const [busy, setBusy] = useState(false);
  const titleId = useId();
  const whyId = useId();
  const problem = draftProblem(d);
  return (
    <form
      className="wn org-form"
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
        조직에 더하기
      </h2>
      <fieldset className="hf-tools">
        <legend>무엇을</legend>
        <div className="org-kinds">
          {KINDS.map((k) => (
            <label key={k} className="check">
              <input type="radio" name={`${titleId}-kind`} checked={d.kind === k} onChange={() => setD({ ...d, kind: k })} />
              <span>{KIND_LABELS[k]}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <p className="wn-lead">{LEADS[d.kind]}</p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      <label>
        이름
        <input className="field" value={d.name} maxLength={100} onChange={(e) => setD({ ...d, name: e.target.value })} required />
      </label>
      {d.kind === 'person' && (
        <fieldset className="hf-tools org-roles">
          <legend>역할</legend>
          {ROLE_ORDER.map((r) => (
            <label key={r} className="check">
              <input
                type="checkbox"
                checked={d.roles.includes(r)}
                onChange={(e) => setD({ ...d, roles: e.target.checked ? [...d.roles, r] : d.roles.filter((x) => x !== r) })}
              />
              <span>
                {ROLE_LABELS[r]} <small>{ROLE_NOTES[r]}</small>
              </span>
            </label>
          ))}
        </fieldset>
      )}
      {d.kind === 'repository' && (
        <>
          <label>
            저장소 키
            <input className="field" value={d.repoKey} placeholder="git:첫 커밋 번호" onChange={(e) => setD({ ...d, repoKey: e.target.value })} />
          </label>
          <label>
            원격 주소(선택)
            <input className="field" value={d.remote} placeholder="github.com/나/저장소" onChange={(e) => setD({ ...d, remote: e.target.value })} />
          </label>
        </>
      )}
      {d.kind === 'environment' && (
        <label>
          종류
          <span className="select">
            <select value={d.envKind} onChange={(e) => setD({ ...d, envKind: e.target.value })}>
              {envKinds.map((k) => (
                <option key={k} value={k}>
                  {ENV_LABELS[k] ?? k}
                </option>
              ))}
            </select>
            <Icon name="chevron-down" className="chev" />
          </span>
        </label>
      )}
      {d.kind === 'project' && (
        <label>
          종류(선택)
          <input className="field" value={d.projectKind} maxLength={50} placeholder="BE, FE, 인프라" onChange={(e) => setD({ ...d, projectKind: e.target.value })} />
        </label>
      )}
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={problem !== null || busy} aria-describedby={problem ? whyId : undefined} aria-busy={busy || undefined}>
          {KIND_LABELS[d.kind]} 더하기
        </button>
        <button type="button" className="btn" onClick={onCancel}>
          그만두기
        </button>
      </div>
      {problem && (
        <p className="wd-why" id={whyId}>
          <Icon name="lock" />
          <span>{problem}</span>
        </p>
      )}
    </form>
  );
}
