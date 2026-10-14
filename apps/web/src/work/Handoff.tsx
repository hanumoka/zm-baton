import { useId, useRef, useState } from 'react';
import { Icon } from '../icons/Icon';
import type { RunEvent } from '../api';
import type { AskBody } from '../questionsApi';
import { usePoll } from '../usePoll';
import { workApi, type HandoffChoice, type ItemRun, type Repository, type WorkItemDetail } from '../workApi';
import { endLabel, RunRow } from './RunRow';

/*
 * Handing a work item to an AI (contract v1 "위임") and watching its runs, in the work detail.
 * The server decides whether the item can be handed over, and how: a first run, or a run that
 * continues the last one (contract "실행 권리 청구" 1-2), possibly sending a result back with its
 * reason. This draws that, the form, and the runs (RunRow). Stage 1 never merges: the person
 * merges or drops the branch with Git (DEC-0098).
 */

/** The tool sets a person picks from; the server knows these tools. No Bash. */
export const TOOL_SETS = [
  { key: 'read', label: '읽기만', tools: ['Read', 'Glob', 'Grep'], note: '파일을 바꾸지 못한다. 조사하거나 검토만 시킬 때 고른다' },
  {
    key: 'edit',
    label: '파일 고치기',
    tools: ['Read', 'Glob', 'Grep', 'Edit', 'Write'],
    note: '이 실행만의 작업 트리 안에서 파일을 고친다. 명령 실행(Bash)은 허용하지 않는다',
  },
] as const;

type ToolSet = (typeof TOOL_SETS)[number]['key'];

const repoValue = (r: Pick<Repository, 'device_id' | 'repo_key'>) => `${r.device_id}|${r.repo_key}`;

type Props = {
  detail: WorkItemDetail;
  onDelegate: (choice: HandoffChoice) => Promise<void>;
  onWithdraw: (runId: string) => Promise<void>;
  /** Stops a claimed or running run; the server says when it may not. */
  onCancel?: (runId: string) => Promise<void>;
  /** Records a run's question (FN-0210); absent, runs offer none. */
  onAsk?: (body: AskBody) => Promise<void>;
};

/**
 * The handoff block of a work item: the form (or why it is locked) and the run list. It reads the
 * repositories and runs itself, again after every change to the work item.
 */
export function Handoff({ detail, onDelegate, onWithdraw, onCancel = async () => {}, onAsk }: Props) {
  const id = detail.item.id;
  const [repos, setRepos] = useState<Repository[] | null>(null);
  const [runs, setRuns] = useState<{ id: string; list: ItemRun[] } | null>(null);
  const [held, setHeld] = useState<{ runId: string; events: RunEvent[] } | null>(null);
  // The poll reads what is held through a ref: its closure lives as long as the run does.
  const heldRef = useRef(held);
  heldRef.current = held;
  const [problem, setProblem] = useState<string | null>(null);
  // Sending an instruction does not change the work item; this reads the runs again.
  const [bump, setBump] = useState(0);
  const shown = runs?.id === id ? runs.list : null;
  const newest = shown && shown.length > 0 ? shown[shown.length - 1] : null;

  usePoll(
    async (alive, signal) => {
      const [repoList, runList] = await Promise.all([workApi.repositories(signal), workApi.runs(id, signal)]);
      if (!alive()) return;
      setRepos(repoList);
      setRuns({ id, list: runList });
      setProblem(null);
    },
    3000,
    [id, detail.item.version, bump],
    (e) => setProblem(e instanceof Error ? e.message : String(e)),
  );

  // The newest run's events, for its recent activity: forward from the largest seq held.
  const newestId = newest?.id ?? null;
  usePoll(
    async (alive, signal) => {
      if (newestId === null) return;
      const h = heldRef.current;
      const mine = h?.runId === newestId ? h.events : [];
      const after = mine.reduce((m, e) => Math.max(m, e.seq), 0);
      const page = await workApi.events(newestId, after, signal);
      if (!alive() || page.length === 0) return;
      const seen = new Set(mine.map((e) => e.event_id));
      const next = { runId: newestId, events: [...mine, ...page.filter((e) => !seen.has(e.event_id))].sort((a, b) => a.seq - b.seq) };
      heldRef.current = next;
      setHeld(next);
    },
    2000,
    [newestId, newest?.state],
    () => {}, // the run list already says when the server cannot be read
  );

  const sendInput = async (runId: string, text: string) => {
    await workApi.sendInput(runId, text);
    setBump((b) => b + 1);
  };

  return (
    <>
      <HandoffForm detail={detail} repos={repos} onDelegate={onDelegate} />
      <RunList
        runs={shown}
        events={held && held.runId === newestId ? held.events : []}
        now={Date.now()}
        outcomeUnknown={detail.item.wait_reasons.includes('outcome_unknown')}
        problem={problem}
        onWithdraw={onWithdraw}
        onCancel={onCancel}
        onSendInput={sendInput}
        onAsk={onAsk && detail.question?.allowed ? onAsk : undefined}
      />
    </>
  );
}

