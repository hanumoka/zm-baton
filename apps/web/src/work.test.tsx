import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { NewWork } from './work/NewWork';
import { Work } from './work/Work';
import { ChipRow, reviewSummary } from './work/WorkChips';
import { StagePath, WorkDetail } from './work/WorkDetail';
import { WorkList, type ListHead } from './work/WorkList';
import {
  conditions,
  filterItems,
  groupByHolder,
  holderOf,
  listState,
  NO_FILTERS,
  readView,
  rowOrder,
  stageOptions,
  step,
  writeView,
  type ListState,
} from './work/workView';
import type { Move, Project, WorkItem, WorkItemDetail } from './workApi';
import { historyLine, moveLabel, moveParts, toward, waitLabel, workTitle } from './workModel';

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

const project = (over: Partial<Project> = {}): Project => ({ id: 'prj_a', external_ref: 'PRJ-007', name: 'hanumoka-site', open_work_items: 2, ...over });

const noop = async () => {};
const actions = { move: noop, update: noop, addWait: noop, removeWait: noop };
const head: ListHead = { base: '열린 작업', count: 2, narrowed: false };

function list(state: ListState, items: WorkItem[] = [], selected: string | null = null, showClosed = false) {
  return renderToStaticMarkup(
    <WorkList
      state={state}
      groups={groupByHolder(items, showClosed)}
      head={head}
      selected={selected}
      onSelect={() => {}}
      onPick={() => {}}
      onOpen={() => {}}
      onNew={() => {}}
      onClearFilters={() => {}}
      onShowClosed={() => {}}
      onReload={() => {}}
    />,
  );
}

describe('who holds the work', () => {
  it('puts closed work under 끝남, waiting work under 기다림, AI executors under AI 세션 and the rest with people', () => {
    expect(holderOf(item({ stage: 'done', wait_reasons: ['waiting_other'] }))).toBe('done');
    expect(holderOf(item({ stage: 'cancelled' }))).toBe('done');
    expect(holderOf(item({ wait_reasons: ['waiting_answer'], executor_kind: 'manager' }))).toBe('wait');
    expect(holderOf(item({ executor_kind: 'manager' }))).toBe('ai');
    expect(holderOf(item({ executor_kind: 'shared_ai' }))).toBe('ai');
    expect(holderOf(item({ executor_kind: 'human' }))).toBe('person');
    // Nobody yet: a person has to pick it up.
    expect(holderOf(item({ executor_kind: null }))).toBe('person');
  });

  it('keeps every group, even empty, and shows 끝남 only with closed work shown', () => {
    expect(groupByHolder([], false).map((g) => g.holder)).toEqual(['wait', 'ai', 'person']);
    expect(groupByHolder([], true).map((g) => g.holder)).toEqual(['wait', 'ai', 'person', 'done']);
  });

  it('orders a group by stage, then the most recently changed first', () => {
    const groups = groupByHolder(
      [
        item({ id: 'a', stage: 'in_progress', updated_at: '2026-10-13T01:00:00Z' }),
        item({ id: 'b', stage: 'ready', updated_at: '2026-10-12T00:00:00Z' }),
        item({ id: 'c', stage: 'in_progress', updated_at: '2026-10-13T02:00:00Z' }),
        item({ id: 'd', stage: 'in_progress', wait_reasons: ['waiting_other'] }),
      ],
      false,
    );
    expect(groups.find((g) => g.holder === 'person')!.items.map((i) => i.id)).toEqual(['b', 'c', 'a']);
    expect(rowOrder(groups)).toEqual(['d', 'b', 'c', 'a']);
  });
});

