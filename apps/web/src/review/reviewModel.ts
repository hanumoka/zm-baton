import type { AutoCheckState, HumanReviewState, ReviewDetail, ReviewOutcome, RunReview } from '../reviewsApi';

/*
 * Words for picking what people must see (contract v1 "사람에게 보낼 것을 고른다"), as plain
 * functions: why a result came to a person, its human review and automatic check states shown
 * apart (contract: 「사람 미확인 · 자동 검사 안 함」), and what a verdict did. The server decides
 * all of it; these only put it into words.
 */

const REASONS: Record<string, string> = {
  risk_path: '위험 경로',
  extra_billing: '추가 과금',
  sample: '표본',
  irreversible: '되돌릴 수 없는 동작',
  conflict: '충돌',
  ai_raised: 'AI가 올림',
};

export const reasonLabel = (reason: string) => REASONS[reason] ?? reason;

/** The run list's line: who has looked at the result, or that nobody has. */
export function humanLabel(state: HumanReviewState | null | undefined, reviews: RunReview[]): string | null {
  switch (state) {
    case 'required':
      return '기다림(위험 목록)';
    case 'sampled':
      return '기다림(표본)';
    case 'reviewed':
      return reviews.some((r) => r.state === 'rejected') ? '반려' : '승인';
    case 'not_reviewed':
      return '사람 미확인';
    default:
      return null;
  }
}

/** The automatic check apart from the person: no result passes just because nobody looked. */
export function autoLabel(state: AutoCheckState | null | undefined): string | null {
  switch (state) {
    case 'passed':
      return '자동 검사 통과';
    case 'failed':
      return '자동 검사 실패';
    case 'not_run':
      return '자동 검사 안 함';
    default:
      return null;
  }
}

/** Why a 「사람 미확인」 result was not sent, from the server's basis. */
export function notSentWhy(basis: Record<string, unknown> | null | undefined): string | null {
  const sample = (basis?.sample ?? null) as Record<string, unknown> | null;
  if (!sample) return null;
  if (sample.skipped === 'unpinned') return '검토 대상을 고정하지 못해 표본에서 뺐다';
  if (sample.capped === true) return '오늘 표본 상한에 닿아 고르지 않았다';
  const draw = typeof sample.draw === 'number' ? sample.draw : null;
  const rate = typeof sample.rate === 'number' ? sample.rate : null;
  if (rate !== null && rate <= 0) return '위험 경로에 걸리지 않았고 표본 비율이 0이라 뽑지 않았다';
  if (draw !== null && rate !== null && draw >= rate) return `위험 경로에 걸리지 않았고 표본으로 뽑히지 않았다(뽑은 수 ${draw.toFixed(3)}, 비율 ${rateLabel(rate)})`;
  return null;
}

/** 0.1 → 「10건 중 1건」, 0.25 → 「25%」. */
export function rateLabel(rate: number): string {
  if (rate <= 0) return '표본 없음';
  if (rate >= 1) return '모두';
  const inverse = 1 / rate;
  if (Math.abs(inverse - Math.round(inverse)) < 1e-9) return `${Math.round(inverse)}건 중 1건`;
  return `${Math.round(rate * 1000) / 10}%`;
}

export type Match = { pattern: string; files: string[] };

/** The patterns that caught the result, with their files. */
export function matches(r: Pick<ReviewDetail, 'evidence'>): Match[] {
  const raw = r.evidence.matches;
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((m): Match[] => {
    if (typeof m !== 'object' || m === null) return [];
    const o = m as Record<string, unknown>;
    return typeof o.pattern === 'string' && Array.isArray(o.files) ? [{ pattern: o.pattern, files: o.files.filter((f): f is string => typeof f === 'string') }] : [];
  });
}

/** The detail's title: what is asked of me. */
export function reviewTitle(r: Pick<ReviewDetail, 'reason'>): string {
  switch (r.reason) {
    case 'risk_path':
      return 'AI가 위험 경로를 고친 결과를 본다';
    case 'extra_billing':
      return '추가 과금으로 멈춘 실행의 결과를 본다';
    case 'sample':
      return 'AI가 통과시킨 결과를 표본으로 본다';
    default:
      return 'AI 실행의 결과를 본다';
  }
}

/** Why it came to me, in one or two sentences. */
export function reviewWhy(r: Pick<ReviewDetail, 'reason' | 'evidence'>): string {
  if (r.reason === 'risk_path') {
    const list = r.evidence.list === 'project' ? '이 프로젝트의 위험 목록' : '조직의 위험 목록';
    return `바뀐 파일이 ${list}에 걸렸다. 정책·권한·보안에 닿는 변경은 사람이 승인한 뒤에 합친다. 승인할 때까지 작업은 결과 검토에 머문다.`;
  }
  if (r.reason === 'extra_billing') return '이 실행은 추가 과금이 감지돼 멈춘 작업에서 나왔다. 돈이 더 나가는 결과는 사람이 본다.';
  if (r.reason === 'sample') {
    const rate = typeof r.evidence.rate === 'number' ? r.evidence.rate : null;
    const picked = rate !== null && rate >= 1 ? '위험 목록에 걸리지 않은 결과를 모두 골라' : `위험 목록에 걸리지 않은 결과 가운데 ${rate === null ? '정한' : rateLabel(rate)} 비율로 골라`;
    return `${picked} 사람이 본다. AI가 놓친 것이 나오면 그 경로를 위험 목록 후보로 올린다. 작업은 막지 않는다.`;
  }
  return '규칙이 사람에게 보낸 결과다.';
}

/** What a verdict did, for the line the first screen shows after it. */
export function outcomeText(effect: ReviewOutcome): string {
  switch (effect) {
    case 'released':
      return '승인했다. 작업이 완료로 넘어갔다';
    case 'approved':
      return '승인했다';
    case 'returned':
      return '반려했다. 작업이 수행으로 돌아갔다. 작업 상세에서 「이어서 맡기기」로 고치게 한다';
    case 'fix_created':
      return '반려했다. 반려 이유를 수락 조건으로 한 고침 작업을 새로 만들었다';
    case 'recorded':
      return '반려를 기록했다. 작업 단계는 그대로다';
  }
}

/** The verdict a form sends; null when a rejection has no reason yet (the server would refuse it). */
export function decisionBody(
  r: Pick<ReviewDetail, 'target_ref' | 'work_item' | 'effects'>,
  decision: 'approved' | 'rejected',
  reason: string,
  proposal: string,
): { decision: 'approved' | 'rejected'; target_ref: string; note?: string; work_version?: number; propose_risk_path?: string } | null {
  const note = reason.trim();
  if (decision === 'approved') return { decision, target_ref: r.target_ref, ...(note ? { note } : {}) };
  if (note === '') return null;
  const body: { decision: 'rejected'; target_ref: string; note: string; work_version?: number; propose_risk_path?: string } = {
    decision,
    target_ref: r.target_ref,
    note,
  };
  if (r.work_item) body.work_version = r.work_item.version;
  const p = proposal.trim();
  if (p) body.propose_risk_path = p;
  return body;
}

/** One pattern per line, blanks dropped, for the list editor. */
export const parsePatterns = (text: string) => text.split(/\r?\n/).map((l) => l.trim()).filter((l) => l !== '');

export const sameList = (a: string[], b: string[]) => a.length === b.length && a.every((v, i) => v === b[i]);
