// Server API for AI accounts (FN-0501) and what their runs used against their limits (FN-0409).
// An account is a reference: whose account, what kind, and where the device keeps the sign-in. The
// server never takes a key value, and this screen never sends one. Same origin as the page; the
// server's refusals carry a reason in `message`, shown as it is.

import { call, type HistoryEntry } from './workApi';

export type AuthKind = 'personal_subscription' | 'org_account' | 'api_key';

export type AuthSource = 'device_login' | 'env:ANTHROPIC_API_KEY';

export type Affiliation = 'personal' | 'organization';

/** A limit is null when there is none. Costs are the executor's price-list estimate, not a bill. */
export type AccountLimits = {
  run_turn_limit: number | null;
  run_cost_limit_usd: number | null;
  run_minute_limit: number | null;
  daily_cost_limit_usd: number | null;
};

export type UsageTotals = {
  runs: number;
  cost_usd: number;
  input_tokens: number;
  output_tokens: number;
  cache_read_tokens: number;
  cache_creation_tokens: number;
  turns: number;
  duration_ms: number;
};

/** Why no run can start on the account now. */
export type AccountBlock = {
  cause: 'disabled' | 'extra_billing' | 'provider_limit' | 'daily_cost_limit' | 'usage_burst' | string;
  wait_reason: string;
  reason: string;
  resets_at: string | null;
};

export type Account = {
  id: string;
  name: string;
  provider: string;
  auth_kind: AuthKind;
  auth_method: 'subscription_login' | 'api_key';
  affiliation: Affiliation;
  purpose: string | null;
  data_terms: string | null;
  auth_source: AuthSource;
  device_id: string | null;
  device_name: string | null;
  owner_user_id: string | null;
  status: 'ready' | 'needs_login' | 'limit_reached' | 'disabled';
  block: AccountBlock | null;
  limits: AccountLimits;
  today: UsageTotals;
  version: number;
  created_at: string;
  updated_at: string;
};

export type DailyUsage = { account_id: string; day: string; totals: UsageTotals };

export type AccountRun = {
  id: string;
  work_item_id: string;
  work_item_title: string;
  state: string;
  end_reason: string | null;
  created_at: string;
  cost_usd: number | null;
  turns: number | null;
  stopped_by: string | null;
  auth_method: string | null;
};

export type AccountActionName = 'edit' | 'disable' | 'enable' | 'resume_billing';

export type AccountDetail = {
  account: Account;
  days: DailyUsage[];
  runs: AccountRun[];
  actions: { name: AccountActionName; allowed: boolean; reason: string | null }[];
  history: HistoryEntry[];
};

/** What the form sends. Text fields are names and words, never a key. */
export type AccountFields = Partial<{
  name: string;
  auth_kind: AuthKind;
  affiliation: Affiliation;
  purpose: string | null;
  data_terms: string | null;
  device_id: string | null;
}> &
  Partial<AccountLimits>;

const enc = encodeURIComponent;

export const accountsApi = {
  list: (signal?: AbortSignal) => call<Account[]>('GET', '/api/ai-accounts?include_disabled=true', undefined, signal),
  detail: (id: string, signal?: AbortSignal) => call<AccountDetail>('GET', `/api/ai-accounts/${enc(id)}`, undefined, signal),
  register: (fields: AccountFields) => call<AccountDetail>('POST', '/api/ai-accounts', fields),
  update: (id: string, version: number, fields: AccountFields) => call<AccountDetail>('PATCH', `/api/ai-accounts/${enc(id)}`, { version, ...fields }),
  disable: (id: string, version: number) => call<AccountDetail>('POST', `/api/ai-accounts/${enc(id)}/disable`, { version }),
  enable: (id: string, version: number) => call<AccountDetail>('POST', `/api/ai-accounts/${enc(id)}/enable`, { version }),
  resumeBilling: (id: string, version: number) => call<AccountDetail>('POST', `/api/ai-accounts/${enc(id)}/resume-billing`, { version }),
};
