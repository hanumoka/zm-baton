import { useId, useState, type Ref } from 'react';
import { Icon } from '../icons/Icon';
import { StageGlyph } from '../icons/StageGlyph';
import { RowSkeleton } from '../ui/Group';
import { ReadOnly } from '../ui/ReadOnly';
import type { HandoffChoice, Move, Project, Stage, WorkItem, WorkItemDetail } from '../workApi';
import { at, historyLine, moveParts, STAGES, waitLabel } from '../workModel';
import { Handoff } from './Handoff';
import { isBlocked, isClosed } from './workView';

export type DetailActions = {
  move: (to: Stage, note?: string) => Promise<void>;
  update: (fields: Record<string, string | null>) => Promise<void>;
  addWait: (reason: string, note?: string) => Promise<void>;
  removeWait: (reason: string) => Promise<void>;
  /** Hands the work item to an AI (contract "위임"); the server checks whether it may. */
  delegate: (choice: HandoffChoice) => Promise<void>;
  /** Takes back a delegation whose run no connector has claimed yet. */
  withdraw: (runId: string) => Promise<void>;
  /** Stops a claimed or running run (FN-0410); the server says when it may not. */
  cancel?: (runId: string) => Promise<void>;
};

type Props = {
  /** The item as the list has it, drawn at once while its detail loads. */
  item: WorkItem;
  /** The server's detail; ignored while it belongs to another item. */
  detail: WorkItemDetail | null;
  projects: Project[];
  skipped: Stage[];
  actions: DetailActions;
  /** The last refusal of an action on this work item, with the server's reason. */
  error: string | null;
  /** The title takes the focus when Enter opens the item from the list. */
  titleRef?: Ref<HTMLHeadingElement>;
  /** In the narrow sheet the sheet's bar already says where this is. */
  inSheet?: boolean;
};

/**
 * One work item (화면 초안 「작업 상세」): where it is, what may happen next and why not. A copy
 * whose source of truth is a Git file says 「읽기만 한다」 in its head and locks every change, with
 * the server's reason next to the locked buttons.
 */
export function WorkDetail({ item, detail, projects, skipped, actions, error, titleRef, inSheet = false }: Props) {
  const titleId = useId();
  const d = detail !== null && detail.item.id === item.id ? detail : null;
  const it = d ? d.item : item;
  const editable = d !== null && d.read_only_reason === null;
  return (
    <article className="dt" aria-labelledby={titleId}>
      {!inSheet && (
        <nav className="dt-crumb" aria-label="위치">
          <span>작업</span>
          <span aria-hidden="true">/</span>
          {it.external_ref ? <code>{it.external_ref}</code> : <span>여기서 만든 작업</span>}
        </nav>
      )}
      <h2 id={titleId} ref={titleRef} className="dt-title" tabIndex={-1}>
        {it.title}
      </h2>
      <dl className="dt-facts">
        <div>
          <dt>프로젝트</dt>
          <dd>
            {it.project ? it.project.name : '없음'}
            {it.project?.external_ref && <code>{it.project.external_ref}</code>}
          </dd>
        </div>
        <div>
          <dt>책임자</dt>
          <dd>{d ? <code>{d.responsible_user_id}</code> : <span className="wd-wait">읽는 중</span>}</dd>
        </div>
        <div>
          <dt>시작</dt>
          <dd className="num">{it.opened_on ?? '아직'}</dd>
        </div>
        {it.due_on && (
          <div>
            <dt>기한</dt>
            <dd className="num">{it.due_on}</dd>
          </div>
        )}
        {it.closed_at && (
          <div>
            <dt>닫힘</dt>
            <dd className="num">{at(it.closed_at)}</dd>
          </div>
        )}
      </dl>
      {d?.read_only_reason && <ReadOnly gitRef={d.git_ref} reason={d.read_only_reason} />}
      <StagePath stage={it.stage} blocked={isBlocked(it)} skipped={skipped} />
      {d === null ? (
        <RowSkeleton rows={3} />
      ) : (
        <div className="dt-grid">
          <div className="dt-main">
            {error && (
              <p role="alert" className="wd-refused">
                <Icon name="alert" />
                <span>{error}</span>
              </p>
            )}
            <Fields detail={d} editable={editable} onRemoveWait={actions.removeWait} />
            {d.moves.length > 0 && <Moves moves={d.moves} from={it.stage} onMove={actions.move} />}
            <Handoff detail={d} onDelegate={actions.delegate} onWithdraw={actions.withdraw} onCancel={actions.cancel} />
            {editable && !isClosed(it) && <WaitControls reasons={it.wait_reasons} onAdd={actions.addWait} />}
            {editable && <EditFields key={`${it.id}:${it.version}`} detail={d} projects={projects} onSave={actions.update} />}
          </div>
          <aside className="dt-aside" aria-labelledby={`${titleId}-h`}>
            <h3 id={`${titleId}-h`}>이력</h3>
            {d.history.length === 0 ? (
              <p className="dt-none">아직 남은 이력이 없다.</p>
            ) : (
              <ol className="wd-hist">
                {d.history.map((h, i) => (
                  <li key={`${h.at}-${i}`}>
                    <time className="num" dateTime={h.at}>
                      {at(h.at)}
                    </time>
                    <span>{historyLine(h)}</span>
                  </li>
                ))}
              </ol>
            )}
          </aside>
        </div>
      )}
    </article>
  );
}

