import type { Account, AccountFields, AccountLimits, Affiliation, AuthKind, UsageTotals } from '../accountsApi';
import type { IconName } from '../icons/Icon';

/*
 * What the AI account screen shows, as plain functions: the groups, the words for kinds, sign-in
 * places, limits and usage, the address that keeps the selection, which empty state applies and the
 * form's data. The server holds the accounts and decides whether one can take work; this only
 * arranges what it returned. Costs are the executor's price-list estimate, never a bill.
 */

export const KIND_LABELS: Record<AuthKind, string> = {
  personal_subscription: '개인 구독',
  org_account: '조직 계정',
  api_key: 'API 키',
};

/** What each kind means, under the radio button. */
export const KIND_NOTES: Record<AuthKind, string> = {
  personal_subscription: '공식 CLI에 내가 직접 로그인한 구독. 내 작업에만 쓴다',
  org_account: '조직이 낸 구독에 그 PC에서 로그인한 계정',
  api_key: '쓴 만큼 과금되는 키. 키 값은 여기 적지 않고 그 PC의 환경 변수 ANTHROPIC_API_KEY에 둔다',
};

export const METHOD_LABELS: Record<string, string> = {
  subscription_login: '구독 로그인',
  api_key: 'API 키',
  cloud_account: '클라우드 계정',
};

export const SOURCE_LABELS: Record<string, string> = {
  device_login: '그 PC의 Claude Code 로그인',
  'env:ANTHROPIC_API_KEY': '그 PC의 환경 변수 ANTHROPIC_API_KEY',
};

export const AFFILIATION_LABELS: Record<Affiliation, string> = { personal: '개인', organization: '조직' };

/** The limit or signal that stopped a run or an account, in words (server UsageService). */
export const CAUSE_LABELS: Record<string, string> = {
  run_turn_limit: '실행 상한(턴)',
  run_cost_limit: '실행 상한(비용)',
  run_minute_limit: '실행 상한(시간)',
  daily_cost_limit: '하루 상한',
  usage_burst: '비정상 소비',
  provider_limit: '공급자 사용 한도',
  extra_billing: '추가 과금 신호',
  disabled: '비활성화',
};

export const causeLabel = (cause: string | null | undefined) => (cause ? (CAUSE_LABELS[cause] ?? cause) : '');

/** 「$1.25」: two places, the way the server keeps limits. */
export const usd = (n: number | null | undefined) => (n === null || n === undefined ? '없음' : `$${n.toFixed(2)}`);

/** 「12,345」. */
export const count = (n: number) => n.toLocaleString('ko-KR');

/** 「3분 12초」 from milliseconds; under a minute, seconds only. */
export function duration(ms: number): string {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}초`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}분 ${s % 60}초`;
  return `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

/** How much of the daily limit today's use is, in whole percent; null without a limit. */
export function dailyShare(a: Pick<Account, 'today' | 'limits'>): number | null {
  const cap = a.limits.daily_cost_limit_usd;
  if (!cap) return null;
  return Math.min(999, Math.round((a.today.cost_usd / cap) * 100));
}

/** 「오늘 $1.25, 하루 상한 $200.00의 1%」 or, without a limit, 「오늘 $1.25, 하루 상한 없음」. */
export function todayLine(a: Pick<Account, 'today' | 'limits'>): string {
  const share = dailyShare(a);
  const cap = a.limits.daily_cost_limit_usd;
  return share === null ? `오늘 ${usd(a.today.cost_usd)}, 하루 상한 없음` : `오늘 ${usd(a.today.cost_usd)}, 하루 상한 ${usd(cap)}의 ${share}%`;
}

/** The run limits in one line: 「턴 200, 비용 $30.00, 60분」; a missing one says 「없음」. */
export function runLimitsLine(l: AccountLimits): string {
  return [
    `턴 ${l.run_turn_limit ?? '없음'}`,
    `비용 ${usd(l.run_cost_limit_usd)}`,
    l.run_minute_limit === null ? '시간 없음' : `${l.run_minute_limit}분`,
  ].join(', ');
}

/** Tokens in one line: what went in (fresh, from cache, written to cache) and what came out. */
export function tokensLine(t: Pick<UsageTotals, 'input_tokens' | 'output_tokens' | 'cache_read_tokens' | 'cache_creation_tokens'>): string {
  return `입력 ${count(t.input_tokens)}, 캐시 읽기 ${count(t.cache_read_tokens)}, 캐시 쓰기 ${count(t.cache_creation_tokens)}, 출력 ${count(t.output_tokens)}`;
}

/** Accounts group by whether they can take work now (DESIGN.md 「화면 배치」 4: status for records no one holds). */
export type Bucket = 'ready' | 'stopped' | 'disabled';

export const BUCKETS: { bucket: Bucket; label: string; icon: IconName }[] = [
  { bucket: 'ready', label: '쓸 수 있음', icon: 'done' },
  { bucket: 'stopped', label: '멈춤', icon: 'wait' },
  { bucket: 'disabled', label: '비활성', icon: 'lock' },
];

export function bucketOf(a: Pick<Account, 'status' | 'block'>): Bucket {
  if (a.status === 'disabled') return 'disabled';
  return a.block ? 'stopped' : 'ready';
}

export type AccountGroup = { bucket: Bucket; label: string; icon: IconName; items: Account[] };

/** The three groups in order, each oldest first (the seeded login stays on top). Every group shows. */
export function groupAccounts(items: Account[]): AccountGroup[] {
  return BUCKETS.map(({ bucket, label, icon }) => ({
    bucket,
    label,
    icon,
    items: items.filter((a) => bucketOf(a) === bucket).sort((a, b) => a.created_at.localeCompare(b.created_at) || a.id.localeCompare(b.id)),
  }));
}

export const rowOrder = (groups: AccountGroup[]) => groups.flatMap((g) => g.items.map((a) => a.id));

export type ListState = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'unused' } | { kind: 'rows' };

export function listState(items: Account[] | null, error: string | null): ListState {
  if (items === null) return error === null ? { kind: 'loading' } : { kind: 'error', message: error };
  return items.length === 0 ? { kind: 'unused' } : { kind: 'rows' };
}

/** The selected account, kept in the address (`#accounts?item=acc_…`). */
export type AccountsView = { selected: string | null };

