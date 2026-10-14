import { useId, useState } from 'react';
import { Icon } from '../icons/Icon';
import type { RunEvent } from '../api';
import { reportStatus, statusLabel, summary } from '../runState';
import { tokensLine } from '../accounts/accountView';
import type { ItemRun, RunInput } from '../workApi';
import { at } from '../workModel';
import { billingLine, limitsLine, methodLabel, stopLine, usageLine } from './runUsage';

/*
 * One run of a work item in its run list: its report status, where it worked, and what a person can
 * do with it (FN-0410). A run that has not ended can be stopped, and a running executor that takes
 * input can get a short instruction; the server says when either is not possible and why. A run that
 * went silent shows when the server will give it up, and a run stopped by the readiness check shows
 * which check failed. A run on an AI account shows the account and how it signed in, its limits,
 * what it used and where that is charged, and which limit or signal stopped it. The screen decides
 * nothing itself.
 */

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

const CONTINUE_LABELS: Record<string, string> = {
  resume_after_cut: '끊기거나 멈춘 실행을 이었다',
  result_rejected: '돌려보낸 결과를 고친다',
  answer_arrived: '질문의 답이 왔다',
  replacement: '다른 계정이나 기기로 넘겼다',
};

const ORIGIN_LABELS: Record<string, string> = {
  reused: '이전 실행의 작업 트리와 세션을 이어 썼다',
  previous_branch: '이전 실행의 브랜치 끝에서 새 작업 트리를 만들었다',
};

const INPUT_LABELS: Record<RunInput['state'], string> = {
  pending: '연결 프로그램에 전달 대기',
  sent: '실행기에 넣음, 받기 대기',
  delivered: '실행기가 받음',
  undelivered: '전달하지 못함',
};

const CHECK_LABELS: Record<string, string> = {
  scope: '위임 범위',
  executor: '실행기',
  repository: '저장소',
  worktree_folder: '작업 트리 폴더',
  disk: '디스크',
  base_branch: '기준',
  worktree: '작업 트리',
  resume: '이어 쓰기',
  account: '계정',
};

/** Contract end reasons in words. */
export function endLabel(reason: string | null | undefined): string {
  switch (reason) {
    case 'submitted':
      return '결과 제출';
    case 'failed':
      return '오류';
    case 'cancelled':
      return '취소됨';
    case 'lost':
      return '연결 끊김';
    case 'replaced':
      return '다른 실행으로 넘김';
    default:
      return reason ?? '';
  }
}

type Props = {
  run: ItemRun;
  events: RunEvent[];
  now: number;
  outcomeUnknown: boolean;
  onWithdraw: (runId: string) => Promise<void>;
  onCancel: (runId: string) => Promise<void>;
  onSendInput: (runId: string, text: string) => Promise<void>;
};

