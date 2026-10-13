import { useState } from 'react';
import type { Move, Project, Stage, WorkItemDetail } from './workApi';
import { at, historyLine, moveLabel, STAGES, waitLabel, workTitle } from './workModel';

export type DetailActions = {
  move: (to: Stage, note?: string) => Promise<void>;
  update: (fields: Record<string, string | null>) => Promise<void>;
  addWait: (reason: string, note?: string) => Promise<void>;
  removeWait: (reason: string) => Promise<void>;
};

type Props = {
  detail: WorkItemDetail;
  projects: Project[];
  skipped: Stage[];
  actions: DetailActions;
  /** The last refusal of an action on this work item, with the server's reason. */
  error: string | null;
};

/** One work item (화면 초안 「작업 상세」): where it is, what may happen next and why not. */
export function WorkDetail({ detail, projects, skipped, actions, error }: Props) {
  const { item } = detail;
  const editable = detail.read_only_reason === null;
  const closed = item.stage === 'done' || item.stage === 'cancelled';
  return (
    <section className="detail" aria-label="작업 상세">
      <h2>{workTitle(item)}</h2>
      <p className="meta">
        {item.project ? `${item.project.external_ref ?? ''} ${item.project.name}`.trim() : '프로젝트 없음'}
        {' · '}책임자 {detail.responsible_user_id}
        {item.opened_on && ` · 시작 ${item.opened_on}`}
        {item.due_on && ` · 기한 ${item.due_on}`}
        {item.closed_at && ` · 닫힘 ${at(item.closed_at)}`}
      </p>
      {detail.read_only_reason && (
        <p className="source">
          {detail.read_only_reason}
          {detail.git_ref?.commit && <small> (읽은 커밋 {detail.git_ref.commit.slice(0, 10)})</small>}
        </p>
      )}
      <StageBar stage={item.stage} skipped={skipped} />
      {error && (
        <p role="alert" className="refused">
          {error}
        </p>
      )}
      <dl className="fields">
        <dt>다음 행동</dt>
        <dd>{item.next_action ?? <small>없음</small>}</dd>
        <dt>수락 조건</dt>
        <dd>{detail.acceptance ?? <small>없음</small>}</dd>
        <dt>대기 사유</dt>
        <dd>
          {item.wait_reasons.length === 0 && <small>없음</small>}
          <ul className="waits">
            {item.wait_reasons.map((r) => (
              <li key={r}>
                {waitLabel(r)}
                {r === 'waiting_other' && item.wait_note && <> — {item.wait_note}</>}
                {editable && (r === 'waiting_other' || r === 'waiting_predecessor') && (
                  <button onClick={() => void actions.removeWait(r)}>떼기</button>
                )}
              </li>
            ))}
          </ul>
        </dd>
        {detail.source_ref && (
          <>
            <dt>참조 문서</dt>
            <dd>
              <code>{detail.source_ref}</code>
            </dd>
          </>
        )}
      </dl>
      {detail.moves.length > 0 && (
        <Moves moves={detail.moves} from={item.stage} explain={detail.read_only_reason === null} onMove={actions.move} />
      )}
      {editable && !closed && <WaitControls reasons={item.wait_reasons} onAdd={actions.addWait} />}
      {editable && <EditFields key={`${item.id}:${item.version}`} detail={detail} projects={projects} onSave={actions.update} />}
      <h3>이력</h3>
      <ol className="history">
        {detail.history.map((h, i) => (
          <li key={`${h.at}-${i}`}>
            <small>{at(h.at)}</small> {historyLine(h)}
          </li>
        ))}
      </ol>
    </section>
  );
}

/** The six working stages with the current one marked; stages the organisation skips are dimmed. */
export function StageBar({ stage, skipped }: { stage: Stage; skipped: Stage[] }) {
  return (
    <ol className="stagebar" aria-label="단계">
      {STAGES.filter((s) => s.stage !== 'cancelled' || stage === 'cancelled').map((s) => (
        <li
          key={s.stage}
          className={[s.stage === stage ? 'current' : '', skipped.includes(s.stage) ? 'skipped' : ''].join(' ').trim() || undefined}
          aria-current={s.stage === stage ? 'step' : undefined}
        >
          {s.label}
        </li>
      ))}
    </ol>
  );
}

