import { useId, useRef, useState } from 'react';
import { Icon } from '../icons/Icon';
import type { RunEvent } from '../api';
import { reportStatus, statusLabel, summary } from '../runState';
import { usePoll } from '../usePoll';
import { workApi, type HandoffChoice, type ItemRun, type Repository, type WorkItemDetail } from '../workApi';
import { at } from '../workModel';

/*
 * Handing a work item to an AI (contract v1 "위임") and watching its runs, in the work detail.
 * The server decides whether the item can be handed over and says why not; this draws that, the
 * form, and each run: its status, recent activity, its own branch and the files it changed.
 * Stage 1 never merges: the person merges or drops the branch with Git (DEC-0098).
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

/** Activity the person reads: not the connector's own lifecycle and change lists. */
const isActivity = (e: RunEvent) => e.kind !== 'lifecycle' && e.kind !== 'git_changes';

const RECENT = 6;

/** Contract activity kinds in words (contract "공통 활동 종류"). */
const KIND_LABELS: Record<string, string> = {
  thought: '생각',
  action: '동작',
  action_result: '결과',
  answer: '답',
  question: '질문',
  error: '오류',
  usage: '사용량',
};

type Props = {
  detail: WorkItemDetail;
  onDelegate: (choice: HandoffChoice) => Promise<void>;
  onWithdraw: (runId: string) => Promise<void>;
};

/**
 * The handoff block of a work item: the form (or why it is locked) and the run list. It reads the
 * repositories and runs itself, again after every change to the work item.
 */
export function Handoff({ detail, onDelegate, onWithdraw }: Props) {
  const id = detail.item.id;
  const [repos, setRepos] = useState<Repository[] | null>(null);
  const [runs, setRuns] = useState<{ id: string; list: ItemRun[] } | null>(null);
  const [held, setHeld] = useState<{ runId: string; events: RunEvent[] } | null>(null);
  // The poll reads what is held through a ref: its closure lives as long as the run does.
  const heldRef = useRef(held);
  heldRef.current = held;
  const [problem, setProblem] = useState<string | null>(null);
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
    [id, detail.item.version],
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
      />
    </>
  );
}

/**
 * 「AI에게 맡기기」: locked with the server's reason when the item cannot be handed over, else a
 * button that opens the form (repository on this PC, base branch, tools).
 */
export function HandoffForm({
  detail,
  repos,
  onDelegate,
}: {
  detail: WorkItemDetail;
  repos: Repository[] | null;
  onDelegate: (choice: HandoffChoice) => Promise<void>;
}) {
  const whyId = useId();
  const [open, setOpen] = useState(false);
  const [repo, setRepo] = useState('');
  const [branch, setBranch] = useState('');
  const [tools, setTools] = useState<ToolSet>('read');
  const [busy, setBusy] = useState(false);
  const availability = detail.delegation ?? { allowed: false, reason: '이 서버는 AI에게 맡기기를 아직 모른다. 서버를 새 판으로 올린다' };

  if (!availability.allowed || !open) {
    return (
      <section className="dt-sec">
        <h3>AI에게 맡기기</h3>
        <button
          type="button"
          className="btn"
          disabled={!availability.allowed}
          aria-describedby={availability.allowed ? undefined : whyId}
          onClick={() => setOpen(true)}
        >
          <Icon name="ai" />
          AI에게 맡기기
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
  return (
    <section className="dt-sec">
      <h3>AI에게 맡기기</h3>
      <form
        className="wd-edit hf"
        onSubmit={(e) => {
          e.preventDefault();
          if (!chosen) return;
          setBusy(true);
          void onDelegate({
            device_id: chosen.device_id,
            repo_key: chosen.repo_key,
            base_branch: branch.trim() || undefined,
            allowed_tools: [...set.tools],
          })
            .then(() => setOpen(false))
            .finally(() => setBusy(false));
        }}
      >
        <p className="wn-lead">Claude Code가 이 작업의 제목·수락 조건·다음 행동을 지시로 받아, 고른 저장소에 이 실행만의 작업 트리와 브랜치를 만들어 일한다.</p>
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
        </label>
        <label>
          기준 브랜치(선택)
          <input className="field" value={branch} onChange={(e) => setBranch(e.target.value)} placeholder="비우면 그 저장소의 지금 커밋에서 시작" />
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
        <div className="wd-acts">
          <button type="submit" className="btn btn-primary" disabled={!chosen || busy} aria-busy={busy || undefined}>
            Claude Code에 맡기기
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
}: {
  runs: ItemRun[] | null;
  /** Events of the newest run, for its status and recent activity. */
  events: RunEvent[];
  now: number;
  outcomeUnknown: boolean;
  problem: string | null;
  onWithdraw: (runId: string) => Promise<void>;
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
            />
          ))}
        </ol>
      )}
    </section>
  );
}

