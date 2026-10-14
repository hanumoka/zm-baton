// Server API for writing decisions made in zm-baton (FN-0604): propose, change while proposed,
// accept or reject, and the decisions linked to one work item. Reading the list and one decision
// is in workApi.ts with the other reads. The server's refusals carry a reason in `message`.

import { call, type Decision, type DecisionDetail } from './workApi';

/** What a person writes in a decision; on a change only the fields present are changed. */
export type DecisionFields = {
  question?: string;
  context?: string | null;
  options?: { key: string; title: string; body: string | null }[];
  chosen_option?: string | null;
  choice?: string | null;
  rationale?: string | null;
  review_conditions?: string | null;
  roles?: { driver: string | null; contributors: string[]; informed: string[] };
  project_ids?: string[];
  work_item_ids?: string[];
  supersedes_id?: string | null;
};

/** `approved` accepts a proposal, `rejected` rejects it (data model "approvals"). */
export type Verdict = 'approved' | 'rejected';

const enc = encodeURIComponent;

export const decisionsApi = {
  propose: (fields: DecisionFields) => call<DecisionDetail>('POST', '/api/decisions', fields),
  update: (id: string, version: number, fields: DecisionFields) =>
    call<DecisionDetail>('PATCH', `/api/decisions/${enc(id)}`, { version, ...fields }),
  judge: (id: string, version: number, decision: Verdict, note?: string) =>
    call<DecisionDetail>('POST', `/api/decisions/${enc(id)}/approvals`, { decision, version, note }),
  forWork: (workItemId: string, signal?: AbortSignal) =>
    call<Decision[]>('GET', `/api/work-items/${enc(workItemId)}/decisions`, undefined, signal),
};
