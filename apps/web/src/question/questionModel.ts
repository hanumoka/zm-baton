import type { AnswerBody, AskBody, Question, QuestionKind } from '../questionsApi';

/*
 * Words for questions (FN-0210; question path), as plain functions: the five kinds, who asked and
 * who answers, where a question stands, and what a form sends. The server routes and decides; these
 * only put it into words.
 */

export const KINDS: { kind: QuestionKind; label: string; to: string }[] = [
  { kind: 'interpretation', label: '해석', to: '원문 책임자(이어진 문서의 소유자, 없으면 작업 책임자)' },
  { kind: 'execution', label: '실행 문제', to: '작업 책임자' },
  { kind: 'fact', label: '사실 확인', to: '작업 책임자' },
  { kind: 'policy_exception', label: '정책 예외', to: '승인자' },
  { kind: 'approval', label: '승인 요청', to: '승인자' },
];

export const kindLabel = (kind: string) => KINDS.find((k) => k.kind === kind)?.label ?? kind;

const ROLES: Record<string, string> = { work_owner: '작업 책임자', document_owner: '문서 소유자', approver: '승인자' };

export const roleLabel = (role: string) => ROLES[role] ?? role;

/** Who asked: a person, or the AI run whose question a person recorded. */
export function askerLabel(q: Pick<Question, 'asked_by_kind' | 'asked_by_id' | 'run_id'>): string {
  if (q.asked_by_kind === 'person') return q.asked_by_id;
  return q.run_id ? 'AI 실행' : 'AI';
}

/** Where a question stands, in the question path's words. */
export function stateLabel(q: Pick<Question, 'state' | 'close_reason' | 'run_id'>): string {
  switch (q.state) {
    case 'open':
    case 'awaiting_answer':
      return '답변 대기';
    case 'answered':
      return q.run_id ? '답함, 다음 실행에 넣기 전' : '답함';
    case 'awaiting_doc_change':
      return '답함, 문서 반영 대기';
    case 'resolved':
      if (q.close_reason === 'withdrawn') return '거둠';
      if (q.close_reason === 'work_cancelled') return '작업 취소로 닫힘';
      return '해결';
  }
}

/** How the answer reached the executor. */
export function deliveryLabel(q: Pick<Question, 'delivery'>): string | null {
  switch (q.delivery) {
    case 'resume':
      return '같은 세션을 이어 답을 넣었다';
    case 'new_session':
      return '새 세션의 지시에 답을 넣었다';
    case 'live_input':
      return '실행 중에 답을 넣었다';
    default:
      return null;
  }
}

export const isWaiting = (q: Pick<Question, 'state'>) => q.state === 'open' || q.state === 'awaiting_answer';

/** What a question form sends; null without words, which the server refuses. */
export function askBody(text: string, kind: QuestionKind, blocking: boolean, runId?: string): AskBody | null {
  const body = text.trim();
  if (body === '') return null;
  return runId ? { body, kind, blocking, asked_by: 'run', run_id: runId } : { body, kind, blocking, asked_by: 'person' };
}

/** What an answer form sends; null without an answer. Record numbers are sent only when written. */
export function answerBody(text: string, needsDocChange: boolean, refs: { doc?: string; decision?: string; approval?: string } = {}): AnswerBody | null {
  const answer = text.trim();
  if (answer === '') return null;
  const body: AnswerBody = { answer };
  if (needsDocChange) body.needs_doc_change = true;
  if (refs.doc?.trim()) body.doc_change_ref = refs.doc.trim();
  if (refs.decision?.trim()) body.decision_ref = refs.decision.trim();
  if (refs.approval?.trim()) body.approval_ref = refs.approval.trim();
  return body;
}

/** The last thing a run said, to start its question from; the person edits it. */
export function lastAnswer(events: { kind: string; payload: Record<string, unknown> }[]): string {
  const e = [...events].reverse().find((x) => x.kind === 'answer' && typeof x.payload.text === 'string');
  return e ? String(e.payload.text).slice(0, 1000) : '';
}
