import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { DecisionList, DecisionView } from './Decisions';
import { WorkList } from './Work';
import type { Decision, DecisionDetail, Move, WorkItem, WorkItemDetail } from './workApi';
import { StageBar, WorkDetail } from './WorkDetail';
import { groupByStage, historyLine, moveLabel, waitLabel, workTitle } from './workModel';

const item = (over: Partial<WorkItem> = {}): WorkItem => ({
  id: 'wrk_a',
  title: '작업 하나',
  stage: 'ready',
  wait_reasons: [],
  generation: 0,
  created_at: '2026-10-13T00:00:00Z',
  version: 3,
  external_ref: null,
  project: null,
  source_of_truth: 'product',
  executor_kind: 'human',
  next_action: null,
  wait_note: null,
  opened_on: '2026-10-13',
  due_on: null,
  closed_at: null,
  updated_at: '2026-10-13T01:00:00Z',
  ...over,
});

const detail = (over: Partial<WorkItemDetail> = {}, itemOver: Partial<WorkItem> = {}): WorkItemDetail => ({
  item: item(itemOver),
  responsible_user_id: 'usr_owner',
  executor_id: 'usr_owner',
  acceptance: null,
  source_ref: null,
  git_ref: null,
  live_run: false,
  read_only_reason: null,
  moves: [],
  history: [],
  ...over,
});

const noop = async () => {};
const actions = { move: noop, update: noop, addWait: noop, removeWait: noop };

describe('groupByStage', () => {
  it('keeps the stage order, shows empty open stages and hides empty closed ones', () => {
    const groups = groupByStage([
      item({ id: 'a', stage: 'in_progress', updated_at: '2026-10-13T01:00:00Z' }),
      item({ id: 'b', stage: 'in_progress', updated_at: '2026-10-13T02:00:00Z' }),
      item({ id: 'c', stage: 'done' }),
    ]);
    expect(groups.map((g) => g.stage)).toEqual(['draft', 'plan_review', 'ready', 'in_progress', 'result_review', 'done']);
    expect(groups.find((g) => g.stage === 'in_progress')!.items.map((i) => i.id)).toEqual(['b', 'a']);
    // A stage the organisation skips is hidden while it is empty.
    expect(groupByStage([item()], ['plan_review', 'result_review']).map((g) => g.stage)).toEqual(['draft', 'ready', 'in_progress']);
  });
});

describe('labels', () => {
  it('names a move by where it ends and the stages it passes', () => {
    const skip: Move = { target: 'result_review', path: ['result_review', 'done'], allowed: true, reason: null, needs_note: false };
    expect(moveLabel(skip, 'in_progress')).toBe('완료(으)로 (결과 검토 건너뜀)');
    expect(moveLabel({ ...skip, target: 'in_progress', path: ['in_progress'] }, 'ready')).toBe('수행(으)로');
    expect(moveLabel({ ...skip, target: 'in_progress', path: ['in_progress'] }, 'result_review')).toBe('돌려보내기 (수행으로)');
    expect(moveLabel({ ...skip, target: 'plan_review', path: ['plan_review', 'ready'] }, 'in_progress')).toBe('다시 계획 (수행 준비(으)로)');
    expect(moveLabel({ ...skip, target: 'plan_review', path: ['plan_review', 'ready'] }, 'draft')).toBe('수행 준비(으)로 (계획 검토 건너뜀)');
    expect(moveLabel({ ...skip, target: 'cancelled', path: ['cancelled'] }, 'ready')).toBe('취소');
    expect(waitLabel('waiting_other')).toBe('다른 사정으로 막힘');
    expect(workTitle({ external_ref: 'TSK-021', title: '제목' })).toBe('TSK-021 · 제목');
  });

  it('writes one history line per kind of change', () => {
    const at = '2026-10-13T00:00:00Z';
    const h = (action: string, details: Record<string, unknown>) => ({ action, actor_kind: 'person', actor_id: 'usr_owner', details, at });
    expect(historyLine(h('work_item.stage_changed', { from: 'in_progress', to: 'done', path: ['result_review', 'done'] }))).toBe(
      '수행 → 결과 검토 → 완료',
    );
    expect(historyLine(h('work_item.stage_changed', { from: 'result_review', to: 'in_progress', path: ['in_progress'], note: '빠짐' }))).toBe(
      '결과 검토 → 수행 — 빠짐',
    );
    expect(historyLine(h('work_item.synced', { fields: ['stage', 'next_action'] }))).toBe('원본에서 다시 가져옴: stage, next_action');
    expect(historyLine(h('work_item.wait_added', { reason: 'waiting_other', note: '회신' }))).toBe('대기 사유 붙임: 다른 사정으로 막힘 — 회신');
  });
});

