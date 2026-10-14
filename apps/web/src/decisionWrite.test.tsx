import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import {
  changedFields,
  decisionHistoryLine,
  draftFields,
  draftFrom,
  draftProblem,
  emptyDraft,
  nextKey,
  supersedeDraft,
  type DecisionDraft,
} from './decisions/decisionDraft';
import { DecisionDetailView, type DecisionActions } from './decisions/DecisionDetailView';
import { DecisionForm } from './decisions/DecisionForm';
import { DecisionList } from './decisions/DecisionList';
import { groupByStatus } from './decisions/decisionView';
import { decisionHref, LinkedDecisionList } from './decisions/LinkedDecisions';
import type { Decision, DecisionAction, DecisionDetail } from './workApi';

const made = (over: Partial<Decision> = {}): Decision => ({
  id: 'dcs_m',
  external_ref: null,
  question: '문서 화면을 어디에 둘까',
  status: 'proposed',
  decided_on: null,
  projects: [{ id: 'prj_a', external_ref: 'PRJ-009', name: 'zm-baton' }],
  choice_preview: null,
  supersedes: null,
  superseded_by: null,
  updated_at: '2026-10-14T00:00:00Z',
  number: 3,
  ref: 'DCS-0003',
  source_of_truth: 'product',
  version: 2,
  ...over,
});

const act = (name: DecisionAction['name'], allowed: boolean, reason: string | null = null): DecisionAction => ({ name, allowed, reason });

const detailOf = (d: Decision, over: Partial<DecisionDetail> = {}): DecisionDetail => ({
  decision: d,
  choice: '사이드바에 둔다.',
  sections: [],
  source_of_truth: 'product',
  git_ref: null,
  context: '화면이 늘고 있다.',
  options: [
    { key: 'A', title: '사이드바', body: '다른 화면과 같은 자리' },
    { key: 'C', title: '결정 화면 안', body: null },
  ],
  chosen_option: 'A',
  rationale: '같은 문법이다.',
  review_conditions: null,
  roles: { driver: 'usr_owner', approver: 'usr_owner', contributors: ['Codex'], informed: [] },
  approver: 'usr_owner',
  work_items: [{ id: 'wrk_a', external_ref: null, title: '문서 화면 만들기', stage: 'in_progress', wait_reasons: [] }],
  approvals: [],
  read_only_reason: null,
  actions: [act('edit', true), act('accept', true), act('reject', true), act('supersede', false, '제안 상태의 결정은 대체하지 않고 고친다')],
  history: [{ action: 'decision.proposed', actor_kind: 'person', actor_id: 'usr_owner', details: { ref: 'DCS-0003' }, at: '2026-10-14T00:00:00Z' }],
  ...over,
});

const noop: DecisionActions = { edit: () => {}, accept: async () => {}, reject: async () => {}, supersede: () => {} };

const detailHtml = (d: DecisionDetail) =>
  renderToStaticMarkup(<DecisionDetailView decision={d.decision} detail={d} related={null} onJump={() => {}} actions={noop} />);