/** The words of the form for each way of handing over. */
const MODE_TEXT = {
  first: {
    button: 'AI에게 맡기기',
    submit: 'Claude Code에 맡기기',
    lead: 'Claude Code가 이 작업의 제목·수락 조건·다음 행동을 지시로 받아, 고른 저장소에 이 실행만의 작업 트리와 브랜치를 만들어 일한다.',
    note: '덧붙일 지시(선택)',
  },
  resume: {
    button: '이어서 맡기기',
    submit: 'Claude Code에 이어서 맡기기',
    lead: '이전 실행에 이어서 남은 것을 맡긴다. 같은 PC이고 이전 실행기와 그 하위 프로세스가 끝난 것을 확인하면 같은 작업 트리와 세션을 이어 쓰고, 아니면 이전 실행의 브랜치 끝에서 새 작업 트리로 시작한다.',
    note: '덧붙일 지시(선택)',
  },
  reject_and_resume: {
    button: '돌려보내고 이어서 맡기기',
    submit: '결과를 돌려보내고 이어서 맡기기',
    lead: '결과를 돌려보내고(결과 검토 → 수행) 같은 요청으로 이어서 맡긴다. 돌려보내는 이유가 AI에게 넘기는 지시에 들어간다.',
    note: '돌려보내는 이유와 고칠 점',
  },
} as const;

/**
 * 「AI에게 맡기기」: locked with the server's reason when the item cannot be handed over, else a
 * button that opens the form (repository on this PC, base branch, tools, a note). A run that
 * continues the last one starts with that run's repository and tools.
 */
