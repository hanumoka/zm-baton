import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { groupByBucket } from './inboxModel';
import { InboxDetail } from './inbox/InboxDetail';
import { InboxRow } from './inbox/InboxList';
import { groupNote, inboxGroups, kindOf, reviewFiles, rowTitle } from './inbox/inboxView';
import { ReviewBody } from './review/ReviewDetail';
import { autoLabel, decisionBody, humanLabel, notSentWhy, outcomeText, rateLabel, reviewWhy } from './review/reviewModel';
import { ORG, readView, scopes, ScopeDetail, sourceLine, writeView } from './review/RiskRules';
import type { ReviewDetail, ReviewSettings, RunReview } from './reviewsApi';
import type { InboxItem } from './sessionsApi';
import { RunList } from './work/Handoff';
import type { ItemRun } from './workApi';
import { historyLine } from './workModel';

/* Picking what people must see (FN-0411): review requests on the first screen, the run list's review line and the risk path lists. */

const T0 = Date.parse('2026-10-14T03:00:00Z');
const iso = (minutes: number) => new Date(T0 + minutes * 60_000).toISOString();
const SNAP = 'abcdef0123456789abcdef0123456789abcdef01';

const item = (over: Partial<InboxItem> = {}): InboxItem => ({
  id: 'inb_r',
  bucket: 4,
  kind: 'risk_review',
  source_kind: 'rvw',
  source_id: 'rvw_1',
  summary: '위험 경로 .github/workflows/ci.yml 외 1개 · 결제 화면',
  detail: { work_item_id: 'wrk_a', run_id: 'run_1', reason: 'risk_path' },
  wait_kind: null,
  repeat_count: 0,
  waiting_since: iso(-30),
  notify: 'immediate',
  promoted_at: null,
  ...over,
});

const review = (over: Partial<ReviewDetail> = {}): ReviewDetail => ({
  id: 'rvw_1',
  reason: 'risk_path',
  raised_by: 'rule',
  state: 'open',
  target_kind: 'run_snapshot',
  target_ref: SNAP,
  evidence: { list: 'org', matches: [{ pattern: '.github/**', files: ['.github/workflows/ci.yml'] }, { pattern: '**/db/migration/**', files: ['db/migration/V1.sql'] }] },
  created_at: iso(-30),
  decided_by: null,
  decided_at: null,
  note: null,
  superseded_by: null,
  proposal: null,
  work_item: { id: 'wrk_a', title: '결제 화면', external_ref: null, stage: 'result_review', version: 7, project: null },
  run: {
    id: 'run_1',
    end_reason: 'submitted',
    ended_at: iso(-31),
    workspace: { repo_key: 'git:abc', base_commit: '0123456789abcdef0123456789abcdef01234567', branch: 'zm-baton/run_1', base_ref: 'main' },
    repo_name: 'demo',
    device_name: 'home-pc',
    files: ['.github/workflows/ci.yml', 'README.md', 'db/migration/V1.sql'],
    truncated: false,
    result_snapshot_sha: SNAP,
    human_review_state: 'required',
    auto_check_state: 'not_run',
  },
  effects: { approve: '승인을 기록한다', reject: '작업을 결과 검토에서 수행으로 돌린다', reject_needs_version: true },
  suggested_pattern: null,
  ...over,
});

const body = (r: ReviewDetail, i: InboxItem = item()) =>
  renderToStaticMarkup(<ReviewBody detail={r} item={i} now={T0} onDecided={() => {}} onError={() => {}} send={async () => ({ review: r, effect: 'approved', fix_work_item_id: null })} />);

