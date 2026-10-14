import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Account, AccountDetail } from './accountsApi';
import { AccountDetailView } from './accounts/AccountDetail';
import { AccountForm } from './accounts/AccountForm';
import { AccountRow } from './accounts/AccountList';
import {
  changedFields,
  DEFAULT_DRAFT,
  draftFrom,
  draftProblem,
  firstAccount,
  groupAccounts,
  listState,
  looksSecret,
  readView,
  registerFields,
  todayLine,
  writeView,
} from './accounts/accountView';
import { HandoffForm } from './work/Handoff';
import { RunRow } from './work/RunRow';
import { billingLine, limitsLine, stopLine, usageLine } from './work/runUsage';
import type { ItemRun, Repository, WorkItemDetail } from './workApi';

const totals = { runs: 2, cost_usd: 12.5, input_tokens: 4, output_tokens: 280, cache_read_tokens: 3112, cache_creation_tokens: 3478, turns: 9, duration_ms: 125000 };

const account = (over: Partial<Account> = {}): Account => ({
  id: 'acc_owner_login',
  name: '이 PC의 Claude Code 로그인',
  provider: 'anthropic',
  auth_kind: 'personal_subscription',
  auth_method: 'subscription_login',
  affiliation: 'personal',
  purpose: '소유자 본인의 작업',
  data_terms: null,
  auth_source: 'device_login',
  device_id: null,
  device_name: null,
  owner_user_id: 'usr_owner',
  status: 'ready',
  block: null,
  limits: { run_turn_limit: 200, run_cost_limit_usd: 30, run_minute_limit: 60, daily_cost_limit_usd: 200 },
  today: totals,
  version: 2,
  created_at: '2026-10-14T00:00:00Z',
  updated_at: '2026-10-14T01:00:00Z',
  ...over,
});

const stoppedDaily = account({
  id: 'acc_b',
  name: '회사 계정',
  auth_kind: 'org_account',
  affiliation: 'organization',
  created_at: '2026-10-14T02:00:00Z',
  block: { cause: 'daily_cost_limit', wait_reason: 'waiting_limit_reset', reason: '오늘 사용량이 하루 상한에 닿았다', resets_at: '2026-10-15T00:00:00+09:00' },
});

const detail = (a: Account = account()): AccountDetail => ({
  account: a,
  days: [{ account_id: a.id, day: '2026-10-14', totals }],
  runs: [{ id: 'run_1', work_item_id: 'wrk_1', work_item_title: '로그인 화면 고치기', state: 'ended', end_reason: 'failed', created_at: '2026-10-14T01:00:00Z', cost_usd: 1.5, turns: 200, stopped_by: 'run_turn_limit', auth_method: 'subscription_login' }],
  actions: [
    { name: 'edit', allowed: true, reason: null },
    { name: 'disable', allowed: true, reason: null },
    { name: 'enable', allowed: false, reason: '쓰는 중인 계정이다' },
    { name: 'resume_billing', allowed: false, reason: '추가 과금 신호로 멈추지 않았다' },
  ],
  history: [{ action: 'account.registered', actor_kind: 'person', actor_id: 'usr_owner', details: {}, at: '2026-10-14T00:00:00Z' }],
});

/** A made-up value with the start of an Anthropic API key, put together so no file holds a key-shaped literal. */
const fakeKey = ['sk', 'ant', 'api03', 'Zx9'.repeat(12)].join('-');