/**
 * The working stages in order with their glyphs, and 취소 apart at the end. The current stage is
 * bold (and says 막힘 when blocked); stages the organisation skips are dimmed and say 건너뜀.
 */
export function StagePath({ stage, blocked, skipped }: { stage: Stage; blocked: boolean; skipped: Stage[] }) {
  const path = STAGES.filter((s) => s.stage !== 'cancelled');
  const now = path.findIndex((s) => s.stage === stage);
  return (
    <div className="wd-track">
      <ol aria-label="단계">
        {path.map((s, i) => {
          const isNow = i === now;
          const skip = !isNow && skipped.includes(s.stage);
          const state = isNow ? 'is-now' : skip ? 'is-skip' : now >= 0 && i < now ? 'is-past' : 'is-next';
          return (
            <li key={s.stage} className={`wd-t ${state}`} aria-current={isNow ? 'step' : undefined}>
              <StageGlyph stage={s.stage} blocked={isNow && blocked} />
              <span className="wd-t-lb">{s.label}</span>
              {isNow && blocked && <small>막힘</small>}
              {skip && <small>건너뜀</small>}
            </li>
          );
        })}
      </ol>
      <div className={`wd-t wd-off ${stage === 'cancelled' ? 'is-now' : 'is-off'}`} aria-current={stage === 'cancelled' ? 'step' : undefined}>
        <StageGlyph stage="cancelled" />
        <span className="wd-t-lb">취소</span>
      </div>
    </div>
  );
}

/** Next action, acceptance, wait reasons and the reference document, each in its own place. */
function Fields({ detail, editable, onRemoveWait }: { detail: WorkItemDetail; editable: boolean; onRemoveWait: (r: string) => Promise<void> }) {
  const { item } = detail;
  return (
    <>
      <section className="dt-sec">
        <h3>다음 행동</h3>
        {item.next_action ? <p className="dt-text">{item.next_action}</p> : <p className="dt-none">적혀 있지 않다.</p>}
      </section>
      <section className="dt-sec">
        <h3>수락 조건</h3>
        {detail.acceptance ? <p className="dt-text">{detail.acceptance}</p> : <p className="dt-none">적혀 있지 않다.</p>}
      </section>
      <section className="dt-sec">
        <h3>대기 사유</h3>
        {item.wait_reasons.length === 0 ? (
          <p className="dt-none">없다.</p>
        ) : (
          <ul className="wd-waits">
            {item.wait_reasons.map((r) => (
              <li key={r}>
                <p className="wd-wait-k">
                  <StageGlyph stage={item.stage} blocked />
                  {waitLabel(r)}
                  {editable && (r === 'waiting_other' || r === 'waiting_predecessor') && (
                    <button type="button" className="btn btn-quiet" onClick={() => void onRemoveWait(r)}>
                      떼기
                    </button>
                  )}
                </p>
                {r === 'waiting_other' && item.wait_note && <p className="dt-text">{item.wait_note}</p>}
              </li>
            ))}
          </ul>
        )}
      </section>
      <section className="dt-sec">
        <h3>참조 문서</h3>
        {detail.source_ref ? (
          <p className="dt-text">
            <code>{detail.source_ref}</code>
          </p>
        ) : (
          <p className="dt-none">없다.</p>
        )}
      </section>
    </>
  );
}

/**
 * What the server allows next. A refused move stays visible with the server's reason next to it
 * (화면 초안: 할 수 없는 단추는 이유를 보인다). The first move forward is the main action.
 */