function RunRow({
  run,
  events,
  now,
  outcomeUnknown,
  onWithdraw,
}: {
  run: ItemRun;
  events: RunEvent[];
  now: number;
  outcomeUnknown: boolean;
  onWithdraw: (runId: string) => Promise<void>;
}) {
  const d = run.delegation;
  const ws = run.workspace;
  const ended = run.state === 'ended';
  const recent = events.filter(isActivity).slice(-RECENT);
  const lastError = [...events].reverse().find((e) => e.kind === 'error');
  const changes = run.git_changes;
  const overBranch = new Set(changes?.base_branch_overlap ?? []);
  const overFolder = new Set(changes?.base_overlap ?? []);
  return (
    <li className="hr-run">
      <p className="hr-head">
        <strong data-testid="run-status">{statusLabel(reportStatus(run, events, now))}</strong>
        <span>{run.executor === 'claude_code' ? 'Claude Code' : run.executor}</span>
        <time className="num" dateTime={run.created_at}>
          {at(run.created_at)}
        </time>
        <code>{run.id}</code>
      </p>
      <dl className="dt-facts hr-facts">
        {d && (
          <div>
            <dt>저장소</dt>
            <dd>
              {d.repo_name ?? d.repo_key}
              {d.device_name && <span className="hr-dim">{d.device_name}</span>}
            </dd>
          </div>
        )}
        {(ws || d?.base_branch) && (
          <div>
            <dt>기준</dt>
            <dd>
              {ws?.base_ref ?? d?.base_branch ?? '지금 커밋'}
              {ws && <code>{ws.base_commit.slice(0, 10)}</code>}
            </dd>
          </div>
        )}
        {d && (
          <div>
            <dt>도구</dt>
            <dd>{d.allowed_tools.join(', ')}</dd>
          </div>
        )}
      </dl>
      {run.state === 'requested' && d && !d.revoked_at && (
        <div className="hr-wait">
          <p className="dt-none">연결 프로그램이 아직 가져가지 않았다. 그 PC에서 <code>zm-baton agent</code>가 떠 있는지 본다.</p>
          <button type="button" className="btn" onClick={() => void onWithdraw(run.id)}>
            맡긴 일 거두기
          </button>
        </div>
      )}
      {!ended && recent.length > 0 && (
        <>
          <p className="hr-label">최근 활동</p>
          <ol className="hr-acts">
            {recent.map((e) => (
              <li key={e.event_id}>
                <span className="hr-kind">{KIND_LABELS[e.kind] ?? e.kind}</span>
                <span>{summary(e)}</span>
              </li>
            ))}
          </ol>
        </>
      )}
      {ended && lastError && run.end_reason !== 'submitted' && <p className="ctl-err">{summary(lastError)}</p>}
      {outcomeUnknown && ended && run.end_reason !== 'submitted' && (
        <p className="hr-warn">
          <Icon name="alert" />
          <span>실행기가 결과를 내기 전에 끝났다. 작업 트리에 반쯤 고친 파일이 있을 수 있어 작업에 「결과 확인 필요」가 붙었다.</span>
        </p>
      )}
      {ws && (
        <p className="hr-branch">
          실행 브랜치 <code>{ws.branch}</code>
        </p>
      )}
      {changes && (
        <>
          <p className="hr-label">
            바뀐 파일 <span className="num">{changes.files.length}</span>개{changes.truncated ? ' (앞부분만)' : ''}
            {!ended && ' (실행 중 마지막으로 잰 것)'}
          </p>
          {changes.files.length > 0 && (
            <ul className="hr-files">
              {changes.files.map((f) => (
                <li key={f}>
                  <code>{f}</code>
                  {overBranch.has(f) && <small className="hr-flag">원래 브랜치에서도 바뀜</small>}
                  {overFolder.has(f) && <small className="hr-flag">원래 폴더에도 고치는 중</small>}
                </li>
              ))}
            </ul>
          )}
        </>
      )}
      {ended && ws && (
        <p className="hr-merge">
          <Icon name="git-file" />
          <span>
            1단계에서는 zm-baton이 합치지 않는다. 이 브랜치를 Git으로 직접 합치거나 버린다. 작업 트리에 커밋하지 않은 변경이 남아 있을 수 있으니 그 폴더에서
            먼저 확인한다.
          </span>
        </p>
      )}
      {run.instruction && (
        <details className="hr-inst">
          <summary>AI에게 넘긴 지시</summary>
          <p className="dt-text">{run.instruction}</p>
        </details>
      )}
    </li>
  );
}
