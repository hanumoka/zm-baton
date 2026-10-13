import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { DecisionChipRow } from './decisions/DecisionChips';
import { DecisionDetailView } from './decisions/DecisionDetailView';
import { DecisionList } from './decisions/DecisionList';
import { Decisions } from './decisions/Decisions';
import {
  conditions,
  filterDecisions,
  groupByStatus,
  jumpTo,
  listState,
  mentioning,
  NO_FILTERS,
  otherSections,
  pastShown,
  previewText,
  readView,
  rowOrder,
  statusOptions,
  workHref,
  writeView,
  type DecisionsView,
  type ListState,
} from './decisions/decisionView';
import { DecisionMark } from './icons/DecisionMark';
import type { Decision, DecisionDetail, Project, WorkItem } from './workApi';

const decision = (over: Partial<Decision> = {}): Decision => ({
  id: 'dcs_a',
  external_ref: 'DEC-0106',
  question: '1단계는 기능을 먼저 쓸 만하게 만든다',
  status: 'accepted',
  decided_on: '2026-10-12',
  projects: [{ id: 'prj_a', external_ref: 'PRJ-009', name: 'zm-baton' }],
  choice_preview: '2026-10-12 소유자가 정했다.',
  supersedes: null,
  superseded_by: null,
  updated_at: '2026-10-13T00:00:00Z',
  ...over,
});

const work = (over: Partial<WorkItem> = {}): WorkItem => ({
  id: 'wrk_a',
  title: '작업 하나',
  stage: 'ready',
  wait_reasons: [],
  generation: 0,
  created_at: '2026-10-13T00:00:00Z',
  version: 0,
  external_ref: 'TSK-006',
  project: null,
  source_of_truth: 'git',
  executor_kind: 'human',
  next_action: null,
  wait_note: null,
  opened_on: null,
  due_on: null,
  closed_at: null,
  updated_at: '2026-10-13T00:00:00Z',
  ...over,
});

const project = (over: Partial<Project> = {}): Project => ({ id: 'prj_a', external_ref: 'PRJ-009', name: 'zm-baton', open_work_items: 3, ...over });

// A replaced decision and the one that replaced it, as zm-docs has them (DEC-0047 and DEC-0077).
const old = decision({
  id: 'dcs_old',
  external_ref: 'DEC-0047',
  question: 'AI 계정 자동 전환에 한도 도달을 포함한다',
  status: 'superseded',
  decided_on: '2026-10-04',
  superseded_by: { id: 'dcs_new', external_ref: 'DEC-0077', question: '한도 도달 때 같은 공급자의 개인 구독으로 넘기지 않는다' },
});
const replacing = decision({
  id: 'dcs_new',
  external_ref: 'DEC-0077',
  question: '한도 도달 때 같은 공급자의 개인 구독으로 넘기지 않는다',
  decided_on: '2026-10-05',
  supersedes: { id: 'dcs_old', external_ref: 'DEC-0047', question: 'AI 계정 자동 전환에 한도 도달을 포함한다' },
});
const proposed = decision({ id: 'dcs_p', external_ref: 'DEC-0110', question: '정할 것', status: 'proposed', decided_on: null, choice_preview: null });
const rejected = decision({ id: 'dcs_r', external_ref: 'DEC-0090', question: '기각된 것', status: 'rejected', decided_on: '2026-10-08' });
const all = [decision(), old, replacing, proposed, rejected, decision({ id: 'dcs_b', external_ref: 'DEC-0105', decided_on: '2026-10-10' })];

const view = (over: Partial<DecisionsView> = {}): DecisionsView => ({ ...NO_FILTERS, selected: null, ...over });

function list(state: ListState, items: Decision[] = [], selected: string | null = null, showPast = false) {
  return renderToStaticMarkup(
    <DecisionList
      state={state}
      groups={groupByStatus(items, showPast)}
      head={{ base: '결정', count: items.length, narrowed: false }}
      selected={selected}
      onSelect={() => {}}
      onPick={() => {}}
      onOpen={() => {}}
      onShowPast={() => {}}
      onClearFilters={() => {}}
      onReload={() => {}}
    />,
  );
}