export function HandoffForm({
  detail,
  repos,
  onDelegate,
  defaultOpen = false,
}: {
  detail: WorkItemDetail;
  repos: Repository[] | null;
  onDelegate: (choice: HandoffChoice) => Promise<void>;
  /** Starts with the form open (the screen opens it with the button). */
  defaultOpen?: boolean;
}) {
  const whyId = useId();
  const availability = detail.delegation ?? { allowed: false, reason: '이 서버는 AI에게 맡기기를 아직 모른다. 서버를 새 판으로 올린다' };
  const mode = availability.mode ?? 'first';
  const previous = mode === 'first' ? null : (availability.resume_from ?? null);
  const firstRepo = previous ? repoValue({ device_id: previous.device_id ?? '', repo_key: previous.repo_key }) : '';
  const firstTools: ToolSet = previous && previous.allowed_tools.some((t) => t === 'Edit' || t === 'Write') ? 'edit' : 'read';
  const [open, setOpen] = useState(defaultOpen);
  const [repo, setRepo] = useState(firstRepo);
  const [branch, setBranch] = useState('');
  const [tools, setTools] = useState<ToolSet>(firstTools);
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const text = MODE_TEXT[mode];

  /** Opens the form; a continuing run starts with the earlier run's repository and tools. */
  const start = () => {
    setRepo(firstRepo);
    setTools(firstTools);
    setBranch('');
    setNote('');
    setOpen(true);
  };

  if (!availability.allowed || !open) {
    return (
      <section className="dt-sec">
        <h3>AI에게 맡기기</h3>
        <button
          type="button"
          className="btn"
          disabled={!availability.allowed}
          aria-describedby={availability.allowed ? undefined : whyId}
          onClick={start}
        >
          <Icon name="ai" />
          {availability.allowed ? (mode === 'resume' && (availability.answers_ready ?? 0) > 0 ? '답을 넣어 이어서 맡기기' : text.button) : 'AI에게 맡기기'}
        </button>
        {!availability.allowed && (
          <p className="wd-why" id={whyId}>
            <Icon name="lock" />
            <span>{availability.reason ?? '이유를 받지 못했다'}</span>
          </p>
        )}
      </section>
    );
  }

  const chosen = repos?.find((r) => repoValue(r) === repo) ?? (repos && repos.length === 1 ? repos[0] : undefined);
  const noRepo = repos !== null && repos.length === 0;
  const set = TOOL_SETS.find((t) => t.key === tools)!;
  const needsNote = availability.needs_note === true;
  return (
    <section className="dt-sec">
      <h3>AI에게 맡기기</h3>
      <form
        className="wd-edit hf"
        onSubmit={(e) => {
          e.preventDefault();
          if (!chosen || (needsNote && !note.trim())) return;
          setBusy(true);
          void onDelegate({
            device_id: chosen.device_id,
            repo_key: chosen.repo_key,
            base_branch: branch.trim() || undefined,
            allowed_tools: [...set.tools],
            note: note.trim() || undefined,
          })
            .then(() => setOpen(false))
            .finally(() => setBusy(false));
        }}
      >
        <p className="wn-lead">{text.lead}</p>
        {mode === 'resume' && (availability.answers_ready ?? 0) > 0 && (
          <p className="hf-prev">답이 온 질문 {availability.answers_ready}개가 이 실행의 지시에 들어간다(이어 가는 이유: 질문의 답이 왔다).</p>
        )}
        {previous && (
          <p className="hf-prev">
            이어 갈 실행 <code>{previous.run_id}</code>
            <span className="hr-dim">{endLabel(previous.end_reason)}</span>
          </p>
        )}
        <label>
          저장소(등록한 PC)
          {noRepo ? (
            <span className="ctl-why">
              등록한 저장소가 없다. 맡길 PC의 터미널에서 <code>zm-baton repo add &lt;폴더&gt;</code>를 실행한다.
            </span>
          ) : (
            <span className="select">
              <select value={chosen ? repoValue(chosen) : repo} onChange={(e) => setRepo(e.target.value)} aria-busy={repos === null || undefined} required>
                {repos === null && <option value="">읽는 중</option>}
                {repos !== null && repos.length > 1 && !chosen && <option value="">저장소를 고른다</option>}
                {(repos ?? []).map((r) => (
                  <option key={repoValue(r)} value={repoValue(r)}>
                    {`${r.name} — ${r.device_name}`}
                  </option>
                ))}
              </select>
              <Icon name="chevron-down" className="chev" />
            </span>
          )}
          {previous && chosen && chosen.device_id !== previous.device_id && (
            <span className="ctl-why">이전 실행과 다른 PC다. 이전 실행의 작업 트리와 브랜치를 쓸 수 없어 기준에서 새로 시작한다.</span>
          )}
        </label>
        <label>
          기준 브랜치(선택)
          <input
            className="field"
            value={branch}
            onChange={(e) => setBranch(e.target.value)}
            placeholder={previous ? `비우면 이전 실행과 같다(${previous.base_branch ?? '그때의 지금 커밋'})` : '비우면 그 저장소의 지금 커밋에서 시작'}
          />
        </label>
        <fieldset className="hf-tools">
          <legend>AI가 쓸 도구</legend>
          {TOOL_SETS.map((t) => (
            <label key={t.key} className="check">
              <input type="radio" name="hf-tools" value={t.key} checked={tools === t.key} onChange={() => setTools(t.key)} />
              <span>
                {t.label} <small className="num">{t.tools.join(', ')}</small>
              </span>
            </label>
          ))}
          <span className="ctl-why">{set.note}</span>
        </fieldset>
        <label>
          {text.note}
          <textarea className="field" rows={3} value={note} onChange={(e) => setNote(e.target.value)} required={needsNote} maxLength={1000} />
        </label>
        <div className="wd-acts">
          <button type="submit" className="btn btn-primary" disabled={!chosen || busy || (needsNote && !note.trim())} aria-busy={busy || undefined}>
            {text.submit}
          </button>
          <button type="button" className="btn" onClick={() => setOpen(false)}>
            그만두기
          </button>
        </div>
      </form>
    </section>
  );
}

/** The runs of the work item, newest first, each with what a person needs to act on it. */
export function RunList({
  runs,
  events,
  now,
  outcomeUnknown,
  problem,
  onWithdraw,
  onCancel = async () => {},
  onSendInput = async () => {},
  onAsk,
}: {
  runs: ItemRun[] | null;
  /** Events of the newest run, for its status and recent activity. */
  events: RunEvent[];
  now: number;
  outcomeUnknown: boolean;
  problem: string | null;
  onWithdraw: (runId: string) => Promise<void>;
  onCancel?: (runId: string) => Promise<void>;
  onSendInput?: (runId: string, text: string) => Promise<void>;
  /** Records a run's question; absent while the work item takes none. */
  onAsk?: (body: AskBody) => Promise<void>;
}) {
  return (
    <section className="dt-sec">
      <h3>실행 기록</h3>
      {problem && <p className="ctl-err">실행 기록을 읽지 못했다. {problem}</p>}
      {runs === null ? (
        problem ? null : <p className="dt-none">읽는 중</p>
      ) : runs.length === 0 ? (
        <p className="dt-none">아직 AI가 실행한 적이 없다.</p>
      ) : (
        <ol className="hr-runs">
          {[...runs].reverse().map((run, i) => (
            <RunRow
              key={run.id}
              run={run}
              events={i === 0 ? events : []}
              now={now}
              outcomeUnknown={i === 0 && outcomeUnknown}
              onWithdraw={onWithdraw}
              onCancel={onCancel}
              onSendInput={onSendInput}
              onAsk={onAsk}
            />
          ))}
        </ol>
      )}
    </section>
  );
}