describe('filters', () => {
  const items = [
    item({ id: 'a', stage: 'in_progress', wait_reasons: ['waiting_other'] }),
    item({ id: 'b', stage: 'in_progress' }),
    item({ id: 'c', stage: 'ready' }),
  ];

  it('filters by stage and by 막힘 on the client', () => {
    expect(filterItems(items, { stage: 'in_progress', blocked: false }).map((i) => i.id)).toEqual(['a', 'b']);
    expect(filterItems(items, { stage: '', blocked: true }).map((i) => i.id)).toEqual(['a']);
    expect(filterItems(items, { stage: 'ready', blocked: true })).toEqual([]);
  });

  it('offers open stages, closed ones only with closed work, and a skipped stage only while it holds an item', () => {
    const open = stageOptions(items, ['plan_review', 'result_review'], false);
    expect(open.map((o) => o.stage)).toEqual(['draft', 'ready', 'in_progress']);
    expect(open.find((o) => o.stage === 'in_progress')?.count).toBe(2);
    expect(stageOptions([...items, item({ stage: 'plan_review' })], ['plan_review'], true).map((o) => o.stage)).toEqual([
      'draft',
      'plan_review',
      'ready',
      'in_progress',
      'result_review',
      'done',
      'cancelled',
    ]);
  });

  it('says what the list is filtered by', () => {
    expect(conditions({ projectId: 'prj_a', stage: 'ready', blocked: true, showClosed: false }, ' 도커 ', [project()])).toEqual([
      '프로젝트 hanumoka-site',
      '단계 수행 준비',
      '막힘만',
      '찾는 말 「도커」',
      '완료·취소 숨김',
    ]);
    expect(conditions({ ...NO_FILTERS, showClosed: true }, '', [])).toEqual([]);
  });

  it('tells loading, a failed read, nothing yet, nothing open, filtered to nothing and rows apart', () => {
    const base = { items: [] as WorkItem[] | null, visible: 0, error: null as string | null, filters: NO_FILTERS, query: '', closedExist: false, projects: [] };
    expect(listState({ ...base, items: null })).toEqual({ kind: 'loading' });
    expect(listState({ ...base, items: null, error: 'HTTP 503' })).toEqual({ kind: 'error', message: 'HTTP 503' });
    expect(listState(base)).toEqual({ kind: 'unused' });
    expect(listState({ ...base, filters: { ...NO_FILTERS, showClosed: true }, closedExist: null })).toEqual({ kind: 'unused' });
    expect(listState({ ...base, closedExist: true })).toEqual({ kind: 'no-open' });
    expect(listState({ ...base, query: '도커' })).toEqual({ kind: 'filtered', conditions: ['찾는 말 「도커」', '완료·취소 숨김'] });
    expect(listState({ ...base, items: [item()], filters: { ...NO_FILTERS, blocked: true } }).kind).toBe('filtered');
    expect(listState({ ...base, items: [item()], visible: 1 })).toEqual({ kind: 'rows' });
    // A failure after a good read keeps the rows; the problem list at the top says what failed.
    expect(listState({ ...base, items: [item()], visible: 1, error: 'HTTP 503' })).toEqual({ kind: 'rows' });
  });
});

describe('address and keys', () => {
  it('keeps filters and the selected item in the address and drops what it does not know', () => {
    const v = { projectId: 'prj_a', stage: 'done' as const, blocked: true, showClosed: true, selected: 'wrk_b' };
    expect(writeView(v)).toBe('#work?project=prj_a&stage=done&blocked=1&closed=1&item=wrk_b');
    expect(readView(writeView(v))).toEqual(v);
    expect(writeView({ ...NO_FILTERS, selected: null })).toBe('#work');
    expect(readView('#work')).toEqual({ ...NO_FILTERS, selected: null });
    expect(readView('#work?stage=nothing').stage).toBe('');
    // A closed stage without closed work shown would filter to nothing; it is dropped.
    expect(readView('#work?stage=done').stage).toBe('');
  });

  it('moves the selection with the arrows, Home and End, and stops at the ends', () => {
    const ids = ['a', 'b', 'c'];
    expect(step(ids, null, 'ArrowDown')).toBe('a');
    expect(step(ids, 'a', 'ArrowDown')).toBe('b');
    expect(step(ids, 'c', 'ArrowDown')).toBe('c');
    expect(step(ids, 'b', 'ArrowUp')).toBe('a');
    expect(step(ids, 'a', 'ArrowUp')).toBe('a');
    expect(step(ids, 'a', 'End')).toBe('c');
    expect(step(ids, 'c', 'Home')).toBe('a');
    expect(step(ids, 'gone', 'ArrowDown')).toBe('a');
    expect(step([], 'a', 'ArrowDown')).toBeNull();
  });
});

