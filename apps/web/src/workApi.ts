// Server API for work items, projects, the workflow setting and decision records (contract v1
// "작업"; FN-0201, FN-0604), and handing a work item to an AI (contract "위임"). Same origin as
// the page. The server's own refusals carry a reason in `message`; it is shown as it is, because
// it is written for a person.

import type { Run, RunEvent } from './api';

export type Stage = 'draft' | 'plan_review' | 'ready' | 'in_progress' | 'result_review' | 'done' | 'cancelled';

export type ProjectRef = { id: string; external_ref: string | null; name: string };

export type WorkItem = {
  id: string;
  title: string;
  stage: Stage;
  wait_reasons: string[];
  generation: number;
  created_at: string;
  version: number;
  external_ref: string | null;
  project: ProjectRef | null;
  source_of_truth: 'product' | 'git';
  executor_kind: string | null;
  next_action: string | null;
  wait_note: string | null;
  opened_on: string | null;
  due_on: string | null;
  closed_at: string | null;
  updated_at: string;
};

export type Move = { target: Stage; path: Stage[]; allowed: boolean; reason: string | null; needs_note: boolean };

export type HistoryEntry = {
  action: string;
  actor_kind: string;
  actor_id: string;
  details: Record<string, unknown>;
  at: string;
};

export type WorkItemDetail = {
  item: WorkItem;
  responsible_user_id: string;
  executor_id: string | null;
  acceptance: string | null;
  source_ref: string | null;
  git_ref: { repo_key?: string; path?: string; commit?: string } | null;
  live_run: boolean;
  read_only_reason: string | null;
  moves: Move[];
  history: HistoryEntry[];
  /** Whether the work item can be handed to an AI now, and why not (third stage-1 slice). */
  delegation?: { allowed: boolean; reason: string | null };
};

/** A repository a device's connector can run in (`zm-baton repo add`). Never a path on the device. */
export type Repository = {
  device_id: string;
  device_name: string;
  repo_key: string;
  name: string;
  remote: string | null;
  updated_at: string;
};

export type RunDelegation = {
  id: string;
  device_id: string | null;
  device_name: string | null;
  repo_key: string | null;
  repo_name: string | null;
  base_branch: string | null;
  allowed_tools: string[];
  expires_at: string;
  revoked_at: string | null;
};

/** A run's own worktree (contract "실행마다 별도 작업 트리" 8): no path on the device. */
export type RunWorkspace = { repo_key: string; base_commit: string; branch: string; base_ref?: string };

/** The newest Git change list of a run (contract "보고 한 건의 모양" git_changes). */
export type GitChanges = { files: string[]; base_overlap?: string[]; base_branch_overlap?: string[]; truncated?: boolean };

/** A run as the work item's run list has it. A compat-test run has no delegation. */
export type ItemRun = Run & {
  claimed_at: string | null;
  started_at: string | null;
  ended_at: string | null;
  last_activity_at: string | null;
  delegation: RunDelegation | null;
  workspace: RunWorkspace | null;
  git_changes: GitChanges | null;
  instruction: string | null;
};

/** What a person picks when handing a work item to Claude Code. */
export type HandoffChoice = { device_id: string; repo_key: string; base_branch?: string; allowed_tools: string[] };

export type Project = ProjectRef & { open_work_items: number };

/** `ref` is the number people call it: `DEC-0106` copied from zm-docs, `DCS-0001` made here. */
export type DecisionRef = { id: string; external_ref: string | null; question: string; ref: string | null };

export type Decision = {
  id: string;
  external_ref: string | null;
  question: string;
  status: 'proposed' | 'accepted' | 'rejected' | 'superseded';
  decided_on: string | null;
  projects: ProjectRef[];
  choice_preview: string | null;
  supersedes: DecisionRef | null;
  superseded_by: DecisionRef | null;
  updated_at: string;
  number: number | null;
  ref: string | null;
  source_of_truth: 'product' | 'git';
  version: number;
};

export type DecisionOption = { key: string; title: string; body: string | null };

/** Decision roles (DACI). In stage 1 the approver is always the owner. */
export type DecisionRoles = { driver: string | null; approver: string | null; contributors: string[]; informed: string[] };

export type DecisionActionName = 'edit' | 'accept' | 'reject' | 'supersede';

export type DecisionAction = { name: DecisionActionName; allowed: boolean; reason: string | null };

export type Approval = {
  id: string;
  decision: 'approved' | 'rejected' | 'revoked';
  approver_kind: 'human' | 'ai_reviewer';
  approver_id: string;
  target_version: number;
  policy_ref: string;
  note: string | null;
  decided_at: string;
};

export type LinkedWork = Pick<WorkItem, 'id' | 'external_ref' | 'title' | 'stage' | 'wait_reasons'>;