function Moves({ moves, from, onMove }: { moves: Move[]; from: Stage; onMove: (to: Stage, note?: string) => Promise<void> }) {
  const whyId = useId();
  const [asking, setAsking] = useState<Move | null>(null);
  const [note, setNote] = useState('');
  const order = (s: Stage) => STAGES.findIndex((x) => x.stage === s);
  const main = moves.find((m) => m.target !== 'cancelled' && order(m.target) > order(from));
  const reasons = [...new Set(moves.filter((m) => !m.allowed).map((m) => m.reason ?? '이유를 받지 못했다'))];
  return (
    <section className="dt-sec">
      <h3>다음 단계</h3>
      <div className="wd-moves">
        {moves.map((m) => {
          const p = moveParts(m, from);
          const sub = [p.note, m.needs_note ? '이유를 적는다' : undefined].filter(Boolean).join(', ');
          return (
            <button
              key={m.target}
              type="button"
              className={m === main ? 'btn btn-primary' : 'btn'}
              disabled={!m.allowed}
              aria-describedby={m.allowed ? undefined : whyId}
              onClick={() => {
                if (m.needs_note) {
                  setAsking(m);
                  setNote('');
                } else {
                  void onMove(m.target);
                }
              }}
            >
              {p.label}
              {sub && <small>{sub}</small>}
            </button>
          );
        })}
      </div>
      {reasons.length > 0 && (
        <p className="wd-why" id={whyId}>
          <Icon name="lock" />
          <span>
            {reasons.length === 1 && moves.every((m) => !m.allowed) ? '단추가 모두 잠겼다. ' : '잠긴 단추가 있다. '}
            {reasons.join(' ')}
          </span>
        </p>
      )}
      {asking && (
        <form
          className="wd-form wd-ask"
          onSubmit={(e) => {
            e.preventDefault();
            if (note.trim() === '') return;
            void onMove(asking.target, note.trim()).then(() => setAsking(null));
          }}
        >
          <label>
            {asking.target === 'in_progress' ? '돌려보내는 이유와 고칠 범위' : '다시 계획하는 이유'}
            <input className="field" value={note} onChange={(e) => setNote(e.target.value)} autoFocus />
          </label>
          <button type="submit" className="btn btn-primary" disabled={note.trim() === ''}>
            {moveParts(asking, from).label}
          </button>
          <button type="button" className="btn" onClick={() => setAsking(null)}>
            그만두기
          </button>
        </form>
      )}
    </section>
  );
}

/** The two wait reasons a person sets by hand; the others come from the server's own paths. */
function WaitControls({ reasons, onAdd }: { reasons: string[]; onAdd: (reason: string, note?: string) => Promise<void> }) {
  const [note, setNote] = useState('');
  return (
    <section className="dt-sec">
      <h3>막힘 표시</h3>
      <form
        className="wd-form"
        onSubmit={(e) => {
          e.preventDefault();
          if (note.trim() === '') return;
          void onAdd('waiting_other', note.trim()).then(() => setNote(''));
        }}
      >
        <label>
          다른 사정으로 막힘: 무엇을 기다리나
          <input className="field" value={note} onChange={(e) => setNote(e.target.value)} placeholder="예: 외부 회신 대기" />
        </label>
        <button type="submit" className="btn" disabled={note.trim() === ''}>
          막힘 표시
        </button>
        {!reasons.includes('waiting_predecessor') && (
          <button type="button" className="btn" onClick={() => void onAdd('waiting_predecessor')}>
            선행 작업 대기 표시
          </button>
        )}
      </form>
    </section>
  );
}

/** Title, next action, acceptance, due date and project. Only changed fields are sent. */
function EditFields({
  detail,
  projects,
  onSave,
}: {
  detail: WorkItemDetail;
  projects: Project[];
  onSave: (fields: Record<string, string | null>) => Promise<void>;
}) {
  const start = {
    title: detail.item.title,
    next_action: detail.item.next_action ?? '',
    acceptance: detail.acceptance ?? '',
    due_on: detail.item.due_on ?? '',
    project_id: detail.item.project?.id ?? '',
  };
  const [form, setForm] = useState(start);
  const [open, setOpen] = useState(false);
  if (!open) {
    return (
      <div className="dt-sec">
        <button type="button" className="btn" onClick={() => setOpen(true)}>
          내용 고치기
        </button>
      </div>
    );
  }
  const changed = Object.fromEntries(
    (Object.keys(form) as (keyof typeof form)[])
      .filter((k) => form[k] !== start[k])
      .map((k) => [k, form[k].trim() === '' && k !== 'title' ? null : form[k].trim()]),
  );
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  return (
    <section className="dt-sec">
      <h3>내용 고치기</h3>
      <form
        className="wd-edit"
        onSubmit={(e) => {
          e.preventDefault();
          if (Object.keys(changed).length === 0) return setOpen(false);
          void onSave(changed).then(() => setOpen(false));
        }}
      >
        <label>
          제목
          <input className="field" value={form.title} onChange={set('title')} required />
        </label>
        <label>
          다음 행동
          <textarea className="field" value={form.next_action} onChange={set('next_action')} rows={2} />
        </label>
        <label>
          수락 조건
          <textarea className="field" value={form.acceptance} onChange={set('acceptance')} rows={2} />
        </label>
        <label>
          기한
          <input className="field" type="date" value={form.due_on} onChange={set('due_on')} />
        </label>
        <label>
          프로젝트
          <span className="select">
            <select value={form.project_id} onChange={set('project_id')}>
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
        <div className="wd-acts">
          <button type="submit" className="btn btn-primary" disabled={form.title.trim() === ''}>
            고친 내용 저장
          </button>
          <button type="button" className="btn" onClick={() => setOpen(false)}>
            그만두기
          </button>
        </div>
      </form>
    </section>
  );
}