describe('labels', () => {
  it('picks 로 or 으로 by the last syllable', () => {
    expect(toward('완료')).toBe('완료로');
    expect(toward('수행')).toBe('수행으로');
    expect(toward('초안')).toBe('초안으로');
    expect(toward('결과 검토')).toBe('결과 검토로');
    expect(toward('수행 준비')).toBe('수행 준비로');
    expect(toward('출발')).toBe('출발로');
    expect(toward('QA')).toBe('QA(으)로');
  });

  it('names a move by where it ends and the stages it passes', () => {
    const skip: Move = { target: 'result_review', path: ['result_review', 'done'], allowed: true, reason: null, needs_note: false };
    expect(moveParts(skip, 'in_progress')).toEqual({ label: '완료로 옮기기', note: '결과 검토 건너뜀' });
    expect(moveLabel(skip, 'in_progress')).toBe('완료로 옮기기 (결과 검토 건너뜀)');
    expect(moveLabel({ ...skip, target: 'in_progress', path: ['in_progress'] }, 'ready')).toBe('수행으로 옮기기');
    expect(moveLabel({ ...skip, target: 'in_progress', path: ['in_progress'] }, 'result_review')).toBe('돌려보내기 (수행으로)');
    expect(moveLabel({ ...skip, target: 'plan_review', path: ['plan_review', 'ready'] }, 'in_progress')).toBe(
      '다시 계획하기 (계획 검토 건너뛰고 수행 준비로)',
    );
    expect(moveLabel({ ...skip, target: 'plan_review', path: ['plan_review', 'ready'] }, 'draft')).toBe('수행 준비로 옮기기 (계획 검토 건너뜀)');
    expect(moveLabel({ ...skip, target: 'cancelled', path: ['cancelled'] }, 'ready')).toBe('취소하기');
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

describe('work list', () => {
  const blocked = item({
    id: 'wrk_b',
    external_ref: 'TSK-021',
    title: 'Temporal을 배운다',
    stage: 'in_progress',
    wait_reasons: ['waiting_other'],
    wait_note: '컨테이너가 안 뜬다',
    project: project(),
    next_action: '재시작 후 확인',
  });
  const plain = item({ id: 'wrk_c', external_ref: 'TSK-026', title: '도메인을 배운다', next_action: '자료를 나란히 놓는다', project: project() });

  it('groups by who holds the work and shows an empty group with a line that says so', () => {
    const html = list({ kind: 'rows' }, [blocked, plain]);
    expect(html).toMatch(/기다림 <span class="n num">1<\/span>/);
    expect(html).toMatch(/AI 세션 <span class="n num">0<\/span>/);
    expect(html).toMatch(/사람 <span class="n num">1<\/span>/);
    expect(html).not.toContain('끝남');
    expect(html).toContain('AI 세션이 든 작업이 없다');
    expect(html).toContain('href="#sessions"');
    expect(list({ kind: 'rows' }, [plain], null, true)).toContain('완료하거나 취소한 작업이 없다');
  });

  it('draws a row as glyph, stage, number and title, then the wait reason or the next action', () => {
    const html = list({ kind: 'rows' }, [blocked, plain], 'wrk_b');
    expect(html).toContain('<span class="lrow-state">수행</span><code class="lrow-ref">TSK-021</code><span class="lrow-title">Temporal을 배운다</span>');
    expect(html).toContain('data-stage="in_progress"');
    expect(html).toContain('glyph is-block');
    expect(html).toContain('<span class="lrow-proj">hanumoka-site</span><span class="wrow-why">다른 사정으로 막힘</span>');
    expect(html).toContain('<span class="wrow-note">컨테이너가 안 뜬다</span>');
    // A blocked row says why it waits, not what comes next.
    expect(html).not.toContain('재시작 후 확인');
    expect(html).toContain('<span class="wrow-next">자료를 나란히 놓는다</span>');
  });

  it('marks the selected row and keeps only it in the Tab order', () => {
    const html = list({ kind: 'rows' }, [blocked, plain], 'wrk_c');
    expect(html).toMatch(/data-row="wrk_c" aria-current="true" tabindex="0"/);
    expect(html).toMatch(/data-row="wrk_b" tabindex="-1"/);
    // Nothing selected: the first row takes the Tab stop.
    expect(list({ kind: 'rows' }, [blocked, plain])).toMatch(/data-row="wrk_b" tabindex="0"/);
  });

  it('offers the new work button in the list head', () => {
    expect(list({ kind: 'rows' }, [plain])).toMatch(/<button type="button" class="btn btn-primary">.*새 작업<\/button>/);
  });

  it('shows the three empty states: nothing yet, filtered to nothing, and a failed read', () => {
    const unused = list({ kind: 'unused' });
    expect(unused).toContain('아직 가져온 작업이 없다');
    expect(unused).toContain('<code>zm-baton import zm-docs --apply</code>');
    expect(unused).toContain('정본이 zm-docs의 Git 파일이라 여기서는 읽기만 한다');
    expect(unused).toContain('새 작업 만들기');

    const filtered = list({ kind: 'filtered', conditions: ['단계 초안', '막힘만'] });
    expect(filtered).toContain('거른 조건에 맞는 작업이 없다');
    expect(filtered).toContain('걸러 본 조건: 단계 초안, 막힘만');
    expect(filtered).toContain('거르기 풀기');

    const error = list({ kind: 'error', message: 'HTTP 503: database is not reachable' });
    expect(error).toContain('is-error');
    expect(error).toContain('작업 목록을 읽지 못했다');
    expect(error).toContain('HTTP 503: database is not reachable');
    expect(error).toContain('다시 읽기');

    expect(list({ kind: 'no-open' })).toContain('완료·취소 보기');
    expect(list({ kind: 'loading' })).toContain('aria-busy="true"');
  });
});

describe('filter chips', () => {
  const chips = (over: Partial<Parameters<typeof ChipRow>[0]> = {}) =>
    renderToStaticMarkup(
      <ChipRow
        filters={NO_FILTERS}
        onFilters={() => {}}
        projects={[project()]}
        stages={[{ stage: 'in_progress', label: '수행', count: 8 }]}
        blockedCount={2}
        skipped={['plan_review', 'result_review']}
        onSkipped={() => {}}
        {...over}
      />,
    );

  it('has project, stage, 막힘 and closed chips and no assignee or team chips', () => {
    const html = chips();
    expect(html).toContain('aria-label="작업 거르기"');
    expect(html).toContain('<option value="prj_a">hanumoka-site (2)</option>');
    expect(html).toContain('<option value="in_progress">수행 (8)</option>');
    expect(html).toMatch(/막힘만 <span class="n num">2<\/span>/);
    expect(html).toContain('완료·취소 보기');
    expect(html).not.toContain('담당자');
    expect(html).not.toContain('팀');
  });

  it('keeps the review stage setting at the end of the row, saying what is set', () => {
    const html = chips();
    // HTML attribute names ignore case; React writes popoverTarget, the browser reads popovertarget.
    expect(html).toMatch(/class="chip wk-review" popovertarget="([^"]+)"/i);
    const target = html.match(/popovertarget="([^"]+)"/i)![1];
    expect(html).toContain(`id="${target}" popover="auto"`);
    expect(html).toContain('popover="auto"');
    expect(html).toContain('검토 단계 건너뜀');
    expect(reviewSummary([])).toBe('검토 단계 모두 씀');
    // Not read yet (the server did not answer): claim nothing and do not open.
    expect(chips({ skipped: null })).toMatch(/class="chip wk-review" popoverTarget="[^"]+" disabled="">.*검토 단계 읽는 중/);
    expect(reviewSummary(['result_review'])).toBe('계획 검토만 씀');
    expect(reviewSummary(['plan_review'])).toBe('결과 검토만 씀');
  });
});

describe('work detail', () => {
  const gitCopy = detail(
    {
      read_only_reason: '정본이 Git 파일(registry/tasks.json)이다. 원본에서 고치고 다시 가져온다',
      git_ref: { path: 'registry/tasks.json', commit: '13923a34cdacb652' },
      source_ref: 'docs/research/x.md',
      moves: [
        { target: 'in_progress', path: ['in_progress'], allowed: false, reason: '정본이 Git 파일(registry/tasks.json)이다. 원본에서 고치고 다시 가져온다', needs_note: false },
        { target: 'cancelled', path: ['cancelled'], allowed: false, reason: '정본이 Git 파일(registry/tasks.json)이다. 원본에서 고치고 다시 가져온다', needs_note: false },
      ],
      history: [{ action: 'work_item.imported', actor_kind: 'person', actor_id: 'usr_owner', details: { ref: 'TSK-001' }, at: '2026-10-13T04:52:10Z' }],
    },
    { source_of_truth: 'git', external_ref: 'TSK-001', wait_reasons: ['waiting_other'], wait_note: '컨테이너가 안 뜬다', project: project() },
  );

  it('says 읽기만 한다 in the head of a Git copy and locks every change with the reason beside the buttons', () => {
    const html = renderToStaticMarkup(<WorkDetail item={gitCopy.item} detail={gitCopy} projects={[]} skipped={[]} actions={actions} error={null} />);
    expect(html).toContain('<strong>읽기만 한다</strong>');
    expect(html).toContain('<code>registry/tasks.json</code>');
    expect(html).toContain('읽은 커밋 <code>13923a34cd</code>');
    expect(html).toMatch(/<button type="button" class="btn btn-primary" disabled="" aria-describedby="([^"]+)">수행으로 옮기기<\/button>/);
    const described = html.match(/aria-describedby="([^"]+)">수행으로 옮기기/)![1];
    expect(html).toContain(`id="${described}"`);
    expect(html).toContain('단추가 모두 잠겼다. 정본이 Git 파일(registry/tasks.json)이다. 원본에서 고치고 다시 가져온다');
    expect(html).toContain('컨테이너가 안 뜬다');
    expect(html).toContain('<code>docs/research/x.md</code>');
    expect(html).toContain('가져옴 (TSK-001)');
    expect(html).not.toContain('내용 고치기');
    expect(html).not.toContain('막힘 표시');
    expect(html).not.toContain('떼기');
  });

  it('offers editing and wait reasons on work made here, and shows the last refusal', () => {
    const d = detail(
      { moves: [{ target: 'in_progress', path: ['in_progress'], allowed: true, reason: null, needs_note: false }] },
      { wait_reasons: ['waiting_other'], wait_note: '회신 대기' },
    );
    const html = renderToStaticMarkup(
      <WorkDetail item={d.item} detail={d} projects={[]} skipped={['plan_review']} actions={actions} error="HTTP 409: the work item changed" />,
    );
    expect(html).not.toContain('읽기만 한다');
    expect(html).toContain('내용 고치기');
    expect(html).toContain('막힘 표시');
    expect(html).toContain('선행 작업 대기 표시');
    expect(html).toContain('떼기');
    expect(html).toContain('role="alert"');
    expect(html).toContain('HTTP 409: the work item changed');
    expect(html).toMatch(/<button type="button" class="btn btn-primary">수행으로 옮기기<\/button>/);
  });

  it('draws the list item at once while its detail loads', () => {
    const shown = item({ id: 'wrk_new', title: '방금 고른 작업', external_ref: 'TSK-030' });
    const html = renderToStaticMarkup(<WorkDetail item={shown} detail={gitCopy} projects={[]} skipped={[]} actions={actions} error={null} />);
    expect(html).toContain('방금 고른 작업');
    expect(html).toContain('<code>TSK-030</code>');
    expect(html).toContain('aria-busy="true"');
    expect(html).not.toContain('읽기만 한다');
    expect(html).not.toContain('다음 단계');
  });

  it('marks the current stage, says 막힘 and 건너뜀, and keeps 취소 apart', () => {
    const html = renderToStaticMarkup(<StagePath stage="in_progress" blocked skipped={['plan_review', 'result_review']} />);
    expect(html).toMatch(/<li class="wd-t is-now" aria-current="step">.*glyph is-block.*수행<\/span><small>막힘<\/small><\/li>/);
    expect(html).toMatch(/<li class="wd-t is-skip">.*계획 검토<\/span><small>건너뜀<\/small><\/li>/);
    expect(html).toMatch(/<li class="wd-t is-past">.*수행 준비<\/span><\/li>/);
    expect(html).toMatch(/<li class="wd-t is-next">.*완료<\/span><\/li>/);
    expect(html).toContain('wd-t wd-off is-off');
    expect(renderToStaticMarkup(<StagePath stage="cancelled" blocked={false} skipped={[]} />)).toContain('wd-t wd-off is-now" aria-current="step"');
  });

  it('asks for a title before a new work item can be made, and says so', () => {
    const html = renderToStaticMarkup(<NewWork projects={[project()]} defaultProject="" onCreate={() => {}} onCancel={() => {}} />);
    expect(html).toMatch(/class="btn btn-primary" disabled="" aria-describedby="[^"]+">작업 만들기/);
    expect(html).toContain('제목을 쓰면 만들 수 있다.');
    expect(html).toContain('정본이 zm-baton이라 여기서 고치고 단계를 넘긴다');
  });
});

describe('work screen', () => {
  it('lays out the list and the detail with no description paragraph in its head', () => {
    const html = renderToStaticMarkup(<Work onError={() => {}} onRead={() => {}} chipHost={null} query="" onClearQuery={() => {}} />);
    expect(html).toMatch(/^<main class="panes wk"><h1 class="sr">작업<\/h1><section class="pane-list"/);
    expect(html).toContain('<section class="pane-detail" aria-label="작업 상세">');
    expect(html).toContain('aria-busy="true"');
    expect(html).not.toContain('<header>');
    // The search box moved to the command line.
    expect(html).not.toContain('type="search"');
  });
});