describe('work screens', () => {
  it('lists work per stage with its number and wait reason', () => {
    const html = renderToStaticMarkup(
      <WorkList items={[item({ external_ref: 'TSK-021', wait_reasons: ['waiting_other'], stage: 'in_progress' })]} selected={null} onSelect={() => {}} />,
    );
    expect(html).toContain('<code>TSK-021</code>');
    expect(html).toContain('다른 사정으로 막힘');
    expect(renderToStaticMarkup(<WorkList items={[]} selected={null} onSelect={() => {}} />)).toContain('zm-baton import zm-docs --apply');
  });

  it('shows a refused move with its reason and hides editing on a Git copy', () => {
    const html = renderToStaticMarkup(
      <WorkDetail
        detail={detail(
          {
            read_only_reason: '정본이 Git 파일(registry/tasks.json)이다. 원본에서 고치고 다시 가져온다',
            git_ref: { path: 'registry/tasks.json', commit: '13923a34cdacb652' },
            moves: [{ target: 'in_progress', path: ['in_progress'], allowed: false, reason: '정본이 Git 파일이다', needs_note: false }],
          },
          { source_of_truth: 'git', external_ref: 'TSK-001', wait_reasons: ['waiting_other'], wait_note: '컨테이너가 안 뜬다' },
        )}
        projects={[]}
        skipped={[]}
        actions={actions}
        error={null}
      />,
    );
    expect(html).toContain('정본이 Git 파일(registry/tasks.json)');
    expect(html).toContain('읽은 커밋 13923a34cd');
    expect(html).toMatch(/<button disabled="">수행\(으\)로<\/button>/);
    // The read-only reason is shown once, above, not again under the buttons.
    expect(html).not.toContain('<ul class="why">');
    expect(html).toContain('다른 사정으로 막힘 — 컨테이너가 안 뜬다');
    expect(html).not.toContain('내용 고치기');
    expect(html).not.toContain('막힘 표시');
    expect(html).not.toContain('떼기');
  });

  it('offers editing and wait reasons on work made here, and shows the last refusal', () => {
    const html = renderToStaticMarkup(
      <WorkDetail
        detail={detail({ moves: [{ target: 'in_progress', path: ['in_progress'], allowed: true, reason: null, needs_note: false }] })}
        projects={[]}
        skipped={['plan_review']}
        actions={actions}
        error="HTTP 409: the work item changed"
      />,
    );
    expect(html).toContain('내용 고치기');
    expect(html).toContain('막힘 표시');
    expect(html).toContain('선행 작업 대기 표시');
    expect(html).toContain('role="alert"');
    expect(html).toContain('HTTP 409: the work item changed');
  });

  it('marks the current stage and dims skipped ones, and shows cancelled only when it is the stage', () => {
    const html = renderToStaticMarkup(<StageBar stage="ready" skipped={['plan_review', 'result_review']} />);
    expect(html).toContain('<li class="current" aria-current="step">수행 준비</li>');
    expect(html).toContain('<li class="skipped">계획 검토</li>');
    expect(html).not.toContain('취소');
    expect(renderToStaticMarkup(<StageBar stage="cancelled" skipped={[]} />)).toContain('취소');
  });
});

const decision = (over: Partial<Decision> = {}): Decision => ({
  id: 'dcs_a',
  external_ref: 'DEC-0106',
  question: '질문',
  status: 'accepted',
  decided_on: '2026-10-12',
  projects: [{ id: 'prj_a', external_ref: 'PRJ-009', name: 'zm-baton' }],
  choice_preview: '한다.',
  supersedes: null,
  superseded_by: null,
  updated_at: '2026-10-13T00:00:00Z',
  ...over,
});

describe('decision screens', () => {
  it('lists decisions and marks the ones not accepted', () => {
    const html = renderToStaticMarkup(
      <DecisionList list={[decision(), decision({ id: 'dcs_b', external_ref: 'DEC-0001', status: 'superseded' })]} selected="dcs_a" onSelect={() => {}} />,
    );
    expect(html).toContain('class="selected"');
    expect(html).toContain('대체됨');
  });

  it('shows every section, the source file and the supersession links', () => {
    const d: DecisionDetail = {
      decision: decision({ supersedes: { id: 'dcs_old', external_ref: 'DEC-0050', question: '예전' } }),
      choice: '한다.',
      sections: [
        { heading: '맥락', body: '배경' },
        { heading: '결정', body: '한다.' },
      ],
      source_of_truth: 'git',
      git_ref: { path: 'docs/governance/decisions/DEC-0106-x.md', commit: 'abcdef1234567' },
    };
    const html = renderToStaticMarkup(<DecisionView detail={d} onSelect={() => {}} />);
    expect(html).toContain('<h3>맥락</h3>');
    expect(html).toContain('<h3>결정</h3>');
    expect(html).toContain('docs/governance/decisions/DEC-0106-x.md');
    expect(html).toContain('대체한 결정: <button>DEC-0050</button>');
    expect(html).toContain('PRJ-009');
  });
});