describe('the decision form as data', () => {
  const draft = (over: Partial<DecisionDraft> = {}): DecisionDraft => ({ ...emptyDraft(), question: ' 질문 ', ...over });

  it('sends trimmed text, null for an empty field, only named options and a chosen key that is one of them', () => {
    const fields = draftFields(
      draft({
        context: '  ',
        options: [
          { key: 'A', title: ' 하나 ', body: '' },
          { key: 'B', title: '', body: '' },
        ],
        chosen: 'B',
        contributors: 'Codex, Claude,Codex\n',
        projectIds: ['prj_a'],
      }),
    );
    expect(fields.question).toBe('질문');
    expect(fields.context).toBeNull();
    expect(fields.options).toEqual([{ key: 'A', title: '하나', body: null }]);
    expect(fields.chosen_option).toBeNull();
    expect(fields.roles).toEqual({ driver: 'usr_owner', contributors: ['Codex', 'Claude'], informed: [] });
    expect(fields.project_ids).toEqual(['prj_a']);
    expect(fields.supersedes_id).toBeNull();
  });

  it('sends only what changed on a change', () => {
    const before = draftFrom(detailOf(made()));
    expect(changedFields(before, before)).toEqual({});
    const after = { ...before, question: '다시 쓴 질문', chosen: 'C' };
    expect(changedFields(before, after)).toEqual({ question: '다시 쓴 질문', chosen_option: 'C' });
    expect(changedFields(before, { ...before, workIds: [] })).toEqual({ work_item_ids: [] });
  });

  it('starts a replacement with the same question, projects and work, naming the decision it replaces', () => {
    const accepted = detailOf(made({ status: 'accepted' }));
    const d = supersedeDraft(accepted);
    expect(d.question).toBe('문서 화면을 어디에 둘까');
    expect(d.projectIds).toEqual(['prj_a']);
    expect(d.workIds).toEqual(['wrk_a']);
    expect(d.supersedes).toEqual({ id: 'dcs_m', external_ref: null, question: '문서 화면을 어디에 둘까', ref: 'DCS-0003' });
    expect(draftFields(d).supersedes_id).toBe('dcs_m');
    expect(d.options).toEqual([]);
  });

  it('cannot be sent without a question, or with an option that has a body and no name', () => {
    expect(draftProblem(emptyDraft())).toBe('질문을 쓰면 보낼 수 있다.');
    expect(draftProblem(draft({ options: [{ key: 'A', title: '', body: '설명만' }] }))).toBe('설명을 적은 선택지에는 이름도 쓴다.');
    expect(draftProblem(draft())).toBeNull();
  });

  it('gives a new option the next letter nobody took, so a removed one does not come back', () => {
    expect(nextKey([])).toBe('A');
    expect(nextKey(['A', 'B'])).toBe('C');
    expect(nextKey(['A', 'C'])).toBe('B');
    expect(nextKey('ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split(''))).toBe('AA');
  });

  it('writes one line per history entry', () => {
    const line = (action: string, details: Record<string, unknown>) =>
      decisionHistoryLine({ action, actor_kind: 'person', actor_id: 'usr_owner', details, at: '2026-10-14T00:00:00Z' });
    expect(line('decision.proposed', { ref: 'DCS-0003' })).toBe('제안함 (DCS-0003)');
    expect(line('decision.updated', { fields: ['question', 'chosen_option', 'work_item_ids'] })).toBe('고침: 질문, 고른 안, 관련 작업');
    expect(line('decision.accepted', { supersedes: 'DCS-0001', note: '좋다' })).toBe('채택함, DCS-0001을(를) 대체 — 좋다');
    expect(line('decision.rejected', { note: null })).toBe('기각함');
    expect(line('decision.superseded', { by: 'DCS-0004' })).toBe('DCS-0004이(가) 대체함');
  });
});

describe('a decision made here', () => {
  it('puts accept first, then reject and edit, and says who approves', () => {
    const html = detailHtml(detailOf(made()));
    expect(html).toContain('<h3>할 수 있는 일</h3>');
    expect(html).toMatch(/<button type="button" class="btn btn-primary">채택하기<\/button><button type="button" class="btn">기각하기<\/button><button type="button" class="btn">내용 고치기<\/button>/);
    expect(html).toContain('승인하는 사람 <code>usr_owner</code>');
    expect(html).toContain('<dt>정본</dt><dd>여기서 만든 결정</dd>');
    expect(html).not.toContain('읽기만 한다');
    expect(html).toContain('<code>DCS-0003</code>');
    expect(html).toContain('<dd class="num">아직</dd>');
  });

  it('keeps a refused action visible with the server reason next to it', () => {
    const html = detailHtml(detailOf(made(), { actions: [act('edit', true), act('accept', false, '고른 안이나 결정 내용을 적어야 채택한다'), act('reject', true), act('supersede', false)] }));
    expect(html).toMatch(/<button type="button" class="btn btn-primary" disabled="" aria-describedby="[^"]+">채택하기<\/button>/);
    expect(html).toContain('고른 안이나 결정 내용을 적어야 채택한다');
  });

  it('shows the chosen option first, every option with the chosen one marked, the roles and the linked work', () => {
    const html = detailHtml(detailOf(made()));
    expect(html).toContain('<p class="dc-chosen"><code>A</code><strong>사이드바</strong></p>');
    expect(html).toMatch(/<li class="is-chosen"><p class="dc-opt-t"><code>A<\/code><span>사이드바<\/span><small>고른 안<\/small><\/p>/);
    expect(html).toContain('<dt>기여</dt><dd>Codex</dd>');
    expect(html).toContain('<dt>알림</dt><dd>없음</dd>');
    expect(html).toContain('href="#work?item=wrk_a"');
    expect(html).toContain('제안함 (DCS-0003)');
    expect(html).toContain('아직 없다. 승인자가 채택하거나 기각하면 남는다.');
    // 고른 안 before 대체 관계 and the options: the reading order of a Git copy.
    expect(html.indexOf('<h3>고른 안</h3>')).toBeLessThan(html.indexOf('<h3>선택지</h3>'));
  });

  it('offers to replace an accepted decision and records who accepted it', () => {
    const accepted = made({ status: 'accepted', decided_on: '2026-10-14' });
    const html = detailHtml(
      detailOf(accepted, {
        actions: [act('edit', false, '채택한 결정은 고치지 않는다'), act('accept', false), act('reject', false), act('supersede', true)],
        approvals: [
          { id: 'apv_a', decision: 'approved', approver_kind: 'human', approver_id: 'usr_owner', target_version: 2, policy_ref: 'stage1.owner_approves', note: '좋다', decided_at: '2026-10-14T01:00:00Z' },
        ],
      }),
    );
    expect(html).toContain('<button type="button" class="btn">새 결정으로 대체하기</button>');
    expect(html).not.toContain('채택하기</button>');
    expect(html).toContain('채택, <code>usr_owner</code> — 좋다');
  });

  it('says a proposal that names an earlier decision replaces it once accepted', () => {
    const html = detailHtml(detailOf(made({ supersedes: { id: 'dcs_o', external_ref: null, question: '먼저 정한 것', ref: 'DCS-0001' } })));
    expect(html).toContain('채택하면 대체할 결정');
    expect(html).toContain('채택하면 DCS-0001이(가) 대체된다');
  });

  it('draws a Git copy without actions, as before', () => {
    const git = made({ id: 'dcs_g', source_of_truth: 'git', external_ref: 'DEC-0106', ref: 'DEC-0106', status: 'accepted' });
    const html = detailHtml(detailOf(git, { source_of_truth: 'git', git_ref: { path: 'docs/governance/decisions/DEC-0106-x.md' }, actions: [] }));
    expect(html).toContain('읽기만 한다');
    expect(html).not.toContain('할 수 있는 일');
  });
});

describe('the decision form', () => {
  const form = (mode: 'new' | 'edit' | 'supersede', start: DecisionDraft) =>
    renderToStaticMarkup(
      <DecisionForm mode={mode} start={start} projects={[{ id: 'prj_a', external_ref: 'PRJ-009', name: 'zm-baton', open_work_items: 1 }]} work={null} approver="usr_owner" onSubmit={async () => {}} onCancel={() => {}} error={null} />,
    );

  it('names what it does, shows the approver as a fact and locks the send button until there is a question', () => {
    const html = form('new', emptyDraft());
    expect(html).toContain('>새 결정</h2>');
    expect(html).toMatch(/<button type="submit" class="btn btn-primary" disabled="" aria-describedby="[^"]+">결정 제안하기<\/button>/);
    expect(html).toContain('질문을 쓰면 보낼 수 있다.');
    expect(html).toContain('<span>승인(Approver)</span><p><code>usr_owner</code><small>1단계는 소유자가 승인한다</small></p>');
    expect(html).toContain('작업을 읽는 중');
    expect(html).toContain('<legend>선택지</legend>');
  });

  it('shows the decision a replacement names, and the options of a change with the chosen one checked', () => {
    const replacing = form('supersede', supersedeDraft(detailOf(made({ status: 'accepted' }))));
    expect(replacing).toContain('>결정 대체하기</h2>');
    expect(replacing).toContain('채택하면 대체할 결정');
    expect(replacing).toContain('DCS-0003 문서 화면을 어디에 둘까');
    expect(replacing).not.toContain('대체하지 않기');
    expect(replacing).toContain('대체 결정 제안하기');

    const editing = form('edit', draftFrom(detailOf(made())));
    expect(editing).toContain('>결정 고치기</h2>');
    expect(editing).toMatch(/<input type="radio" name="[^"]+" checked=""\/><span class="sr">이 안을 고른다:<\/span><code>A<\/code>/);
    expect(editing).toContain('고친 내용 저장');
  });
});

describe('the list and the work item', () => {
  it('puts 새 결정 in the list head', () => {
    const html = renderToStaticMarkup(
      <DecisionList
        state={{ kind: 'rows' }}
        groups={groupByStatus([made()], false)}
        head={{ base: '결정', count: 1, narrowed: false }}
        selected={null}
        onSelect={() => {}}
        onPick={() => {}}
        onOpen={() => {}}
        onShowPast={() => {}}
        onClearFilters={() => {}}
        onReload={() => {}}
        onNew={() => {}}
      />,
    );
    expect(html).toMatch(/<div class="pane-head">.*<button type="button" class="btn btn-primary">.*새 결정<\/button><\/div>/);
    expect(html).toContain('<code class="lrow-ref">DCS-0003</code>');
    expect(html).toContain('<span class="dc-date num">정하기 전</span>');
  });

  it('lists the decisions linked to a work item, opening a past one with 지난 것 unfolded', () => {
    expect(renderToStaticMarkup(<LinkedDecisionList decisions={[]} />)).toBe('');
    const html = renderToStaticMarkup(<LinkedDecisionList decisions={[made(), made({ id: 'dcs_s', status: 'superseded', ref: 'DCS-0001' })]} />);
    expect(html).toContain('이어진 결정 <span class="num">2</span>');
    expect(html).toContain('href="#decisions?item=dcs_m"');
    expect(html).toContain('href="#decisions?past=1&amp;item=dcs_s"');
    expect(decisionHref({ id: 'dcs_r', status: 'rejected' })).toBe('#decisions?past=1&item=dcs_r');
  });
});
