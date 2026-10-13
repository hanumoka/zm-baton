import { useId, type ReactNode } from 'react';
import { Icon } from '../icons/Icon';

/*
 * Every part in its seven states (DESIGN.md 「상태와 문구」). Hover and active are held on with the
 * classes `is-hover` and `is-active`, and focus with `is-focus`, so they show without a mouse.
 * The parts are real elements: tab into them to see the real focus ring.
 */

export const STATES = ['default', 'hover', 'focus', 'active', 'disabled', 'loading', 'error'] as const;
export type State = (typeof STATES)[number];

const STATE_LABEL: Record<State, string> = {
  default: '기본',
  hover: 'hover',
  focus: 'focus',
  active: 'active',
  disabled: 'disabled',
  loading: 'loading',
  error: 'error',
};

/** The forced class for a state that otherwise needs a mouse or a key. */
const forced = (s: State) => (s === 'hover' ? 'is-hover' : s === 'focus' ? 'is-focus' : s === 'active' ? 'is-active' : '');
const cx = (...names: (string | false | undefined)[]) => names.filter(Boolean).join(' ');

function Why({ id, children }: { id: string; children: ReactNode }) {
  return (
    <small id={id} className="ctl-why">
      {children}
    </small>
  );
}

function Err({ id, children }: { id: string; children: ReactNode }) {
  return (
    <small id={id} className="ctl-err">
      {children}
    </small>
  );
}

function ButtonSample({ s, primary = false }: { s: State; primary?: boolean }) {
  const id = useId();
  const base = cx('btn', primary && 'btn-primary', forced(s), s === 'error' && 'is-error');
  const label = primary ? '새 작업' : '다시 읽기';
  const icon = primary ? <Icon name="plus" /> : null;
  if (s === 'disabled') {
    return (
      <>
        <button type="button" className={base} disabled aria-describedby={id}>
          {icon}
          {label}
        </button>
        <Why id={id}>{primary ? '정본이 Git 파일인 프로젝트라 여기서 만들 수 없다' : '서버에 연결되지 않아 누를 수 없다'}</Why>
      </>
    );
  }
  if (s === 'loading') {
    return (
      <button type="button" className={base} aria-busy="true" aria-disabled="true">
        {icon}
        {primary ? '만드는 중' : '읽는 중'}
      </button>
    );
  }
  if (s === 'error') {
    return (
      <>
        <button type="button" className={base} aria-describedby={id}>
          {icon}
          {primary ? '다시 만들기' : '다시 읽기'}
        </button>
        <Err id={id}>보내지 못했다: HTTP 503. 서버를 켠 뒤 다시 누른다</Err>
      </>
    );
  }
  return (
    <button type="button" className={base}>
      {icon}
      {label}
    </button>
  );
}

function FieldSample({ s }: { s: State }) {
  const id = useId();
  const base = cx('field', forced(s));
  if (s === 'disabled') {
    return (
      <>
        <input className={base} disabled defaultValue="TSK-021" aria-label="번호" aria-describedby={id} />
        <Why id={id}>정본이 Git 파일이라 고칠 수 없다</Why>
      </>
    );
  }
  if (s === 'loading') return <input className={base} readOnly aria-busy="true" placeholder="불러오는 중" aria-label="다음 행동" />;
  if (s === 'error') {
    return (
      <>
        <input className={base} aria-invalid="true" aria-describedby={id} aria-label="제목" placeholder="제목" />
        <Err id={id}>제목을 써야 만들 수 있다</Err>
      </>
    );
  }
  return <input className={base} placeholder="다음 행동" aria-label="다음 행동" defaultValue={s === 'active' ? '재시작 뒤 확인' : undefined} />;
}

function CheckSample({ s }: { s: State }) {
  const id = useId();
  const base = cx('check', forced(s), s === 'disabled' && 'is-disabled', s === 'loading' && 'is-loading', s === 'error' && 'is-error');
  return (
    <>
      <label className={base}>
        <input
          type="checkbox"
          defaultChecked={s === 'active'}
          disabled={s === 'disabled' || s === 'loading'}
          aria-busy={s === 'loading' || undefined}
          aria-invalid={s === 'error' || undefined}
          aria-describedby={s === 'disabled' || s === 'error' ? id : undefined}
        />
        {s === 'loading' ? '바꾸는 중' : '끝난 세션도 보기'}
      </label>
      {s === 'disabled' && <Why id={id}>세션 목록을 읽은 뒤 고를 수 있다</Why>}
      {s === 'error' && <Err id={id}>설정을 저장하지 못했다. 다시 누른다</Err>}
    </>
  );
}

function SelectSample({ s }: { s: State }) {
  const id = useId();
  const base = cx('select', forced(s), s === 'error' && 'is-error');
  return (
    <>
      <span className={base}>
        <select
          aria-label="프로젝트"
          disabled={s === 'disabled' || s === 'loading'}
          aria-busy={s === 'loading' || undefined}
          aria-invalid={s === 'error' || undefined}
          aria-describedby={s === 'disabled' || s === 'error' ? id : undefined}
          defaultValue={s === 'loading' ? 'loading' : 'all'}
        >
          {s === 'loading' ? <option value="loading">불러오는 중</option> : <option value="all">모든 프로젝트</option>}
          <option value="zm-docs">zm-docs</option>
        </select>
        <Icon name="chevron-down" className="chev" />
      </span>
      {s === 'disabled' && <Why id={id}>프로젝트가 아직 없다</Why>}
      {s === 'error' && <Err id={id}>프로젝트 목록을 읽지 못했다</Err>}
    </>
  );
}

function ChipSample({ s }: { s: State }) {
  const id = useId();
  const base = cx('chip', forced(s), s === 'disabled' && 'is-disabled', s === 'loading' && 'is-loading', s === 'error' && 'is-error');
  return (
    <>
      <label className={base}>
        단계
        <select
          aria-label="단계"
          disabled={s === 'disabled' || s === 'loading'}
          aria-busy={s === 'loading' || undefined}
          aria-describedby={s === 'disabled' || s === 'error' ? id : undefined}
          defaultValue="open"
        >
          <option value="open">{s === 'loading' ? '세는 중' : '열린 것'}</option>
          <option value="all">전체</option>
        </select>
        <Icon name="chevron-down" className="chev" />
      </label>
      {s === 'disabled' && <Why id={id}>작업이 없어 거를 것이 없다</Why>}
      {s === 'error' && <Err id={id}>거른 목록을 읽지 못했다</Err>}
    </>
  );
}

const PARTS: { name: string; render: (s: State) => ReactNode }[] = [
  { name: '단추', render: (s) => <ButtonSample s={s} /> },
  { name: '주 단추', render: (s) => <ButtonSample s={s} primary /> },
  { name: '입력란', render: (s) => <FieldSample s={s} /> },
  { name: '체크박스', render: (s) => <CheckSample s={s} /> },
  { name: '선택 상자', render: (s) => <SelectSample s={s} /> },
  { name: '거르기 칩', render: (s) => <ChipSample s={s} /> },
];

export function StateGallery() {
  return (
    <div className="states">
      {PARTS.map((p) => (
        <section key={p.name} className="state-part" aria-label={p.name}>
          <h3>{p.name}</h3>
          <div className="state-cells">
            {STATES.map((s) => (
              <div key={s} className="state-cell">
                <span className="state-lbl">{STATE_LABEL[s]}</span>
                {p.render(s)}
              </div>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
