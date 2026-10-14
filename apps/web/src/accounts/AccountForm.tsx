import { useId, useState } from 'react';
import type { AuthKind } from '../accountsApi';
import { Icon } from '../icons/Icon';
import {
  AFFILIATION_LABELS,
  affiliationFor,
  draftProblem,
  KIND_LABELS,
  KIND_NOTES,
  limitValue,
  LIMITS,
  SOURCE_LABELS,
  withLimit,
  type AccountDraft,
} from './accountView';

type Props = {
  mode: 'new' | 'edit';
  start: AccountDraft;
  onSubmit: (draft: AccountDraft) => Promise<void>;
  onCancel: () => void;
  error: string | null;
  inSheet?: boolean;
};

const KINDS: AuthKind[] = ['personal_subscription', 'org_account', 'api_key'];

/**
 * Registering an AI account (FN-0501) or changing it. The kind decides where the sign-in lives: a
 * subscription signs in with the official CLI on that PC, a key sits in that PC's environment
 * variable ANTHROPIC_API_KEY. The form never asks for a key, and refuses text that looks like one
 * before anything is sent. A change keeps the kind; another kind is another account.
 */
export function AccountForm({ mode, start, onSubmit, onCancel, error, inSheet = false }: Props) {
  const [d, setD] = useState(start);
  const [busy, setBusy] = useState(false);
  const titleId = useId();
  const whyId = useId();
  const problem = draftProblem(d);
  const set = (k: 'name' | 'purpose' | 'dataTerms') => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });
  const source = d.authKind === 'api_key' ? 'env:ANTHROPIC_API_KEY' : 'device_login';
  return (
    <form
      className="wn acc-form"
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
        {mode === 'new' ? '새 AI 계정' : 'AI 계정 고치기'}
      </h2>
      <p className="wn-lead">
        계정은 로그인 정보가 아니다. 어느 PC의 어떤 로그인이나 키로 돌지를 가리키는 이름이다. zm-baton은 로그인 정보나 키 값을 받지 않는다.
      </p>
      {error && (
        <p role="alert" className="wd-refused">
          <Icon name="alert" />
          <span>{error}</span>
        </p>
      )}
      <label>
        이름
        <input className="field" value={d.name} onChange={set('name')} required autoFocus={mode === 'new'} placeholder="예: 내 Max 구독" maxLength={100} />
      </label>
      {mode === 'new' ? (
        <fieldset className="hf-tools">
          <legend>종류</legend>
          {KINDS.map((k) => (
            <label key={k} className="check">
              <input
                type="radio"
                name={`${titleId}-kind`}
                checked={d.authKind === k}
                onChange={() => setD({ ...d, authKind: k, affiliation: affiliationFor(k, d.affiliation) })}
              />
              <span>
                {KIND_LABELS[k]} <small>{KIND_NOTES[k]}</small>
              </span>
            </label>
          ))}
        </fieldset>
      ) : (
        <p className="acc-fixed">
          종류 <strong>{KIND_LABELS[d.authKind]}</strong>. 종류와 자격 위치는 바꾸지 않는다. 다른 종류는 새 계정으로 등록한다.
        </p>
      )}
      {d.authKind === 'api_key' ? (
        <fieldset className="hf-tools">
          <legend>소속</legend>
          {(['personal', 'organization'] as const).map((a) => (
            <label key={a} className="check">
              <input type="radio" name={`${titleId}-aff`} checked={d.affiliation === a} onChange={() => setD({ ...d, affiliation: a })} />
              <span>{AFFILIATION_LABELS[a]}</span>
            </label>
          ))}
        </fieldset>
      ) : (
        <p className="acc-fixed">
          소속 <strong>{AFFILIATION_LABELS[affiliationFor(d.authKind, d.affiliation)]}</strong>
        </p>
      )}
      <p className="acc-fixed">
        자격 위치 <strong>{SOURCE_LABELS[source]}</strong>
        <span className="ctl-why">
          {d.authKind === 'api_key'
            ? '맡길 PC에서 zm-baton agent를 띄우는 환경에 ANTHROPIC_API_KEY를 둔다. 실행마다 이 계정 전용 설정 폴더로 띄운다.'
            : '맡길 PC에서 Claude Code에 직접 로그인해 둔다. 실행할 때 셸의 ANTHROPIC_API_KEY 같은 다른 자격은 빼고 띄운다.'}
        </span>
      </p>
      <label>
        목적(선택)
        <input className="field" value={d.purpose} onChange={set('purpose')} placeholder="예: 내 개인 저장소 작업" maxLength={300} />
      </label>
      <label>
        자료 처리 조건(선택)
        <textarea className="field" rows={2} value={d.dataTerms} onChange={set('dataTerms')} placeholder="예: 학습에 쓰지 않는 조건의 플랜" maxLength={500} />
      </label>
      <fieldset className="acc-limits-set">
        <legend>상한(비우면 상한 없음)</legend>
        <div className="wn-row">
          {LIMITS.map((l) => (
            <label key={l.key}>
              {l.label}
              <input
                className="field num"
                inputMode={l.whole ? 'numeric' : 'decimal'}
                value={limitValue(d, l.key)}
                onChange={(e) => setD(withLimit(d, l.key, e.target.value))}
              />
            </label>
          ))}
        </div>
        <span className="ctl-why">비용은 실행기가 가격표로 환산한 값이다. 처음 값은 소유자의 작업을 갑자기 멈추지 않도록 넉넉하게 잡았다.</span>
      </fieldset>
      <div className="wd-acts">
        <button type="submit" className="btn btn-primary" disabled={problem !== null || busy} aria-describedby={problem ? whyId : undefined} aria-busy={busy || undefined}>
          {mode === 'new' ? '등록하기' : '저장하기'}
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