describe('status groups', () => {
  it('puts proposed under 정할 것, accepted under 지금 유효 and superseded and rejected under 지난 것, each newest first', () => {
    const groups = groupByStatus(all, true);
    expect(groups.map((g) => [g.label, g.items.map((d) => d.external_ref)])).toEqual([
      ['정할 것', ['DEC-0110']],
      ['지금 유효', ['DEC-0106', 'DEC-0105', 'DEC-0077']],
      ['지난 것', ['DEC-0090', 'DEC-0047']],
    ]);
  });

  it('keeps every group, even empty, and folds 지난 것 until asked, so its rows take no arrow-key stop', () => {
    expect(groupByStatus([], false).map((g) => [g.bucket, g.items.length, g.folded])).toEqual([
      ['open', 0, false],
      ['current', 0, false],
      ['past', 0, true],
    ]);
    expect(rowOrder(groupByStatus(all, false))).toEqual(['dcs_p', 'dcs_a', 'dcs_b', 'dcs_new']);
    expect(rowOrder(groupByStatus(all, true))).toEqual(['dcs_p', 'dcs_a', 'dcs_b', 'dcs_new', 'dcs_r', 'dcs_old']);
  });

  it('orders the same day by number, the higher first, and an undated decision last', () => {
    const sameDay = [decision({ id: 'x', external_ref: 'DEC-0101' }), decision({ id: 'y', external_ref: 'DEC-0102' }), decision({ id: 'z', decided_on: null })];
    expect(groupByStatus(sameDay, false)[1].items.map((d) => d.id)).toEqual(['y', 'x', 'z']);
  });

  it('draws the group heads with counts, an empty group with its line, and folded 지난 것 with a button', () => {
    const html = list({ kind: 'rows' }, [decision(), old]);
    expect(html).toMatch(/정할 것 <span class="n num">0<\/span>/);
    expect(html).toContain('제안 상태로 남은 결정이 없다');
    expect(html).toMatch(/지금 유효 <span class="n num">1<\/span>/);
    expect(html).toMatch(/지난 것 <span class="n num">1<\/span>/);
    expect(html).toContain('대체되거나 기각된 결정은 접어 두었다.');
    expect(html).toContain('지난 것 1건 보기');
    expect(html).not.toContain('data-row="dcs_old"');
    expect(list({ kind: 'rows' }, [decision(), old], null, true)).toContain('data-row="dcs_old"');
  });
});

