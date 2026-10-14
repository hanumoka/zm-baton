// Server API for picking what people must see (FN-0411; contract v1 "사람에게 보낼 것을 고른다"):
// a review request's detail and verdict, and the rules that pick them (the risk path list, the
// sample rate and cap, and the candidates people put forward). Same origin as the page.

import { call, type ProjectRef } from './workApi';

export type ReviewReason = 'risk_path' | 'extra_billing' | 'sample' | string;

export type ReviewState = 'open' | 'approved' | 'rejected' | 'superseded';

export type HumanReviewState = 'required' | 'sampled' | 'reviewed' | 'not_reviewed';

export type AutoCheckState = 'passed' | 'failed' | 'not_run';

export type ReviewWorkItem = { id: string; title: string; external_ref: string | null; stage: string; version: number; project: ProjectRef | null };

export type ReviewRun = {
  id: string;
  end_reason: string | null;
  ended_at: string | null;
  workspace: { repo_key?: string; base_commit?: string; branch?: string; base_ref?: string } | null;
  repo_name: string | null;
  device_name: string | null;
  files: string[];
  truncated: boolean;
  result_snapshot_sha: string | null;
  human_review_state: HumanReviewState | null;
  auto_check_state: AutoCheckState | null;
};

/** What approving and rejecting would do now, as the server says it. */
export type ReviewEffects = { approve: string; reject: string; reject_needs_version: boolean };

export type ReviewDetail = {
  id: string;
  reason: ReviewReason;
  raised_by: string;
  state: ReviewState;
  target_kind: 'run_snapshot' | 'session_file' | 'unpinned';
  target_ref: string;
  /** risk_path: `{list, matches: [{pattern, files}]}`; sample: `{draw, rate, daily_cap, seed}`. */
  evidence: Record<string, unknown>;
  created_at: string;
  decided_by: string | null;
  decided_at: string | null;
  note: string | null;
  superseded_by: string | null;
  proposal: { pattern: string; state: 'open' | 'added' | 'dismissed' } | null;
  work_item: ReviewWorkItem | null;
  run: ReviewRun | null;
  effects: ReviewEffects | null;
  suggested_pattern: string | null;
};

export type ReviewDecision = {
  decision: 'approved' | 'rejected';
  target_ref: string;
  note?: string;
  work_version?: number;
  propose_risk_path?: string;
};

export type ReviewOutcome = 'approved' | 'released' | 'returned' | 'fix_created' | 'recorded';

export type ReviewDecisionOut = { review: ReviewDetail; effect: ReviewOutcome; fix_work_item_id: string | null };

/** A review request of one run, as the run list has it. */
export type RunReview = {
  id: string;
  reason: ReviewReason;
  state: ReviewState;
  target_kind: string;
  decided_at: string | null;
  note: string | null;
  superseded_by: string | null;
  inbox_item_id: string | null;
};

export type RiskProposal = {
  review_id: string;
  project_id: string | null;
  pattern: string;
  work_item_id: string | null;
  work_title: string | null;
  note: string | null;
  proposed_at: string;
};

export type ProjectRiskScope = { project: ProjectRef; patterns: string[]; inherited: boolean; open_proposals: number };

export type ReviewSettings = {
  org: { patterns: string[]; customized: boolean };
  default_patterns: string[];
  sample_rate: number;
  sample_daily_cap: number;
  promote_after_hours: number;
  projects: ProjectRiskScope[];
  proposals: RiskProposal[];
};

const enc = encodeURIComponent;

export const reviewsApi = {
  detail: (id: string, signal?: AbortSignal) => call<ReviewDetail>('GET', `/api/reviews/${enc(id)}`, undefined, signal),
  decide: (id: string, body: ReviewDecision) => call<ReviewDecisionOut>('POST', `/api/reviews/${enc(id)}/decision`, body),
  settings: (signal?: AbortSignal) => call<ReviewSettings>('GET', '/api/review-settings', undefined, signal),
  setOrg: (body: { risk_paths?: string[]; sample_rate?: number; sample_daily_cap?: number }) =>
    call<ReviewSettings>('PUT', '/api/review-settings', body),
  /** `null` puts the project back on the organisation's list. */
  setProject: (projectId: string, patterns: string[] | null) =>
    call<ReviewSettings>('PUT', `/api/projects/${enc(projectId)}/risk-paths`, { patterns }),
  proposal: (reviewId: string, action: 'add' | 'dismiss') => call<ReviewSettings>('POST', `/api/reviews/${enc(reviewId)}/proposal`, { action }),
};
