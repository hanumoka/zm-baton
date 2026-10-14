import { causeLabel, count, duration, METHOD_LABELS, usd } from '../accounts/accountView';
import type { ItemRun } from '../workApi';

/*
 * What a run's record says about its account, limits, usage and billing, in words (FN-0409, FN-0501,
 * billing-signals 「화면에 보여 줄 문구」). Plain functions; the server decided every value.
 */

const LOCATIONS: Record<string, string> = {
  subscription_limit: '구독 한도',
  extra_usage: '추가 사용',
  separate_credit: '별도 크레딧',
  api: 'API 과금',
  unknown: '알 수 없음',
};

const WINDOWS: Record<string, string> = { five_hour: '5시간', seven_day: '7일' };

/** 「구독 로그인」 for the method the executor used, or nothing when the run does not say. */
export const methodLabel = (m: string | null | undefined) => (m ? (METHOD_LABELS[m] ?? m) : '');

/** 「턴 200, 비용 $30.00(오늘 남은 만큼), 60분」, or null when the run had no limits. */
export function limitsLine(run: Pick<ItemRun, 'limits'>): string | null {
  const l = run.limits;
  if (!l) return null;
  const parts: string[] = [];
  if (l.max_turns) parts.push(`턴 ${l.max_turns}`);
  if (l.max_budget_usd) parts.push(`비용 ${usd(l.max_budget_usd)}${l.budget_from === 'daily' ? '(오늘 남은 만큼)' : ''}`);
  if (l.max_minutes) parts.push(`${l.max_minutes}분`);
  return parts.length > 0 ? parts.join(', ') : null;
}

/** 「가격표 환산 $0.02, 턴 3, 2분 3초」, or null before the executor's result came. */
export function usageLine(run: Pick<ItemRun, 'usage'>): string | null {
  const u = run.usage;
  if (!u) return null;
  return `가격표 환산 ${usd(u.cost_usd)}, 턴 ${count(u.num_turns)}, ${duration(u.duration_ms)}`;
}

/** 「구독 한도, 5시간 11%, 7일 32%」, 「API 과금」, 「알 수 없음, 확인 불가」 (billing-signals). */
export function billingLine(run: Pick<ItemRun, 'billing_location' | 'billing_detection' | 'billing_evidence'>): string | null {
  if (!run.billing_location) return null;
  const parts = [LOCATIONS[run.billing_location] ?? run.billing_location];
  for (const [name, w] of Object.entries(run.billing_evidence?.windows ?? {})) {
    if (typeof w?.utilization === 'number') parts.push(`${WINDOWS[name] ?? name} ${Math.round(w.utilization * 100)}%`);
  }
  if (run.billing_detection === 'unavailable') parts.push('확인 불가');
  if (run.billing_detection === 'detected') parts.push('추가 과금 감지');
  return parts.join(', ');
}

/** Why a limit or a signal stopped the run, and what the person can do now. */
export function stopLine(run: Pick<ItemRun, 'stopped_by' | 'limits'>): string | null {
  const cause = run.stopped_by;
  if (!cause) return null;
  const what = causeLabel(cause);
  switch (cause) {
    case 'extra_billing':
      return `${what}를 받아 멈췄다. 계속하면 추가 결제가 생길 수 있다. 공급자 쪽을 확인한 뒤 AI 계정 화면에서 「추가 과금 멈춤 풀기」를 누르고 이어서 맡긴다.`;
    case 'provider_limit':
      return `${what}에 닿아 멈췄다. 한도가 초기화된 뒤 이어서 맡기거나, 다른 계정으로 맡긴다.`;
    case 'daily_cost_limit':
    case 'usage_burst':
      return `계정의 ${what}에 닿아 멈췄다. AI 계정 화면에서 상한과 오늘 사용량을 보고, 상한을 올리거나 다른 계정으로 이어서 맡긴다.`;
    default:
      return `${what}에 닿아 멈췄다. 작업 트리를 확인한 뒤 이어서 맡기면 새 실행의 상한이 다시 시작된다. 상한은 AI 계정 화면에서 바꾼다.`;
  }
}