export type DecisionDetail = {
  decision: Decision;
  choice: string | null;
  sections: { heading: string; body: string }[];
  source_of_truth: 'product' | 'git';
  git_ref: { repo_key?: string; path?: string; commit?: string } | null;
  /** The fields below are a decision made here; a Git copy has its text in `sections`. */
  context: string | null;
  options: DecisionOption[];
  chosen_option: string | null;
  rationale: string | null;
  review_conditions: string | null;
  roles: DecisionRoles;
  /** Who may accept or reject it now. */
  approver: string;
  work_items: LinkedWork[];
  approvals: Approval[];
  read_only_reason: string | null;
  actions: DecisionAction[];
  history: HistoryEntry[];
};

export type NewWorkItem = {
  title: string;
  project_id?: string;
  next_action?: string;
  acceptance?: string;
  due_on?: string;
  initial_stage: 'draft' | 'ready';
};

/** A refusal with the server's reason, so a screen can show why. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export async function call<T>(method: string, path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const res = await fetch(path, {
    method,
    signal,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let reason = '';
    try {
      reason = ((await res.json()) as { message?: string }).message ?? '';
    } catch {
      // not JSON: the status says enough
    }
    throw new ApiError(reason ? `HTTP ${res.status}: ${reason}` : `${method} ${path}: HTTP ${res.status}`, res.status);
  }
  return (await res.json()) as T;
}

const enc = encodeURIComponent;

function query(params: Record<string, string | number | boolean | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') q.set(k, String(v));
  return q.toString();
}

export const workApi = {
  workItems: (f: { projectId?: string; open: boolean; q?: string; limit?: number }, signal?: AbortSignal) =>
    call<WorkItem[]>(
      'GET',
      `/api/work-items?${query({ limit: f.limit ?? 500, project_id: f.projectId, open: f.open, q: f.q })}`,
      undefined,
      signal,
    ),
  detail: (id: string, signal?: AbortSignal) => call<WorkItemDetail>('GET', `/api/work-items/${enc(id)}`, undefined, signal),
  create: (body: NewWorkItem) =>
    call<{ id: string; stage: Stage }>('POST', '/api/work-items', {
      ...body,
      responsible_user_id: 'usr_owner',
      executor_kind: 'human',
    }),
  update: (id: string, version: number, fields: Record<string, string | null>) =>
    call<WorkItemDetail>('PATCH', `/api/work-items/${enc(id)}`, { version, ...fields }),
  move: (id: string, version: number, to: Stage, note?: string) =>
    call<WorkItemDetail>('POST', `/api/work-items/${enc(id)}/transitions`, { to, version, note }),
  addWait: (id: string, version: number, reason: string, note?: string) =>
    call<WorkItemDetail>('POST', `/api/work-items/${enc(id)}/wait-reasons`, { reason, version, note }),
  removeWait: (id: string, version: number, reason: string) =>
    call<WorkItemDetail>('DELETE', `/api/work-items/${enc(id)}/wait-reasons/${enc(reason)}?version=${version}`),
  projects: (signal?: AbortSignal) => call<Project[]>('GET', '/api/projects', undefined, signal),
  workflow: (signal?: AbortSignal) => call<{ skipped_stages: Stage[] }>('GET', '/api/settings/workflow', undefined, signal),
  setWorkflow: (skipped: Stage[]) => call<{ skipped_stages: Stage[] }>('PUT', '/api/settings/workflow', { skipped_stages: skipped }),
  decisions: (f: { projectId?: string; status?: string; q?: string }, signal?: AbortSignal) =>
    call<Decision[]>('GET', `/api/decisions?${query({ limit: 500, project_id: f.projectId, status: f.status, q: f.q })}`, undefined, signal),
  decision: (id: string, signal?: AbortSignal) => call<DecisionDetail>('GET', `/api/decisions/${enc(id)}`, undefined, signal),
  repositories: (signal?: AbortSignal) => call<Repository[]>('GET', '/api/repositories', undefined, signal),
  runs: (id: string, signal?: AbortSignal) => call<ItemRun[]>('GET', `/api/work-items/${enc(id)}/runs?limit=20`, undefined, signal),
  events: (runId: string, afterSeq: number, signal?: AbortSignal) =>
    call<RunEvent[]>('GET', `/api/runs/${enc(runId)}/events?after_seq=${afterSeq}&limit=200`, undefined, signal),
  delegate: (id: string, version: number, choice: HandoffChoice) =>
    call<WorkItemDetail>('POST', `/api/work-items/${enc(id)}/delegations`, { executor: 'claude_code', ...choice, version }),
  withdraw: (runId: string, version: number) => call<WorkItemDetail>('POST', `/api/runs/${enc(runId)}/withdraw`, { version }),
};