describe('account view', () => {
  it('groups accounts by whether they can take work now, every group shown', () => {
    const disabled = account({ id: 'acc_c', status: 'disabled', block: { cause: 'disabled', wait_reason: 'waiting_account', reason: '비활성화', resets_at: null } });
    const groups = groupAccounts([disabled, stoppedDaily, account()]);
    expect(groups.map((g) => [g.label, g.items.map((a) => a.id)])).toEqual([
      ['쓸 수 있음', ['acc_owner_login']],
      ['멈춤', ['acc_b']],
      ['비활성', ['acc_c']],
    ]);
    expect(listState(null, null)).toEqual({ kind: 'loading' });
    expect(listState([], null)).toEqual({ kind: 'unused' });
  });

  it('says today against the daily limit, or that there is none', () => {
    expect(todayLine(account())).toBe('오늘 $12.50, 하루 상한 $200.00의 6%');
    expect(todayLine(account({ limits: { run_turn_limit: null, run_cost_limit_usd: null, run_minute_limit: null, daily_cost_limit_usd: null } }))).toBe('오늘 $12.50, 하루 상한 없음');
  });

  it('keeps the selection in the address', () => {
    expect(readView('#accounts?item=acc_x')).toEqual({ selected: 'acc_x' });
    expect(writeView({ selected: null })).toBe('#accounts');
    expect(writeView(readView(writeView({ selected: 'acc_y' })))).toBe('#accounts?item=acc_y');
  });

  it('refuses a key in any field before sending, but not identifiers or commit hashes', () => {
    expect(looksSecret(fakeKey)).toBe(true);
    expect(looksSecret('Zq7Yt2Lm9Pw4Rx8Vb3Nc6Kd1Hf5Gs0Ja')).toBe(true);
    expect(looksSecret('run_0192f3a4-b5c6-7d8e-9f0a-1b2c3d4e5f60')).toBe(false);
    expect(looksSecret('a1b2c3d4e5'.repeat(4))).toBe(false);
    expect(draftProblem({ ...DEFAULT_DRAFT, name: '내 키', purpose: `키는 ${fakeKey}` })).toContain('API 키나 토큰처럼');
    expect(draftProblem({ ...DEFAULT_DRAFT, name: '' })).toBe('이름을 쓴다');
    expect(draftProblem({ ...DEFAULT_DRAFT, name: 'a', runTurn: '1.5' })).toContain('정수');
    expect(draftProblem({ ...DEFAULT_DRAFT, name: 'a', daily: '' })).toBeNull();
  });

  it('sends every field when registering and only what changed when changing; empty is no limit', () => {
    expect(registerFields({ ...DEFAULT_DRAFT, name: ' 키 ', authKind: 'api_key', affiliation: 'personal', daily: '' })).toEqual({
      name: '키',
      auth_kind: 'api_key',
      affiliation: 'personal',
      purpose: null,
      data_terms: null,
      run_turn_limit: 200,
      run_cost_limit_usd: 30,
      run_minute_limit: 60,
      daily_cost_limit_usd: null,
    });
    // A subscription is personal whatever the form held.
    expect(registerFields({ ...DEFAULT_DRAFT, name: 'a', affiliation: 'organization' }).affiliation).toBe('personal');
    const start = draftFrom(account());
    expect(changedFields(start, { ...start, daily: '50', runMinute: '' })).toEqual({ daily_cost_limit_usd: 50, run_minute_limit: null });
    expect(changedFields(start, start)).toEqual({});
  });

  it('picks the account of the earlier run when it can still take work, else the first that can', () => {
    expect(firstAccount([stoppedDaily, account()], 'acc_b')).toBe('acc_owner_login');
    expect(firstAccount([account(), account({ id: 'acc_d', created_at: '2026-10-14T03:00:00Z' })], 'acc_d')).toBe('acc_d');
    expect(firstAccount([stoppedDaily], null)).toBe('');
    expect(firstAccount(null, null)).toBe('');
  });
});

