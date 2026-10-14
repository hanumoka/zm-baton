import type { Decision, HistoryEntry, Move, Stage, WorkItem } from './workApi';

/** Stages in the contract's order (contract v1 "단계"). The server holds the rules; this only shows them. */
export const STAGES: { stage: Stage; label: string }[] = [
  { stage: 'draft', label: '초안' },
  { stage: 'plan_review', label: '계획 검토' },
  { stage: 'ready', label: '수행 준비' },
  { stage: 'in_progress', label: '수행' },
  { stage: 'result_review', label: '결과 검토' },
  { stage: 'done', label: '완료' },
  { stage: 'cancelled', label: '취소' },
];

export const SKIPPABLE: Stage[] = ['plan_review', 'result_review'];

const WAIT_LABELS: Record<string, string> = {
  waiting_answer: '질문 답 대기',
  waiting_approval: '승인 대기',
  waiting_predecessor: '선행 작업 대기',
  waiting_device: '기기 연결 대기',
  waiting_account: '쓸 수 있는 AI 계정 없음',
  waiting_limit_reset: '한도 리셋 대기',
  billing_stopped: '추가 과금으로 멈춤',
  outcome_unknown: '결과 확인 필요',
  cancelling: '취소 처리 중',
  readiness_failed: '시작 전 준비 미달',
  waiting_other: '다른 사정으로 막힘',
};

const DECISION_STATUS: Record<Decision['status'], string> = {
  proposed: '제안',
  accepted: '채택',
  rejected: '기각',
  superseded: '대체됨',
};

export function stageLabel(stage: string): string {
  return STAGES.find((s) => s.stage === stage)?.label ?? stage;
}

export function waitLabel(reason: string): string {
  return WAIT_LABELS[reason] ?? reason;
}

export function decisionStatusLabel(status: Decision['status']): string {
  return DECISION_STATUS[status] ?? status;
}

/**
 * 「완료로」, 「수행으로」: the particle 로/으로 follows the word's last syllable (no final consonant or
 * ㄹ takes 로), so a stage an organisation renames still reads right. Not Korean: 「(으)로」.
 */
export function toward(word: string): string {
  const code = word.charCodeAt(word.length - 1);
  if (Number.isNaN(code) || code < 0xac00 || code > 0xd7a3) return `${word}(으)로`;
  const last = (code - 0xac00) % 28;
  return last === 0 || last === 8 ? `${word}로` : `${word}으로`;
}

export type MoveParts = {
  /** What the button does, 「완료로 옮기기」. */
  label: string;
  /** What else happens on the way, 「결과 검토 건너뜀」. */
  note?: string;
};

/**
 * The button for a move from [from]: where it ends, the stages it passes through because the
 * organisation skips them, and the two moves backwards by what they mean.
 */
export function moveParts(move: Move, from: Stage): MoveParts {
  const end = stageLabel(move.path[move.path.length - 1]);
  const passed = move.path.slice(0, -1).map(stageLabel);
  if (move.target === 'cancelled') return { label: '취소하기' };
  if (move.target === 'plan_review' && (from === 'in_progress' || from === 'result_review')) {
    return { label: '다시 계획하기', note: passed.length > 0 ? `${passed.join(', ')} 건너뛰고 ${toward(end)}` : undefined };
  }
  if (move.target === 'in_progress' && from === 'result_review') return { label: '돌려보내기', note: '수행으로' };
  return { label: `${toward(end)} 옮기기`, note: passed.length > 0 ? `${passed.join(', ')} 건너뜀` : undefined };
}

/** The same in one line: 「완료로 옮기기 (결과 검토 건너뜀)」. */
export function moveLabel(move: Move, from: Stage): string {
  const { label, note } = moveParts(move, from);
  return note ? `${label} (${note})` : label;
}

/** One line for an entry of a work item's history. */
const REVIEW_REASONS: Record<string, string> = { risk_path: '위험 경로', extra_billing: '추가 과금', sample: '표본' };

export function historyLine(h: HistoryEntry): string {
  const d = h.details;
  switch (h.action) {
    case 'work_item.created':
      return `만듦 (${stageLabel(String(d.stage ?? ''))})`;
    case 'work_item.imported':
      return `가져옴 (${String(d.ref ?? '')})`;
    case 'work_item.synced':
      return `원본에서 다시 가져옴: ${(d.fields as string[] | undefined)?.join(', ') ?? ''}`;
    case 'work_item.stage_changed': {
      const path = (d.path as string[] | undefined) ?? [String(d.to ?? '')];
      const note = d.note ? ` — ${String(d.note)}` : '';
      // A delegated run moves its work item itself: started, then submitted.
      const by = h.actor_kind === 'system' ? ' (AI 실행이 옮김)' : '';
      return `${stageLabel(String(d.from ?? ''))} → ${path.map(stageLabel).join(' → ')}${note}${by}`;
    }
    case 'work_item.delegated': {
      const tools = (d.allowed_tools as string[] | undefined)?.join(', ') ?? '';
      const what = d.resumed_from_run_id ? 'AI에게 이어서 맡김' : 'AI에게 맡김';
      const note = d.note ? ` — ${String(d.note)}` : '';
      return `${what} (도구 ${tools}${d.base_branch ? `, 기준 ${String(d.base_branch)}` : ''})${note}`;
    }
    case 'work_item.delegation_withdrawn':
      return '맡긴 일을 거둠';
    case 'work_item.run_cancel_requested':
      return '실행 취소를 요청함';
    case 'run.input_sent':
      return `실행 중인 AI에게 지시함 — ${String(d.text ?? '')}`;
    case 'run.lost':
      return '연결 프로그램의 신호가 끊겨 서버가 실행을 「연결 끊김」으로 끝냄';
    case 'work_item.updated':
      return `고침: ${Object.keys(d).join(', ')}`;
    case 'work_item.wait_added':
      return `대기 사유 붙임: ${waitLabel(String(d.reason ?? ''))}${d.note ? ` — ${String(d.note)}` : ''}`;
    case 'work_item.wait_removed':
      return `대기 사유 뗌: ${waitLabel(String(d.reason ?? ''))}`;
    // Added with human review picking (FN-0411).
    case 'review.raised':
      return `결과를 사람 검토로 보냄: ${REVIEW_REASONS[String(d.reason ?? '')] ?? String(d.reason ?? '')}`;
    case 'review.approved':
      return `사람 검토 승인${d.effect === 'released' ? ', 완료로 넘어감' : ''}${d.note ? ` — ${String(d.note)}` : ''}`;
    case 'review.rejected':
      return `사람 검토 반려${d.effect === 'fix_created' ? ', 고침 작업을 만듦' : d.effect === 'recorded' ? ', 기록만 함' : ''}${d.note ? ` — ${String(d.note)}` : ''}`;
    case 'work_item.fixes':
      return '표본 반려로 만든 고침 작업';
    default:
      return h.action;
  }
}

/** `TSK-021 · 제목` or just the title for work made here. */
export function workTitle(item: Pick<WorkItem, 'external_ref' | 'title'>): string {
  return item.external_ref ? `${item.external_ref} · ${item.title}` : item.title;
}

/** `2026-10-13 14:05` in the viewer's time zone. */
export function at(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