export function readView(hash: string): AccountsView {
  const at = hash.indexOf('?');
  const p = new URLSearchParams(at < 0 ? '' : hash.slice(at + 1));
  return { selected: p.get('item') || null };
}

export const writeView = (v: AccountsView) => (v.selected ? `#accounts?item=${encodeURIComponent(v.selected)}` : '#accounts');

/** What the form holds: text as typed, limits as typed (empty means no limit). */
export type AccountDraft = {
  name: string;
  authKind: AuthKind;
  affiliation: Affiliation;
  purpose: string;
  dataTerms: string;
  runTurn: string;
  runCost: string;
  runMinute: string;
  daily: string;
};

/** The defaults a new account starts with on the server (AccountService.DEFAULT_LIMITS). */
export const DEFAULT_DRAFT: AccountDraft = {
  name: '',
  authKind: 'personal_subscription',
  affiliation: 'personal',
  purpose: '',
  dataTerms: '',
  runTurn: '200',
  runCost: '30',
  runMinute: '60',
  daily: '200',
};

const text = (n: number | null) => (n === null ? '' : String(n));

export function draftFrom(a: Account): AccountDraft {
  return {
    name: a.name,
    authKind: a.auth_kind,
    affiliation: a.affiliation,
    purpose: a.purpose ?? '',
    dataTerms: a.data_terms ?? '',
    runTurn: text(a.limits.run_turn_limit),
    runCost: text(a.limits.run_cost_limit_usd),
    runMinute: text(a.limits.run_minute_limit),
    daily: text(a.limits.daily_cost_limit_usd),
  };
}

/** The affiliation a kind allows: a subscription is personal, an organisation's account is the organisation's. */
export function affiliationFor(kind: AuthKind, asked: Affiliation): Affiliation {
  if (kind === 'personal_subscription') return 'personal';
  if (kind === 'org_account') return 'organization';
  return asked;
}

/**
 * The shapes the server refuses as secrets (server SecretShapes), checked before sending so a key
 * never leaves this page. A hyphen breaks a long run and hexadecimal alone does not count.
 */
const SECRET_SHAPES = [/sk-ant-/i, /\bsk-[A-Za-z0-9_-]{16,}/, /\bAKIA[0-9A-Z]{16}\b/, /\bgh[pousr]_[A-Za-z0-9]{20,}/, /\bxox[abprs]-[A-Za-z0-9-]{10,}/, /-----BEGIN [A-Z ]*PRIVATE KEY-----/];

