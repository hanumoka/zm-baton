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
 * Work items per stage, in stage order, skipping empty closed stages. Inside a stage the work
 * changed most recently comes first, so what is moving is on top.
 */
export function groupByStage(items: WorkItem[], skipped: Stage[] = []): { stage: Stage; label: string; items: WorkItem[] }[] {
  return STAGES.map(({ stage, label }) => ({
    stage,
    label,
    items: items
      .filter((i) => i.stage === stage)
      .sort((a, b) => b.updated_at.localeCompare(a.updated_at) || (b.external_ref ?? '').localeCompare(a.external_ref ?? '')),
  })).filter((g) => g.items.length > 0 || !(['done', 'cancelled'].includes(g.stage) || skipped.includes(g.stage)));
}

/**
 * The button for a move from [from]: "완료(으)로 (결과 검토 건너뜀)" when it passes through a
 * skipped stage, and the two moves backwards by what they mean.
 */
export function moveLabel(move: Move, from: Stage): string {
  const end = stageLabel(move.path[move.path.length - 1]);
  const passed = move.path.slice(0, -1).map(stageLabel);
  if (move.target === 'cancelled') return '취소';
  if (move.target === 'plan_review' && (from === 'in_progress' || from === 'result_review')) {
    return passed.length > 0 ? `다시 계획 (${end}(으)로)` : '다시 계획';
  }
  if (move.target === 'in_progress' && from === 'result_review') return '돌려보내기 (수행으로)';
  return passed.length > 0 ? `${end}(으)로 (${passed.join(', ')} 건너뜀)` : `${end}(으)로`;
}

/** One line for an entry of a work item's history. */
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
      return `${stageLabel(String(d.from ?? ''))} → ${path.map(stageLabel).join(' → ')}${note}`;
    }
    case 'work_item.updated':
      return `고침: ${Object.keys(d).join(', ')}`;
    case 'work_item.wait_added':
      return `대기 사유 붙임: ${waitLabel(String(d.reason ?? ''))}${d.note ? ` — ${String(d.note)}` : ''}`;
    case 'work_item.wait_removed':
      return `대기 사유 뗌: ${waitLabel(String(d.reason ?? ''))}`;
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