export function RunRow({ run, events, now, outcomeUnknown, onWithdraw, onCancel, onSendInput }: Props) {
  const d = run.delegation;
  const ws = run.workspace;
  const ended = run.state === 'ended';
  const recent = events.filter(isActivity).slice(-RECENT);
  const lastError = [...events].reverse().find((e) => e.kind === 'error');
  const changes = run.git_changes;
  const overBranch = new Set(changes?.base_branch_overlap ?? []);
  const overFolder = new Set(changes?.base_overlap ?? []);
  const readiness = run.readiness;
  const failedChecks = readiness && !readiness.ok ? readiness.checks.filter((c) => !c.ok) : [];
  const limits = limitsLine(run);
  const used = usageLine(run);
  const billing = billingLine(run);
  const stop = stopLine(run);
  return (
    <li className="hr-run">
      <p className="hr-head">
        <strong data-testid="run-status">{statusLabel(reportStatus(run, events, now))}</strong>
        {run.state === 'stopping' && <small className="hr-flag">취소 요청함</small>}
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
        {run.account && (
          <div>
            <dt>계정</dt>
            <dd>
              {run.account.name}
              {run.auth_method && <span className="hr-dim">{methodLabel(run.auth_method)}</span>}
            </dd>
          </div>
        )}
        {limits && (
          <div>
            <dt>상한</dt>
            <dd>{limits}</dd>
          </div>
        )}
        {used && (
          <div>
            <dt>사용량</dt>
            <dd>{used}</dd>
          </div>
        )}
        {billing && (
          <div>
            <dt>과금 위치</dt>
            <dd>{billing}</dd>
          </div>
        )}
        {run.resumed_from_run_id && (
          <div>
            <dt>이어서</dt>
            <dd>
              {CONTINUE_LABELS[run.continue_reason ?? ''] ?? run.continue_reason}
              <code>{run.resumed_from_run_id}</code>
            </dd>
          </div>
        )}
      </dl>
      {ws?.origin && ORIGIN_LABELS[ws.origin] && <p className="hr-branch">{ORIGIN_LABELS[ws.origin]}</p>}
      {stop && (
        <p className="hr-warn" role="status">
          <Icon name="alert" />
          <span>{stop}</span>
        </p>
      )}
      {run.usage && <p className="hr-branch num">토큰: {tokensLine(run.usage)}</p>}
      {run.state === 'requested' && d && !d.revoked_at && (
        <div className="hr-wait">
          <p className="dt-none">연결 프로그램이 아직 가져가지 않았다. 그 PC에서 <code>zm-baton agent</code>가 떠 있는지 본다.</p>
          <button type="button" className="btn" onClick={() => void onWithdraw(run.id)}>
            맡긴 일 거두기
          </button>
        </div>
      )}
      {!ended && run.lease_lapsed && (
        <p className="hr-warn" role="status">
          <Icon name="alert" />
          <span>
            연결 프로그램의 신호가 끊겼다{run.lease_expires_at ? ` (임대 만료 ${at(run.lease_expires_at)})` : ''}.
            {run.lost_at ? ` ${at(run.lost_at)}까지 돌아오지 않으면 서버가 이 실행을 「연결 끊김」으로 끝낸다.` : ''} 그 PC에서{' '}
            <code>zm-baton agent</code>가 떠 있는지 본다.
          </span>
        </p>
      )}
      {!ended && run.state !== 'requested' && run.actions && (
        <RunControls run={run} onCancel={onCancel} onSendInput={onSendInput} />
      )}
      {run.inputs && run.inputs.length > 0 && (
        <>
          <p className="hr-label">사람이 보낸 지시</p>
          <ol className="hr-inputs">
            {run.inputs.map((i) => (
              <li key={i.id}>
                <span className="dt-text">{i.text}</span>
                <small className={i.state === 'undelivered' ? 'hr-flag' : 'hr-dim'}>{INPUT_LABELS[i.state] ?? i.state}</small>
              </li>
            ))}
          </ol>
          {ended && run.inputs.some((i) => i.state === 'undelivered') && (
            <p className="hr-dim hr-note">전달하지 못한 지시는 「이어서 맡기기」의 덧붙일 지시에 넣어 다시 보낸다.</p>
          )}
        </>
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
      {failedChecks.length > 0 && (
        <div className="hr-warn">
          <Icon name="alert" />
          <div>
            <p className="hr-tight">시작 전 준비 검사에서 멈춰 실행기를 띄우지 않았다. 고친 뒤 다시 맡긴다.</p>
            <ul className="hr-checks">
              {failedChecks.map((c) => (
                <li key={c.name}>{c.detail || (CHECK_LABELS[c.name] ?? c.name)}</li>
              ))}
            </ul>
          </div>
        </div>
      )}
      {ended && run.end_reason === 'lost' && (
        <p className="ctl-err">연결 끊김: 연결 프로그램의 보고가 끊겨 실행이 어디까지 했는지 모른다. 작업 트리를 확인한 뒤 이어서 맡긴다.</p>
      )}
      {ended && lastError && run.end_reason !== 'submitted' && failedChecks.length === 0 && <p className="ctl-err">{summary(lastError)}</p>}
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
      {readiness && readiness.checks.length > 0 && (
        <details className="hr-inst">
          <summary>시작 전 준비 검사 {readiness.ok ? '통과' : '미달'}</summary>
          <ul className="hr-checks">
            {readiness.checks.map((c) => (
              <li key={c.name}>
                <strong>{CHECK_LABELS[c.name] ?? c.name}</strong> {c.ok ? '통과' : '미달'}
                {c.detail ? `, ${c.detail}` : ''}
              </li>
            ))}
          </ul>
        </details>
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

/**
 * Stopping the run and sending its executor an instruction. Both stay visible; a locked one shows
 * the server's reason next to it.
 */
function RunControls({
  run,
  onCancel,
  onSendInput,
}: {
  run: ItemRun;
  onCancel: (runId: string) => Promise<void>;
  onSendInput: (runId: string, text: string) => Promise<void>;
}) {
  const cancelWhy = useId();
  const inputWhy = useId();
  const inputId = useId();
  const [confirming, setConfirming] = useState(false);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const actions = run.actions!;
  const cancel = actions.cancel;
  const input = actions.send_input;
  return (
    <div className="hr-ctl">
      {confirming && cancel.allowed ? (
        <div className="hr-wait">
          <p className="dt-none">실행기와 그 하위 프로세스를 멈춘다. 작업 트리와 브랜치는 그대로 남는다.</p>
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => {
              setConfirming(false);
              void onCancel(run.id);
            }}
          >
            실행 멈추기
          </button>
          <button type="button" className="btn" onClick={() => setConfirming(false)}>
            그만두기
          </button>
        </div>
      ) : (
        <div className="hr-wait">
          <button
            type="button"
            className="btn"
            disabled={!cancel.allowed}
            aria-describedby={cancel.allowed ? undefined : cancelWhy}
            onClick={() => setConfirming(true)}
          >
            실행 취소
          </button>
          {!cancel.allowed && (
            <p className="wd-why" id={cancelWhy}>
              <Icon name="lock" />
              <span>{cancel.reason ?? '이유를 받지 못했다'}</span>
            </p>
          )}
        </div>
      )}
      <form
        className="hr-say"
        onSubmit={(e) => {
          e.preventDefault();
          const t = text.trim();
          if (!input.allowed || !t || busy) return;
          setBusy(true);
          onSendInput(run.id, t)
            .then(() => {
              setText('');
              setProblem(null);
            })
            .catch((err: unknown) => setProblem(err instanceof Error ? err.message : String(err)))
            .finally(() => setBusy(false));
        }}
      >
        <label htmlFor={inputId}>실행 중인 AI에게 지시</label>
        <textarea
          id={inputId}
          className="field"
          rows={2}
          maxLength={2000}
          value={text}
          disabled={!input.allowed}
          aria-describedby={input.allowed ? undefined : inputWhy}
          onChange={(e) => setText(e.target.value)}
          placeholder={input.allowed ? '예: 테스트 파일은 건드리지 않는다. 진행 중인 도구가 끝난 뒤에 받는다' : undefined}
        />
        <div className="wd-acts">
          <button type="submit" className="btn" disabled={!input.allowed || busy || !text.trim()} aria-busy={busy || undefined}>
            지시 보내기
          </button>
        </div>
        {!input.allowed && (
          <p className="wd-why" id={inputWhy}>
            <Icon name="lock" />
            <span>{input.reason ?? '이유를 받지 못했다'}</span>
          </p>
        )}
        {problem && <p className="ctl-err">{problem}</p>}
      </form>
    </div>
  );
}