export function looksSecret(s: string): boolean {
  if (SECRET_SHAPES.some((r) => r.test(s))) return true;
  return (s.match(/[A-Za-z0-9_+/=]{32,}/g) ?? []).some((t) => /\d/.test(t) && /[A-Za-z]/.test(t) && !/^[0-9a-fA-F]+$/.test(t));
}

type Limit = { key: keyof AccountLimits; label: string; whole: boolean; max: number };

export const LIMITS: Limit[] = [
  { key: 'run_turn_limit', label: '실행마다 턴', whole: true, max: 10000 },
  { key: 'run_cost_limit_usd', label: '실행마다 비용(달러, 가격표 환산)', whole: false, max: 100000 },
  { key: 'run_minute_limit', label: '실행마다 시간(분)', whole: true, max: 1440 },
  { key: 'daily_cost_limit_usd', label: '계정의 하루 비용(달러, 가격표 환산)', whole: false, max: 100000 },
];

const draftKey: Record<keyof AccountLimits, keyof AccountDraft> = {
  run_turn_limit: 'runTurn',
  run_cost_limit_usd: 'runCost',
  run_minute_limit: 'runMinute',
  daily_cost_limit_usd: 'daily',
};

export const limitValue = (d: AccountDraft, key: keyof AccountLimits) => d[draftKey[key]];

export const withLimit = (d: AccountDraft, key: keyof AccountLimits, value: string): AccountDraft => ({ ...d, [draftKey[key]]: value });

/** A limit as the server takes it: empty is no limit (null). */
function limitOf(raw: string): number | null {
  const t = raw.trim();
  return t === '' ? null : Number(t);
}

/** Why the draft cannot be sent, or null. */
export function draftProblem(d: AccountDraft): string | null {
  if (d.name.trim() === '') return '이름을 쓴다';
  for (const field of [d.name, d.purpose, d.dataTerms]) {
    if (looksSecret(field)) return 'API 키나 토큰처럼 보이는 값이 있다. 키 값은 여기에 적지 않는다';
  }
  for (const l of LIMITS) {
    const raw = limitValue(d, l.key).trim();
    if (raw === '') continue;
    const n = Number(raw);
    if (!Number.isFinite(n) || n <= 0 || n > l.max || (l.whole && !Number.isInteger(n))) {
      return `${l.label}: ${l.whole ? '1 이상의 정수' : '0보다 큰 수'}를 쓰거나 비워 둔다(비우면 상한 없음)`;
    }
  }
  return null;
}

/** Everything a new account sends. */
export function registerFields(d: AccountDraft): AccountFields {
  return {
    name: d.name.trim(),
    auth_kind: d.authKind,
    affiliation: affiliationFor(d.authKind, d.affiliation),
    purpose: d.purpose.trim() || null,
    data_terms: d.dataTerms.trim() || null,
    run_turn_limit: limitOf(d.runTurn),
    run_cost_limit_usd: limitOf(d.runCost),
    run_minute_limit: limitOf(d.runMinute),
    daily_cost_limit_usd: limitOf(d.daily),
  };
}

/** Only what changed, for a change. The kind and where the sign-in lives never change. */
export function changedFields(start: AccountDraft, d: AccountDraft): AccountFields {
  const out: AccountFields = {};
  if (d.name.trim() !== start.name.trim()) out.name = d.name.trim();
  if (d.purpose.trim() !== start.purpose.trim()) out.purpose = d.purpose.trim() || null;
  if (d.dataTerms.trim() !== start.dataTerms.trim()) out.data_terms = d.dataTerms.trim() || null;
  if (d.authKind === 'api_key' && d.affiliation !== start.affiliation) out.affiliation = d.affiliation;
  for (const l of LIMITS) {
    const before = limitOf(limitValue(start, l.key));
    const after = limitOf(limitValue(d, l.key));
    if (before !== after) out[l.key] = after;
  }
  return out;
}

/** The account a handoff form picks first: the earlier run's when it can still take work, else the first that can. */
export function firstAccount(accounts: Account[] | null, previous: string | null | undefined): string {
  const usable = (accounts ?? []).filter((a) => bucketOf(a) === 'ready');
  if (previous && usable.some((a) => a.id === previous)) return previous;
  return usable[0]?.id ?? '';
}

/** One line for an account in a picker: name, kind, and today's use or why it is stopped. */
export function pickerLabel(a: Account): string {
  const state = a.block ? `멈춤: ${causeLabel(a.block.cause)}` : todayLine(a);
  return `${a.name} (${KIND_LABELS[a.auth_kind]}) ${state}`;
}