describe('account screen', () => {
  it('draws a row with the kind, the name and today against the daily limit, or why it stopped', () => {
    const html = renderToStaticMarkup(<AccountRow account={stoppedDaily} selected={false} tabbable onPick={() => {}} />);
    expect(html).toContain('조직 계정');
    expect(html).toContain('회사 계정');
    expect(html).toContain('멈춤: 하루 상한');
  });

  it('shows the kind, where the sign-in lives, today, the limits and their rules, days, runs and history, and no key', () => {
    const html = renderToStaticMarkup(<AccountDetailView account={account()} detail={detail()} actions={{ edit: () => {}, run: async () => {} }} />);
    for (const part of ['개인 구독', '구독 로그인', '그 PC의 Claude Code 로그인', '맡긴 PC', '$12.50', '하루 상한 $200.00의 6%', '턴 200, 비용 $30.00, 60분', '--max-turns', '비정상 소비', '2026-10-14', '로그인 화면 고치기', '실행 상한(턴)', '등록', '청구액이 아니며']) {
      expect(html, part).toContain(part);
    }
    expect(html).toContain('비활성화하기');
    expect(html).not.toContain('추가 과금 멈춤 풀기');
    expect(html).not.toMatch(/sk-ant|password|비밀번호/);
  });

  it('shows why a stopped account is stopped and offers to lift an extra-billing stop', () => {
    const billing = account({ block: { cause: 'extra_billing', wait_reason: 'billing_stopped', reason: '추가 과금 신호를 받아 멈춘 AI 계정이다', resets_at: null } });
    const d = detail(billing);
    d.actions = d.actions.map((x) => (x.name === 'resume_billing' ? { ...x, allowed: true, reason: null } : x));
    const html = renderToStaticMarkup(<AccountDetailView account={billing} detail={d} actions={{ edit: () => {}, run: async () => {} }} />);
    expect(html).toContain('멈춤: 추가 과금 신호');
    expect(html).toContain('추가 과금 멈춤 풀기');
    expect(html).toContain('role="status"');
  });

  it('asks for no key: an API key account names the environment variable on that PC', () => {
    const html = renderToStaticMarkup(<AccountForm mode="new" start={{ ...DEFAULT_DRAFT, authKind: 'api_key', affiliation: 'organization' }} onSubmit={async () => {}} onCancel={() => {}} error={null} />);
    expect(html).toContain('그 PC의 환경 변수 ANTHROPIC_API_KEY');
    expect(html).toContain('로그인 정보나 키 값을 받지 않는다');
    expect(html).not.toMatch(/type="password"/);
    for (const label of ['실행마다 턴', '실행마다 비용', '실행마다 시간(분)', '계정의 하루 비용']) expect(html).toContain(label);
    const edit = renderToStaticMarkup(<AccountForm mode="edit" start={draftFrom(account())} onSubmit={async () => {}} onCancel={() => {}} error={null} />);
    expect(edit).toContain('종류와 자격 위치는 바꾸지 않는다');
    expect(edit).not.toContain('type="radio"');
  });
});

const repo: Repository = { device_id: 'dev_a', device_name: 'home-pc', repo_key: 'git:abc', name: 'zm-baton', remote: null, updated_at: '2026-10-14T00:00:00Z' };

const work = (): WorkItemDetail => ({
  item: {
    id: 'wrk_a',
    title: '작업',
    stage: 'ready',
    wait_reasons: [],
    generation: 0,
    created_at: '2026-10-14T00:00:00Z',
    version: 3,
    external_ref: null,
    project: null,
    source_of_truth: 'product',
    executor_kind: 'human',
    next_action: null,
    wait_note: null,
    opened_on: null,
    due_on: null,
    closed_at: null,
    updated_at: '2026-10-14T00:00:00Z',
  },
  responsible_user_id: 'usr_owner',
  executor_id: 'usr_owner',
  acceptance: null,
  source_ref: null,
  git_ref: null,
  live_run: false,
  read_only_reason: null,
  moves: [],
  history: [],
  delegation: { allowed: true, reason: null, mode: 'first' },
});

describe('handing work over on an account', () => {
  it('picks the account, locks a stopped one with its reason, and shows the run limits and today', () => {
    const html = renderToStaticMarkup(<HandoffForm detail={work()} repos={[repo]} accounts={[account(), stoppedDaily]} onDelegate={async () => {}} defaultOpen />);
    expect(html).toContain('AI 계정');
    expect(html).toMatch(/<option value="acc_owner_login" selected="">이 PC의 Claude Code 로그인 \(개인 구독\)<\/option>/);
    expect(html).toMatch(/<option value="acc_b" disabled="">회사 계정 \(조직 계정\), 멈춤: 하루 상한<\/option>/);
    expect(html).toContain('이 실행의 상한: 턴 200, 비용 $30.00, 60분. 오늘 $12.50, 하루 상한 $200.00의 6%.');
  });

  it('cannot hand over when no account can take work, and says where to look', () => {
    const html = renderToStaticMarkup(<HandoffForm detail={work()} repos={[repo]} accounts={[stoppedDaily]} onDelegate={async () => {}} defaultOpen />);
    expect(html).toContain('지금 일을 맡을 수 있는 AI 계정이 없다');
    expect(html).toContain('href="#accounts"');
    expect(html).toMatch(/<button type="submit" class="btn btn-primary" disabled="">/);
  });

  it('leaves the pick to an older server that has no accounts', () => {
    const html = renderToStaticMarkup(<HandoffForm detail={work()} repos={[repo]} accounts={null} onDelegate={async () => {}} defaultOpen />);
    expect(html).not.toContain('AI 계정');
  });
});