describe('the first screen', () => {
  it('fills the risk and sample buckets and raises a review that waited a day to the top bucket', () => {
    const risk = item();
    const sample = item({ id: 'inb_s', bucket: 5, kind: 'sample_review', notify: 'digest', source_id: 'rvw_2' });
    const old = item({ id: 'inb_o', source_id: 'rvw_3', waiting_since: iso(-26 * 60), promoted_at: iso(-60) });
    const groups = inboxGroups([risk, sample, old]);
    expect(groups.map((g) => [g.bucket, g.raised, g.items.map((i) => i.id)])).toEqual([
      [1, false, ['inb_o']],
      [2, true, []],
      [3, true, []],
      [4, true, ['inb_r']],
      [5, true, ['inb_s']],
    ]);
    expect(groupByBucket([old]).get(4)).toEqual([]);
    expect(kindOf(risk)).toEqual({ mark: 'alert', label: '위험 검토' });
    expect(kindOf(sample)).toEqual({ mark: 'done', label: '표본 검토' });
    expect(groupNote({ bucket: 4, raised: true })).toBe('사람이 볼 위험 결과가 없다');
    expect(groupNote({ bucket: 5, raised: true })).toBe('고른 표본이 없다');
  });

  it('titles a review row with its work item and puts the caught file on the second line', () => {
    const i = item({ detail: { title: '결제 화면', files: ['.github/workflows/ci.yml', 'db/migration/V1.sql'] } });
    expect(rowTitle(i, null)).toBe('결제 화면');
    expect(reviewFiles(i)).toEqual({ first: '.github/workflows/ci.yml', more: 1 });
    const html = renderToStaticMarkup(<InboxRow item={{ ...i, promoted_at: iso(-5) }} sessions={null} now={T0} selected={false} tabbable onPick={() => {}} />);
    expect(html).toContain('<span class="lrow-title">결제 화면</span>');
    expect(html).toContain('.github/workflows/ci.yml 외 1개');
    expect(html).toContain('오래 기다려 위로 올림');
    expect(rowTitle(item({ detail: null }), null)).toBe(item().summary);
  });

  it('writes review steps into the work item history', () => {
    const h = (action: string, details: Record<string, unknown>) => historyLine({ action, actor_kind: 'person', actor_id: 'usr_owner', details, at: iso(0) });
    expect(h('review.raised', { reason: 'risk_path' })).toBe('결과를 사람 검토로 보냄: 위험 경로');
    expect(h('review.approved', { effect: 'released' })).toBe('사람 검토 승인, 완료로 넘어감');
    expect(h('review.rejected', { effect: 'returned', note: '고친다' })).toBe('사람 검토 반려 — 고친다');
    expect(h('review.rejected', { effect: 'fix_created' })).toBe('사람 검토 반려, 고침 작업을 만듦');
  });

  it('opens a review request in its own detail, which loads it from the server', () => {
    const html = renderToStaticMarkup(<InboxDetail item={item()} sessions={[]} now={T0} onJudged={() => {}} onError={() => {}} />);
    expect(html).toContain('skel-list'); // the row-shaped placeholder while the request loads
    expect(html).toContain('위험 경로 .github/workflows/ci.yml 외 1개');
  });

  it('says why it came, which patterns caught which files, what the result is and what each verdict does', () => {
    const html = body(review());
    expect(html).toContain('AI가 위험 경로를 고친 결과를 본다');
    expect(html).toContain('조직의 위험 목록에 걸렸다');
    expect(html).toContain('<code>.github/**</code>');
    expect(html).toContain('<code>**/db/migration/**</code>');
    expect(html).toContain('<code>zm-baton/run_1</code>');
    expect(html).toContain('스냅샷 커밋 <code>abcdef012345</code>');
    expect(html).toContain('사람 기다림(위험 목록), 자동 검사 안 함');
    expect(html).toMatch(/<button type="button" class="btn btn-primary">승인하기<small>승인을 기록한다<\/small><\/button>/);
    expect(html).toMatch(/반려하기<small>작업을 결과 검토에서 수행으로 돌린다<\/small>/);
    // The caught files are marked in the list of changed ones; the others are not.
    expect(html).toMatch(/<code>\.github\/workflows\/ci\.yml<\/code><small class="hr-flag">위험 경로<\/small>/);
    expect(html).toMatch(/<code>README\.md<\/code><\/li>/);
    expect(html).toContain('href="#work?item=wrk_a"');
    expect(html).toContain('AI는 이 요청을 닫을 수 없다');
  });

  it('marks a target it could not pin, says a sample does not block, and shows a closed request without buttons', () => {
    const unpinned = body(review({ target_kind: 'unpinned', target_ref: 'f'.repeat(64) }));
    expect(unpinned).toContain('대상을 고정할 수 없음');
    expect(unpinned).not.toContain('스냅샷 커밋');

    const sample = body(review({ reason: 'sample', evidence: { rate: 0.1, draw: 0.04 }, suggested_pattern: 'src/pay/**' }), item({ bucket: 5, kind: 'sample_review' }));
    expect(sample).toContain('AI가 통과시킨 결과를 표본으로 본다');
    expect(sample).toContain('10건 중 1건 비율로 골라');
    expect(sample).toContain('작업은 막지 않는다');

    const closed = body(review({ state: 'superseded', effects: null }));
    expect(closed).toContain('새 실행 결과로 대체됐다');
    expect(closed).not.toContain('승인하기');

    const raised = body(review(), item({ promoted_at: iso(-10) }));
    expect(raised).toContain('오래 기다린 검토');
  });

  it('sends a rejection only with its reason, with the work item version and a sample candidate', () => {
    const r = review();
    expect(decisionBody(r, 'rejected', '  ', '')).toBeNull();
    expect(decisionBody(r, 'rejected', '권한 파일은 두기', '')).toEqual({ decision: 'rejected', target_ref: SNAP, note: '권한 파일은 두기', work_version: 7 });
    expect(decisionBody(r, 'rejected', '빠졌다', ' src/pay/** ')).toEqual({
      decision: 'rejected',
      target_ref: SNAP,
      note: '빠졌다',
      work_version: 7,
      propose_risk_path: 'src/pay/**',
    });
    expect(decisionBody(r, 'approved', '', 'ignored')).toEqual({ decision: 'approved', target_ref: SNAP });
    expect(outcomeText('returned')).toContain('작업이 수행으로 돌아갔다');
    expect(outcomeText('fix_created')).toContain('고침 작업');
    expect(outcomeText('released')).toContain('완료로 넘어갔다');
  });
});

