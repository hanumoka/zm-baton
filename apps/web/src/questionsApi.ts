// Server API for questions and renegotiation (FN-0210; contract v1 "질문과 과금 신호", question
// path): leaving a question on a work item, answering it, recording the document change it needed
// and taking it back. Same origin as the page.

import type { ReviewWorkItem } from './reviewsApi';
import { call, type WorkItemDetail } from './workApi';

export type QuestionKind = 'interpretation' | 'execution' | 'policy_exception' | 'fact' | 'approval';

export type QuestionState = 'open' | 'awaiting_answer' | 'answered' | 'awaiting_doc_change' | 'resolved';

export type Question = {
  id: string;
  work_item_id: string;
  run_id: string | null;
  origin: string;
  kind: QuestionKind;
  blocking: boolean;
  blocked_step: string | null;
  independent_steps: string | null;
  body: string;
  options: string[];
  asked_by_kind: 'person' | 'manager' | 'shared_ai';
  asked_by_id: string;
  routed_to_kind: string;
  routed_to_id: string;
  /** work_owner, document_owner or approver. */
  routed_role: string;
  due_at: string;
  overdue: boolean;
  remind_count: number;
  answer: string | null;
  answered_by_kind: string | null;
  answered_by_id: string | null;
  answered_at: string | null;
  needs_doc_change: boolean;
  doc_change_ref: string | null;
  decision_ref: string | null;
  approval_ref: string | null;
  evidence_refs: string[];
  delivery: 'live_input' | 'resume' | 'new_session' | null;
  delivered_at: string | null;
  delivery_run_id: string | null;
  state: QuestionState;
  close_reason: 'answered' | 'withdrawn' | 'work_cancelled' | null;
  created_at: string;
  inbox_item_id: string | null;
};

export type QuestionDetail = {
  question: Question;
  work_item: ReviewWorkItem;
  routing_history: { at?: string; event?: string; to?: string; role?: string; note?: string }[];
  can_answer: boolean;
  can_confirm_doc_change: boolean;
  can_withdraw: boolean;
};

export type AskBody = {
  body: string;
  kind: QuestionKind;
  blocking: boolean;
  blocked_step?: string;
  /** `run`: a person records what a run of this work item asked. */
  asked_by: 'person' | 'run';
  run_id?: string;
};

export type AnswerBody = {
  answer: string;
  needs_doc_change?: boolean;
  doc_change_ref?: string;
  decision_ref?: string;
  approval_ref?: string;
};

const enc = encodeURIComponent;

export const questionsApi = {
  list: (workItemId: string, signal?: AbortSignal) => call<Question[]>('GET', `/api/work-items/${enc(workItemId)}/questions`, undefined, signal),
  detail: (id: string, signal?: AbortSignal) => call<QuestionDetail>('GET', `/api/questions/${enc(id)}`, undefined, signal),
  /** Returns the work item: its version and wait reasons moved. */
  ask: (workItemId: string, version: number, body: AskBody) =>
    call<WorkItemDetail>('POST', `/api/work-items/${enc(workItemId)}/questions`, { ...body, version }),
  answer: (id: string, body: AnswerBody) => call<Question>('POST', `/api/questions/${enc(id)}/answer`, body),
  docChange: (id: string, ref: string) => call<Question>('POST', `/api/questions/${enc(id)}/doc-change`, { doc_change_ref: ref }),
  withdraw: (id: string, note?: string) => call<Question>('POST', `/api/questions/${enc(id)}/withdraw`, { note }),
};