const run = (over: Partial<ItemRun> = {}): ItemRun => ({
  id: 'run_1',
  work_item_id: 'wrk_a',
  state: 'ended',
  end_reason: 'failed',
  executor: 'claude_code',
  generation: 1,
  device_id: 'dev_a',
  late_reports: false,
  created_at: '2026-10-14T02:00:00Z',
  last_event_at: '2026-10-14T02:05:00Z',
  claimed_at: '2026-10-14T02:00:01Z',
  started_at: '2026-10-14T02:00:02Z',
  ended_at: '2026-10-14T02:05:00Z',
  last_activity_at: '2026-10-14T02:04:59Z',
  delegation: null,
  workspace: null,
  git_changes: null,
  instruction: null,
  account: { id: 'acc_owner_login', name: '이 PC의 Claude Code 로그인', auth_kind: 'personal_subscription' },
  auth_method: 'subscription_login',
  limits: { max_turns: 200, max_budget_usd: 12.5, max_minutes: 60, budget_from: 'daily' },
  usage: { cost_usd: 0.0170312, input_tokens: 2, output_tokens: 140, cache_read_tokens: 1556, cache_creation_tokens: 1739, num_turns: 200, duration_ms: 2160, models: ['claude-opus-5-5'], result_subtype: 'error_max_turns' },
  billing_location: 'subscription_limit',
  billing_detection: 'not_detected',
  billing_evidence: { windows: { five_hour: { utilization: 0.11 }, seven_day: { utilization: 0.32 } } },
  stopped_by: 'run_turn_limit',
  ...over,
});

describe('a run on an account', () => {
  it('says the account, how it signed in, its limits, what it used, where it is charged and what stopped it', () => {
    expect(limitsLine(run())).toBe('턴 200, 비용 $12.50(오늘 남은 만큼), 60분');
    expect(usageLine(run())).toBe('가격표 환산 $0.02, 턴 200, 2초');
    expect(billingLine(run())).toBe('구독 한도, 5시간 11%, 7일 32%');
    expect(billingLine(run({ billing_location: 'unknown', billing_detection: 'unavailable', billing_evidence: null }))).toBe('알 수 없음, 확인 불가');
    expect(stopLine(run())).toContain('실행 상한(턴)에 닿아 멈췄다');
    expect(stopLine(run({ stopped_by: 'extra_billing' }))).toContain('추가 과금 멈춤 풀기');
    expect(stopLine(run({ stopped_by: null }))).toBeNull();
    const html = renderToStaticMarkup(
      <RunRow run={run()} events={[]} now={Date.parse('2026-10-14T02:06:00Z')} outcomeUnknown={false} onWithdraw={async () => {}} onCancel={async () => {}} onSendInput={async () => {}} />,
    );
    for (const part of ['이 PC의 Claude Code 로그인', '구독 로그인', '턴 200, 비용 $12.50(오늘 남은 만큼), 60분', '가격표 환산 $0.02', '구독 한도, 5시간 11%', '실행 상한(턴)에 닿아 멈췄다', '캐시 읽기 1,556']) {
      expect(html, part).toContain(part);
    }
  });

  it('draws a run from before accounts as before', () => {
    const html = renderToStaticMarkup(
      <RunRow
        run={run({ account: null, auth_method: null, limits: null, usage: null, billing_location: null, billing_detection: null, billing_evidence: null, stopped_by: null })}
        events={[]}
        now={Date.parse('2026-10-14T02:06:00Z')}
        outcomeUnknown={false}
        onWithdraw={async () => {}}
        onCancel={async () => {}}
        onSendInput={async () => {}}
      />,
    );
    for (const absent of ['<dt>계정</dt>', '<dt>상한</dt>', '<dt>사용량</dt>', '<dt>과금 위치</dt>', '토큰:']) expect(html).not.toContain(absent);
  });
});