const run = (over: Partial<ItemRun> = {}): ItemRun => ({
  id: 'run_1',
  work_item_id: 'wrk_a',
  state: 'ended',
  end_reason: 'submitted',
  executor: 'claude_code',
  generation: 2,
  device_id: 'dev_a',
  late_reports: false,
  created_at: '2026-10-14T02:00:00Z',
  last_event_at: '2026-10-14T02:05:00Z',
  claimed_at: '2026-10-14T02:00:01Z',
  started_at: '2026-10-14T02:00:02Z',
  ended_at: '2026-10-14T02:05:00Z',
  last_activity_at: '2026-10-14T02:05:00Z',
  delegation: null,
  workspace: null,
  git_changes: null,
  instruction: null,
  ...over,
});

const rv = (over: Partial<RunReview> = {}): RunReview => ({
  id: 'rvw_1',
  reason: 'risk_path',
  state: 'open',
  target_kind: 'run_snapshot',
  decided_at: null,
  note: null,
  superseded_by: null,
  inbox_item_id: 'inb_r',
  ...over,
});

const runs = (r: ItemRun) => renderToStaticMarkup(<RunList runs={[r]} events={[]} now={T0} outcomeUnknown={false} problem={null} onWithdraw={async () => {}} />);

describe('the run list', () => {
  it('shows the person review and the automatic check apart, with the way to the open request', () => {
    const html = runs(run({ human_review_state: 'required', auto_check_state: 'not_run', reviews: [rv()] }));
    expect(html).toContain('사람 검토 <strong class="is-wait">기다림(위험 목록)</strong>');
    expect(html).toContain('자동 검사 안 함');
    expect(html).toContain('href="#inbox?item=inb_r"');
  });

  it('says 사람 미확인 and why nobody was sent, and tells approved from rejected', () => {
    const none = runs(run({ human_review_state: 'not_reviewed', auto_check_state: 'not_run', review_basis: { sample: { draw: 0.734, rate: 0.1 } } }));
    expect(none).toContain('<strong class="is-none">사람 미확인</strong>');
    expect(none).toContain('표본으로 뽑히지 않았다(뽑은 수 0.734, 비율 10건 중 1건)');
    const replaced = runs(run({ human_review_state: 'not_reviewed', reviews: [rv({ state: 'superseded', inbox_item_id: null })] }));
    expect(replaced).toContain('다음 실행의 결과로 대체됐다');
    expect(runs(run({ human_review_state: 'reviewed', reviews: [rv({ state: 'rejected', inbox_item_id: null })] }))).toContain('<strong>반려</strong>');
    expect(runs(run({ human_review_state: 'reviewed', reviews: [rv({ state: 'approved', inbox_item_id: null })] }))).toContain('<strong>승인</strong>');
    expect(runs(run({ human_review_state: null }))).not.toContain('사람 검토');
  });

  it('puts the states into words without inventing one', () => {
    expect(humanLabel('sampled', [])).toBe('기다림(표본)');
    expect(humanLabel(undefined, [])).toBeNull();
    expect(autoLabel('failed')).toBe('자동 검사 실패');
    expect(autoLabel(null)).toBeNull();
    expect(notSentWhy({ sample: { capped: true } })).toContain('상한');
    expect(notSentWhy({ sample: { skipped: 'unpinned' } })).toContain('고정하지 못해');
    expect(notSentWhy(null)).toBeNull();
    expect(rateLabel(0.1)).toBe('10건 중 1건');
    expect(rateLabel(0.25)).toBe('4건 중 1건');
    expect(rateLabel(0.15)).toBe('15%');
    expect(rateLabel(0)).toBe('표본 없음');
    expect(reviewWhy({ reason: 'risk_path', evidence: { list: 'project' } })).toContain('이 프로젝트의 위험 목록');
  });
});