/** What the server allows next. A refused move stays visible with the reason (화면 초안: 할 수 없는 단추는 이유를 보인다). */
function Moves({
  moves,
  from,
  explain,
  onMove,
}: {
  moves: Move[];
  from: Stage;
  /** False when the reason is already shown above (a read-only copy). */
  explain: boolean;
  onMove: (to: Stage, note?: string) => Promise<void>;
}) {
  const [asking, setAsking] = useState<Move | null>(null);
  const [note, setNote] = useState('');
  return (
    <div className="moves">
      <h3>다음 단계</h3>
      <div className="buttons">
        {moves.map((m) => (
          <button
            key={m.target}
            disabled={!m.allowed}
            className={m.target === 'cancelled' ? 'quiet' : undefined}
            onClick={() => {
              if (m.needs_note) {
                setAsking(m);
                setNote('');
              } else {
                void onMove(m.target);
              }
            }}
          >
            {moveLabel(m, from)}
          </button>
        ))}
      </div>
      {explain && moves.some((m) => !m.allowed) && (
        <ul className="why">
          {[...new Set(moves.filter((m) => !m.allowed).map((m) => m.reason ?? ''))].map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      )}
      {asking && (
        <form
          className="reason"
          onSubmit={(e) => {
            e.preventDefault();
            if (note.trim() === '') return;
            void onMove(asking.target, note.trim()).then(() => setAsking(null));
          }}
        >
          <label>
            {asking.target === 'in_progress' ? '돌려보내는 이유와 고칠 범위' : '다시 계획하는 이유'}
            <input value={note} onChange={(e) => setNote(e.target.value)} autoFocus />
          </label>
          <button type="submit" disabled={note.trim() === ''}>
            {moveLabel(asking, from)}
          </button>
          <button type="button" onClick={() => setAsking(null)}>
            그만두기
          </button>
        </form>
      )}
    </div>
  );
}

/** The two wait reasons a person sets by hand; the others come from the server's own paths. */
function WaitControls({ reasons, onAdd }: { reasons: string[]; onAdd: (reason: string, note?: string) => Promise<void> }) {
  const [note, setNote] = useState('');
  return (
    <form
      className="reason"
      onSubmit={(e) => {
        e.preventDefault();
        if (note.trim() === '') return;
        void onAdd('waiting_other', note.trim()).then(() => setNote(''));
      }}
    >
      <label>
        다른 사정으로 막힘 — 무엇을 기다리나
        <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="예: 외부 회신 대기" />
      </label>
      <button type="submit" disabled={note.trim() === ''}>
        막힘 표시
      </button>
      {!reasons.includes('waiting_predecessor') && (
        <button type="button" onClick={() => void onAdd('waiting_predecessor')}>
          선행 작업 대기 표시
        </button>
      )}
    </form>
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
      <p>
        <button onClick={() => setOpen(true)}>내용 고치기</button>
      </p>
    );
  }
  const changed = Object.fromEntries(
    (Object.keys(form) as (keyof typeof form)[])
      .filter((k) => form[k] !== start[k])
      .map((k) => [k, form[k].trim() === '' && k !== 'title' ? null : form[k].trim()]),
  );
  const set = (k: keyof typeof form) => (e: { target: { value: string } }) => setForm({ ...form, [k]: e.target.value });
  return (
    <form
      className="edit"
      onSubmit={(e) => {
        e.preventDefault();
        if (Object.keys(changed).length === 0) return setOpen(false);
        void onSave(changed).then(() => setOpen(false));
      }}
    >
      <label>
        제목
        <input value={form.title} onChange={set('title')} required />
      </label>
      <label>
        다음 행동
        <textarea value={form.next_action} onChange={set('next_action')} rows={2} />
      </label>
      <label>
        수락 조건
        <textarea value={form.acceptance} onChange={set('acceptance')} rows={2} />
      </label>
      <label>
        기한
        <input type="date" value={form.due_on} onChange={set('due_on')} />
      </label>
      <label>
        프로젝트
        <select value={form.project_id} onChange={set('project_id')}>
          <option value="">없음</option>
          {projects.map((p) => (
            <option key={p.id} value={p.id}>
              {`${p.external_ref ?? ''} ${p.name}`.trim()}
            </option>
          ))}
        </select>
      </label>
      <div className="buttons">
        <button type="submit" disabled={form.title.trim() === ''}>
          저장
        </button>
        <button type="button" onClick={() => setOpen(false)}>
          그만두기
        </button>
      </div>
    </form>
  );
}