describe('rows', () => {
  it('draws the status mark and name, the number and the question, then the date, projects and decision', () => {
    const html = list({ kind: 'rows' }, [decision()], 'dcs_a');
    expect(html).toContain('data-status="accepted"');
    expect(html).toContain(
      '<span class="lrow-state">채택</span><code class="lrow-ref">DEC-0106</code><span class="lrow-title">1단계는 기능을 먼저 쓸 만하게 만든다</span>',
    );
    expect(html).toContain(
      '<span class="dc-date num">2026-10-12</span><span class="lrow-proj">zm-baton</span><span class="dc-choice">소유자가 정했다.</span>',
    );
    expect(html).toMatch(/data-row="dcs_a" aria-current="true" tabindex="0"/);
  });

  it('says which decision replaced a superseded one, and 날짜 없음 for an undated one', () => {
    const html = list({ kind: 'rows' }, [old, proposed], null, true);
    expect(html).toContain('<span class="lrow-state">대체됨</span>');
    expect(html).toContain('<span class="dc-by">대체한 결정 DEC-0077</span>');
    expect(html).toContain('<span class="dc-date num">날짜 없음</span>');
  });

  it('tells the four statuses apart by shape, not colour alone', () => {
    const shapes = (['proposed', 'accepted', 'superseded', 'rejected'] as const).map((s) => renderToStaticMarkup(<DecisionMark status={s} />));
    expect(shapes[0]).toContain('<circle class="dot"');
    expect(shapes[1]).toContain('class="fill"');
    expect(shapes[2]).toContain('d="M5 8h6"');
    expect(shapes[3]).toContain('stroke-dasharray');
    expect(new Set(shapes.map((h) => h.replace(/ data-status="[^"]+"| is-[a-z]+/g, ''))).size).toBe(4);
  });
});

describe('filters', () => {
  it('filters by status on the client and counts every status for the chip', () => {
    expect(filterDecisions(all, 'superseded').map((d) => d.id)).toEqual(['dcs_old']);
    expect(filterDecisions(all, '')).toHaveLength(all.length);
    expect(statusOptions(all).map((o) => [o.label, o.count])).toEqual([
      ['제안', 1],
      ['채택', 3],
      ['기각', 1],
      ['대체됨', 1],
    ]);
  });

  it('unfolds 지난 것 when the status chip asks for a past status', () => {
    expect(pastShown({ status: '', showPast: false })).toBe(false);
    expect(pastShown({ status: 'superseded', showPast: false })).toBe(true);
    expect(pastShown({ status: 'rejected', showPast: false })).toBe(true);
    expect(pastShown({ status: 'accepted', showPast: true })).toBe(true);
  });

  it('draws project, status with counts and 지난 것 chips, and no search box of its own', () => {
    const html = renderToStaticMarkup(
      <DecisionChipRow filters={NO_FILTERS} onFilters={() => {}} projects={[project()]} statuses={statusOptions(all)} pastCount={2} />,
    );
    expect(html).toContain('aria-label="결정 거르기"');
    expect(html).toContain('<option value="prj_a">zm-baton</option>');
    expect(html).toContain('<option value="superseded">대체됨 (1)</option>');
    expect(html).toContain('<option value="rejected">기각 (1)</option>');
    expect(html).toMatch(/지난 것 보기 <span class="n num">2<\/span>/);
    expect(html).not.toContain('<input type="search"');
    expect(html).not.toContain('placeholder');
  });

  it('says what the list is filtered by', () => {
    expect(conditions({ projectId: 'prj_a', status: 'superseded', showPast: false }, ' 계정 ', [project()])).toEqual([
      '프로젝트 zm-baton',
      '상태 대체됨',
      '찾는 말 「계정」',
    ]);
  });
});

describe('empty states', () => {
  const base = { items: [] as Decision[] | null, visible: 0, error: null as string | null, filters: NO_FILTERS, query: '', projects: [] };

  it('tells loading, a failed read, nothing imported, filtered to nothing and rows apart', () => {
    expect(listState({ ...base, items: null })).toEqual({ kind: 'loading' });
    expect(listState({ ...base, items: null, error: 'HTTP 503' })).toEqual({ kind: 'error', message: 'HTTP 503' });
    expect(listState(base)).toEqual({ kind: 'unused' });
    expect(listState({ ...base, query: '계정' })).toEqual({ kind: 'filtered', conditions: ['찾는 말 「계정」'] });
    expect(listState({ ...base, items: [decision()], filters: { ...NO_FILTERS, status: 'proposed' } }).kind).toBe('filtered');
    expect(listState({ ...base, items: [old], visible: 1 })).toEqual({ kind: 'rows' });
    // A failure after a good read keeps the rows; the problem list at the top says what failed.
    expect(listState({ ...base, items: [decision()], visible: 1, error: 'HTTP 503' })).toEqual({ kind: 'rows' });
  });

  it('draws the three with the work screen parts: nothing imported, filtered to nothing, a failed read', () => {
    const unused = list({ kind: 'unused' });
    expect(unused).toContain('empty-state is-unused');
    expect(unused).toContain('아직 가져온 결정이 없다');
    expect(unused).toContain('<code>zm-baton import zm-docs --apply</code>');
    expect(unused).toContain('정본이 Git 파일이라 여기서는 읽기만 하고');

    const filtered = list({ kind: 'filtered', conditions: ['상태 제안'] });
    expect(filtered).toContain('empty-state is-empty');
    expect(filtered).toContain('거른 조건에 맞는 결정이 없다');
    expect(filtered).toContain('걸러 본 조건: 상태 제안');
    expect(filtered).toContain('거르기 풀기');

    const error = list({ kind: 'error', message: 'HTTP 503: database is not reachable' });
    expect(error).toContain('empty-state is-error');
    expect(error).toContain('결정 목록을 읽지 못했다');
    expect(error).toContain('HTTP 503: database is not reachable');
    expect(error).toContain('다시 읽기');

    expect(list({ kind: 'loading' })).toContain('aria-busy="true"');
  });
});

describe('address', () => {
  it('keeps the filters and the selected decision in the address and drops what it does not know', () => {
    const v = view({ projectId: 'prj_a', status: 'superseded', showPast: true, selected: 'dcs_old' });
    expect(writeView(v)).toBe('#decisions?project=prj_a&status=superseded&past=1&item=dcs_old');
    expect(readView(writeView(v))).toEqual(v);
    expect(writeView(view())).toBe('#decisions');
    expect(readView('#decisions')).toEqual(view());
    expect(readView('#decisions?status=maybe&item=').status).toBe('');
    expect(readView('#decisions?status=maybe&item=').selected).toBeNull();
  });
});

describe('supersession links', () => {
  it('selects the other decision, clears a status chip that hides it and unfolds 지난 것 for a past one', () => {
    expect(jumpTo(view({ selected: 'dcs_new' }), 'dcs_old', all)).toEqual(view({ selected: 'dcs_old', showPast: true }));
    expect(jumpTo(view({ status: 'accepted', selected: 'dcs_new' }), 'dcs_old', all)).toEqual(view({ selected: 'dcs_old', showPast: true }));
    expect(jumpTo(view({ status: 'superseded', selected: 'dcs_old' }), 'dcs_new', all)).toEqual(view({ selected: 'dcs_new' }));
    // Not in the list (another project or other search words): shown in the detail only, filters kept.
    expect(jumpTo(view({ projectId: 'prj_b' }), 'dcs_far', all)).toEqual(view({ projectId: 'prj_b', selected: 'dcs_far' }));
  });

  const detail = (d: Decision): DecisionDetail => ({
    decision: d,
    choice: '같은 공급자의 다른 개인 구독으로는 자동으로 넘기지 않는다.',
    sections: [
      { heading: '맥락', body: 'DEC-0047은 전환 사유를 셋으로 정했다.' },
      { heading: '결정', body: '같은 공급자의 다른 개인 구독으로는 자동으로 넘기지 않는다.' },
      { heading: '재검토 조건', body: '' },
    ],
    source_of_truth: 'git',
    git_ref: { path: 'docs/governance/decisions/DEC-0077-x.md', commit: '29d648af389546fc' },
  });

  it('shows both directions as buttons that name the other decision', () => {
    const newer = renderToStaticMarkup(<DecisionDetailView decision={replacing} detail={detail(replacing)} related={[]} onJump={() => {}} />);
    expect(newer).toContain('<h3>대체 관계</h3>');
    expect(newer).toContain('이 결정이 대체한 결정');
    expect(newer).toMatch(/<button type="button" class="dc-link" data-jump="dcs_old"><code>DEC-0047<\/code>/);
    expect(newer).not.toContain('이 결정을 대체한 결정');

    const older = renderToStaticMarkup(<DecisionDetailView decision={old} detail={detail(old)} related={[]} onJump={() => {}} />);
    expect(older).toContain('이 결정을 대체한 결정');
    expect(older).toMatch(/data-jump="dcs_new"><code>DEC-0077<\/code>/);
    expect(older).toContain('data-status="superseded"');

    expect(renderToStaticMarkup(<DecisionDetailView decision={decision()} detail={detail(decision())} related={[]} onJump={() => {}} />)).not.toContain(
      '대체 관계',
    );
  });

  it('says 읽기만 한다 with the Git file, puts 고른 안 first and does not repeat the 결정 section', () => {
    const html = renderToStaticMarkup(<DecisionDetailView decision={replacing} detail={detail(replacing)} related={[]} onJump={() => {}} />);
    expect(html).toContain('<strong>읽기만 한다</strong>');
    expect(html).toContain('<code>docs/governance/decisions/DEC-0077-x.md</code>');
    expect(html).toContain('읽은 커밋 <code>29d648af38</code>');
    expect(html.indexOf('<h3>고른 안</h3>')).toBeLessThan(html.indexOf('<h3>대체 관계</h3>'));
    expect(html.indexOf('<h3>대체 관계</h3>')).toBeLessThan(html.indexOf('<h3>맥락</h3>'));
    expect(html).not.toContain('<h3>결정</h3>');
    expect(html).toContain('<h3>재검토 조건</h3><p class="dt-none">비어 있다.</p>');
    expect(html).toContain('<dt>결정일</dt><dd class="num">2026-10-05</dd>');
    expect(html).toContain('zm-baton<code>PRJ-009</code>');
    // No work names the number: one column, no aside.
    expect(html).toContain('class="dt-grid is-solo"');
    expect(html).not.toContain('<aside');
  });

  it('links work that writes the number to the work screen, in an aside', () => {
    const tasks = [work({ id: 'wrk_1', title: 'DEC-0077 후속을 정리한다' }), work({ id: 'wrk_2', stage: 'done', external_ref: 'TSK-003', next_action: 'DEC-0077 반영' })];
    const html = renderToStaticMarkup(<DecisionDetailView decision={replacing} detail={detail(replacing)} related={tasks} onJump={() => {}} />);
    expect(html).toContain('class="dt-grid"');
    expect(html).toContain('이 번호를 적은 작업 <span class="num">2</span>');
    expect(html).toContain('href="#work?item=wrk_1"');
    expect(html).toContain('href="#work?closed=1&amp;item=wrk_2"');
    expect(html).toContain('<span class="lrow-state">완료</span><code>TSK-003</code>');
  });

  it('draws the list decision at once while its detail loads', () => {
    const html = renderToStaticMarkup(<DecisionDetailView decision={old} detail={detail(replacing)} related={null} onJump={() => {}} />);
    expect(html).toContain('AI 계정 자동 전환에 한도 도달을 포함한다');
    expect(html).toContain('<code>DEC-0047</code>');
    expect(html).toContain('aria-busy="true"');
    expect(html).not.toContain('읽기만 한다');
  });
});

describe('detail parts', () => {
  it('leaves out only the section the decision came from', () => {
    expect(otherSections({ choice: '한다.', sections: [{ heading: '맥락', body: '배경' }, { heading: '판단 기준과 결정', body: '한다.\n' }] })).toEqual([
      { heading: '맥락', body: '배경' },
    ]);
    expect(otherSections({ choice: null, sections: [{ heading: '맥락', body: '배경' }] })).toHaveLength(1);
  });

  it('counts only whole decision numbers as a mention', () => {
    const items = [work({ id: 'a', title: 'DEC-0015 따라 고친다' }), work({ id: 'b', next_action: 'DEC-00150 참고' }), work({ id: 'c', next_action: '(DEC-0015)' })];
    expect(mentioning(items, 'DEC-0015').map((w) => w.id)).toEqual(['a', 'c']);
  });

  it('leaves out of the preview only the same day the row shows before it', () => {
    expect(previewText({ decided_on: '2026-10-12', choice_preview: '2026-10-12 소유자가 정했다.' })).toBe('소유자가 정했다.');
    expect(previewText({ decided_on: '2026-10-12', choice_preview: '2026-10-11 소유자가 정했다.' })).toBe('2026-10-11 소유자가 정했다.');
    expect(previewText({ decided_on: null, choice_preview: '한다.' })).toBe('한다.');
    expect(previewText({ decided_on: '2026-10-12', choice_preview: '2026-10-12 ' })).toBeNull();
    expect(previewText({ decided_on: '2026-10-12', choice_preview: null })).toBeNull();
  });

  it('opens closed work with closed work shown', () => {
    expect(workHref({ id: 'w', stage: 'in_progress' })).toBe('#work?item=w');
    expect(workHref({ id: 'w', stage: 'cancelled' })).toBe('#work?closed=1&item=w');
  });
});

describe('decisions screen', () => {
  it('lays out the list and the detail with no description paragraph and no search box in its head', () => {
    const html = renderToStaticMarkup(<Decisions onError={() => {}} onRead={() => {}} chipHost={null} query="" onClearQuery={() => {}} />);
    expect(html).toMatch(/^<main class="panes dc"><h1 class="sr">결정<\/h1><section class="pane-list"/);
    expect(html).toContain('상태로 묶음');
    expect(html).toContain('<section class="pane-detail" aria-label="결정 상세">');
    expect(html).toContain('aria-busy="true"');
    expect(html).not.toContain('<header>');
    expect(html).not.toContain('읽기만 한다.</p>');
    expect(html).not.toContain('type="search"');
  });
});