const settings = (over: Partial<ReviewSettings> = {}): ReviewSettings => ({
  org: { patterns: ['AGENTS.md', '.github/**'], customized: false },
  default_patterns: ['AGENTS.md', '.github/**'],
  sample_rate: 0.1,
  sample_daily_cap: 5,
  promote_after_hours: 24,
  projects: [
    { project: { id: 'prj_a', external_ref: 'PRJ-009', name: 'zm-baton' }, patterns: ['AGENTS.md', '.github/**'], inherited: true, open_proposals: 1 },
    { project: { id: 'prj_b', external_ref: null, name: '문서' }, patterns: ['docs/**'], inherited: false, open_proposals: 0 },
  ],
  proposals: [
    { review_id: 'rvw_9', project_id: 'prj_a', pattern: 'src/pay/**', work_item_id: 'wrk_a', work_title: '결제 화면', note: '검증이 빠졌다', proposed_at: iso(-5) },
  ],
  ...over,
});

describe('the risk path lists', () => {
  it('lists the organisation, then every project with where its list comes from and its candidates', () => {
    const list = scopes(settings());
    expect(list.map((s) => [s.key, s.label, sourceLine(s), s.proposals.length])).toEqual([
      [ORG, '조직 기본', 'zm-baton의 처음 목록', 0],
      ['prj_a', 'PRJ-009 zm-baton', '조직 기본 목록을 쓴다', 1],
      ['prj_b', '문서', '이 프로젝트가 고친 목록', 0],
    ]);
    expect(sourceLine(scopes(settings({ org: { patterns: ['x/**'], customized: true } }))[0])).toBe('조직이 고친 목록');
    expect(writeView({ selected: 'prj_a' })).toBe('#risk?item=prj_a');
    expect(readView('#risk?item=prj_a')).toEqual({ selected: 'prj_a' });
    expect(readView('#risk')).toEqual({ selected: null });
  });

  const detail = (key: string) => {
    const s = settings();
    const scope = scopes(s).find((x) => x.key === key)!;
    return renderToStaticMarkup(<ScopeDetail scope={scope} settings={s} api={{} as never} titleRef={null} inSheet={false} onSaved={() => {}} onError={() => {}} />);
  };

  it('edits a project list one pattern per line, offers its candidates, and locks saving until something changed', () => {
    const html = detail('prj_a');
    expect(html).toContain('AGENTS.md\n.github/**</textarea>');
    expect(html).toContain('<code>src/pay/**</code>');
    expect(html).toContain('목록에 더하기');
    expect(html).toContain('후보에서 빼기');
    expect(html).toContain('반려 이유: 검증이 빠졌다');
    expect(html).toMatch(/<button type="submit" class="btn btn-primary" disabled="">이 프로젝트의 목록 저장<\/button>/);
    expect(html).toContain('바꾼 것이 없다');
    expect(html).not.toContain('조직 기본 목록으로 되돌리기'); // it already uses it
    expect(detail('prj_b')).toContain('조직 기본 목록으로 되돌리기');
  });

  it('sets the sample rate and cap on the organisation', () => {
    const html = detail(ORG);
    expect(html).toContain('표본 비율(%)');
    expect(html).toMatch(/value="10"/);
    expect(html).toContain('하루 표본 상한(건)');
    expect(html).toContain('10건 중 1건, 하루 5건까지');
    expect(html).not.toContain('처음 목록으로 되돌리기');
  });
});
