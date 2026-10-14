import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { Doc, DocDetail, DocVersion } from './documentsApi';
import { DocumentDetailView, type DocActions } from './documents/DocumentDetailView';
import { DocumentForm } from './documents/DocumentForm';
import { DocumentChipRow, DocumentList } from './documents/DocumentList';
import { Documents } from './documents/Documents';
import {
  bucketOf,
  changedDocFields,
  conditions,
  docDraftFrom,
  docDraftProblem,
  docHistoryLine,
  emptyDocDraft,
  filterDocs,
  groupDocs,
  linkedDecisionHref,
  listState,
  NO_FILTERS,
  readView,
  registerFields,
  rowOrder,
  sourceCounts,
  versionLabel,
  writeView,
  type ListState,
} from './documents/documentView';
import { VersionWriter } from './documents/VersionWriter';
import { matchScreen, NAV_GROUPS, screenFromHash } from './shell/screens';
import { MarkdownField } from './ui/MarkdownField';

const doc = (over: Partial<Doc> = {}): Doc => ({
  id: 'doc_a',
  title: '1단계 문서 화면 안내',
  project: { id: 'prj_a', external_ref: 'PRJ-009', name: 'zm-baton' },
  source_of_truth: 'product',
  git_ref: null,
  owner_user_id: 'usr_owner',
  version: 0,
  latest: { number: 2, state: 'draft', git_commit_sha: null, created_at: '2026-10-14T01:00:00Z' },
  approved_number: 1,
  updated_at: '2026-10-14T01:00:00Z',
  ...over,
});

const gitDoc = doc({
  id: 'doc_g',
  title: '결정 기록 안내',
  source_of_truth: 'git',
  git_ref: { repo: 'github.com/hanumoka/zm-docs', path: 'docs/governance/decisions/README.md', branch: 'main' },
  latest: { number: 1, state: 'approved', git_commit_sha: 'abcdef1234', created_at: '2026-10-13T01:00:00Z' },
  approved_number: 1,
  updated_at: '2026-10-13T01:00:00Z',
});

const bare = doc({ id: 'doc_n', title: '경로만', source_of_truth: 'git', git_ref: { repo: 'zm-docs', path: 'README.md' }, latest: null, approved_number: null, updated_at: '2026-10-12T00:00:00Z' });

const version = (over: Partial<DocVersion> = {}): DocVersion => ({
  id: 'dvr_2',
  number: 2,
  state: 'draft',
  note: '고침',
  body_markdown: null,
  git_commit_sha: null,
  created_by: 'usr_owner',
  created_at: '2026-10-14T01:00:00Z',
  approved_by: null,
  approved_at: null,
  approve: { allowed: true, reason: null },
  ...over,
});

const v1 = version({ id: 'dvr_1', number: 1, state: 'approved', note: '처음', approved_by: 'usr_owner', approved_at: '2026-10-13T02:00:00Z', approve: { allowed: false, reason: '이미 승인한 판이다' } });

const detailOf = (d: Doc, over: Partial<DocDetail> = {}): DocDetail => ({
  document: d,
  versions: [version(), v1],
  shown: version({ body_markdown: '# 안내\n\n**둘째** 판이다.' }),
  work_items: [{ id: 'wrk_a', external_ref: null, title: '문서 화면 만들기', stage: 'in_progress', wait_reasons: [] }],
  decisions: [{ id: 'dcs_a', ref: 'DCS-0003', question: '문서 화면을 어디에 둘까', status: 'accepted' }],
  approver: 'usr_owner',
  read_only_reason: null,
  history: [{ action: 'document.version_added', actor_kind: 'person', actor_id: 'usr_owner', details: { number: 2, note: '고침' }, at: '2026-10-14T01:00:00Z' }],
  ...over,
});

const noop: DocActions = { write: () => {}, record: async () => {}, approve: async () => {}, edit: () => {}, show: () => {} };

const detailHtml = (d: DocDetail) => renderToStaticMarkup(<DocumentDetailView doc={d.document} detail={d} actions={noop} />);

function list(state: ListState, items: Doc[] = []) {
  return renderToStaticMarkup(
    <DocumentList
      state={state}
      groups={groupDocs(items)}
      head={{ base: '문서', count: items.length, narrowed: false }}
      selected={null}
      onSelect={() => {}}
      onPick={() => {}}
      onOpen={() => {}}
      onNew={() => {}}
      onClearFilters={() => {}}
      onReload={() => {}}
    />,
  );
}

describe('groups and filters', () => {
  it('groups by where the newest version stands, each most recently changed first, empty groups kept', () => {
    expect([doc(), gitDoc, bare].map(bucketOf)).toEqual(['review', 'approved', 'none']);
    expect(groupDocs([gitDoc, doc(), bare]).map((g) => [g.label, g.items.map((d) => d.id)])).toEqual([
      ['승인 기다림', ['doc_a']],
      ['승인됨', ['doc_g']],
      ['판 없음', ['doc_n']],
    ]);
    expect(groupDocs([]).map((g) => g.items.length)).toEqual([0, 0, 0]);
    expect(rowOrder(groupDocs([bare, gitDoc, doc()]))).toEqual(['doc_a', 'doc_g', 'doc_n']);
    expect([doc(), gitDoc, bare].map(versionLabel)).toEqual(['판 2 초안', '판 1 승인됨', '판 없음']);
  });

  it('filters by source here so the chip counts both, and says what the list is filtered by', () => {
    expect(filterDocs([doc(), gitDoc, bare], 'git').map((d) => d.id)).toEqual(['doc_g', 'doc_n']);
    expect(sourceCounts([doc(), gitDoc, bare])).toEqual({ product: 1, git: 2 });
    expect(conditions({ projectId: 'prj_a', source: 'git' }, ' 안내 ', [{ id: 'prj_a', external_ref: 'PRJ-009', name: 'zm-baton', open_work_items: 0 }])).toEqual([
      '프로젝트 zm-baton',
      '정본 위치 Git',
      '찾는 말 「안내」',
    ]);
    const chips = renderToStaticMarkup(<DocumentChipRow filters={NO_FILTERS} onFilters={() => {}} projects={[]} counts={{ product: 1, git: 2 }} />);
    expect(chips).toContain('<option value="git">Git (2)</option>');
  });

  it('tells loading, a failed read, nothing yet, filtered to nothing and rows apart', () => {
    const base = { error: null, filters: NO_FILTERS, query: '', projects: [] };
    expect(listState({ ...base, items: null, visible: 0 })).toEqual({ kind: 'loading' });
    expect(listState({ ...base, items: null, visible: 0, error: 'HTTP 503' })).toEqual({ kind: 'error', message: 'HTTP 503' });
    expect(listState({ ...base, items: [], visible: 0 })).toEqual({ kind: 'unused' });
    expect(listState({ ...base, items: [doc()], visible: 0, filters: { projectId: '', source: 'git' } }).kind).toBe('filtered');
    expect(listState({ ...base, items: [doc()], visible: 1 })).toEqual({ kind: 'rows' });
  });

  it('keeps the filters, the document and the version shown in the address', () => {
    const v = { projectId: 'prj_a', source: 'git' as const, selected: 'doc_g', version: 1 };
    expect(writeView(v)).toBe('#documents?project=prj_a&source=git&item=doc_g&ver=1');
    expect(readView(writeView(v))).toEqual(v);
    expect(readView('#documents?source=notion&ver=x&item=doc_a')).toEqual({ projectId: '', source: '', selected: 'doc_a', version: null });
    expect(writeView({ ...NO_FILTERS, selected: null, version: 3 })).toBe('#documents');
    expect(screenFromHash('#documents?item=doc_a')).toBe('documents');
  });
});

describe('the form as data', () => {
  it('sends a product document a first body only when there is one, a Git document its place and commit', () => {
    expect(registerFields({ ...emptyDocDraft('prj_a'), title: ' 안내 ', body: '  ' })).toEqual({
      title: '안내',
      source_of_truth: 'product',
      project_id: 'prj_a',
      work_item_ids: [],
      decision_ids: [],
    });
    expect(registerFields({ ...emptyDocDraft(), title: '안내', body: '# 본문', note: '처음' }).body_markdown).toBe('# 본문');
    expect(registerFields({ ...emptyDocDraft(), title: 'g', source: 'git', repo: ' zm-docs ', path: 'README.md', commit: 'abc1234' }).git).toEqual({
      repo: 'zm-docs',
      path: 'README.md',
      commit: 'abc1234',
    });
  });

  it('sends only the fields that changed, and never the source of truth', () => {
    const before = docDraftFrom(detailOf(gitDoc));
    expect(changedDocFields(before, before)).toEqual({});
    expect(changedDocFields(before, { ...before, title: '새 제목', path: 'docs/README.md', decisionIds: [] })).toEqual({
      title: '새 제목',
      git: { repo: 'github.com/hanumoka/zm-docs', path: 'docs/README.md', branch: 'main' },
      decision_ids: [],
    });
    expect(changedDocFields(before, { ...before, source: 'product' })).toEqual({});
  });

  it('cannot be sent without a title, a Git place, a path from the root or a proper commit', () => {
    expect(docDraftProblem(emptyDocDraft())).toBe('제목을 쓰면 보낼 수 있다.');
    const git = { ...emptyDocDraft(), title: 'g', source: 'git' as const };
    expect(docDraftProblem(git)).toBe('Git 문서는 저장소와 경로를 쓴다.');
    expect(docDraftProblem({ ...git, repo: 'r', path: 'C:/Users/me/a.md' })).toContain('절대 경로는 받지 않는다');
    expect(docDraftProblem({ ...git, repo: 'r', path: '/etc/a.md' })).toContain('절대 경로는 받지 않는다');
    expect(docDraftProblem({ ...git, repo: 'r', path: 'a.md', commit: 'xyz' })).toBe('커밋은 16진수 7~64자다.');
    expect(docDraftProblem({ ...git, repo: 'r', path: 'a.md', commit: 'ABCDEF1' })).toBeNull();
  });

  it('writes one line per history entry', () => {
    const line = (action: string, details: Record<string, unknown>) => docHistoryLine({ action, actor_kind: 'person', actor_id: 'usr_owner', details, at: '' });
    expect(line('document.registered', { source_of_truth: 'git' })).toBe('등록함 (Git)');
    expect(line('document.updated', { fields: ['title', 'work_item_ids'] })).toBe('고침: 제목, 영향 받는 작업');
    expect(line('document.version_added', { number: 3, commit: '1234567890abcdef', note: 'README' })).toBe('판 3 더함 (커밋 1234567890) — README');
    expect(line('document.version_approved', { number: 2 })).toBe('판 2 승인함');
    expect(linkedDecisionHref({ id: 'dcs_s', status: 'superseded' })).toBe('#decisions?past=1&item=dcs_s');
  });
});

describe('the list', () => {
  it('draws the groups with counts, 새 문서 in the head and each row with its source and version state', () => {
    const html = list({ kind: 'rows' }, [doc(), gitDoc]);
    expect(html).toContain('승인 상태로 묶음');
    expect(html).toMatch(/<div class="pane-head">.*새 문서<\/button><\/div>/);
    expect(html).toMatch(/승인 기다림 <span class="n num">1<\/span>/);
    expect(html).toContain('판 없이 등록만 한 문서가 없다');
    expect(html).toContain('<span class="lrow-state">판 2 초안</span><span class="lrow-title">1단계 문서 화면 안내</span>');
    expect(html).toContain('<span class="doc-src">Git</span>');
    expect(html).toContain('<code class="doc-path">docs/governance/decisions/README.md</code>');
  });

  it('draws the three empty states', () => {
    const unused = list({ kind: 'unused' });
    expect(unused).toContain('아직 등록한 문서가 없다');
    expect(unused).toContain('새 문서 등록하기');
    expect(list({ kind: 'filtered', conditions: ['정본 위치 Git'] })).toContain('걸러 본 조건: 정본 위치 Git');
    const error = list({ kind: 'error', message: 'HTTP 503' });
    expect(error).toContain('문서 목록을 읽지 못했다');
    expect(error).toContain('다시 읽기');
  });
});

describe('the detail', () => {
  it('puts the source of truth, the versions and their approval on top, writing the next version as the main action', () => {
    const html = detailHtml(detailOf(doc()));
    expect(html).toContain('<dt>정본</dt>');
    expect(html).toContain('제품 안</dd>');
    expect(html).toContain('<dt>현재 판</dt><dd>판 2 초안</dd>');
    expect(html).toContain('<dt>승인된 판</dt><dd>판 1</dd>');
    expect(html).toMatch(/<button type="button" class="btn btn-primary">새 판 쓰기<\/button><button type="button" class="btn">판 2 승인하기<\/button><button type="button" class="btn">문서 정보 고치기<\/button>/);
    expect(html).toContain('<strong>둘째</strong>');
    expect(html).not.toContain('읽기만 한다');
    expect(html).toContain('<span class="doc-state is-approved">승인됨</span>');
    expect(html).toContain('판 1 보기');
    expect(html).toContain('href="#work?item=wrk_a"');
    expect(html).toContain('href="#decisions?item=dcs_a"');
    expect(html).toContain('판 2 더함 — 고침');
  });

  it('locks approving a version that is approved, with the server reason', () => {
    const html = detailHtml(detailOf(doc(), { shown: { ...v1, body_markdown: '첫 판' } }));
    expect(html).toMatch(/<button type="button" class="btn" disabled="" aria-describedby="[^"]+">판 1 승인하기<\/button>/);
    expect(html).toContain('이미 승인한 판이다');
    expect(html).toContain('최신은 판 2');
  });

  it('reads a Git document only, with its repository, path, branch and commit', () => {
    const g = detailOf(gitDoc, {
      versions: [version({ id: 'dvr_g', number: 1, git_commit_sha: 'abcdef1234' })],
      shown: version({ id: 'dvr_g', number: 1, git_commit_sha: 'abcdef1234' }),
      read_only_reason: '본문은 Git 파일(docs/governance/decisions/README.md)이 정본이다. Git에서 고치고 커밋한 뒤 그 커밋을 새 판으로 기록한다',
    });
    const html = detailHtml(g);
    expect(html).toContain('<strong>읽기만 한다</strong>');
    expect(html).toContain('정본은 Git 파일 <code>docs/governance/decisions/README.md</code> 저장소 <code>github.com/hanumoka/zm-docs</code> 브랜치 <code>main</code>');
    expect(html).toContain('<button type="button" class="btn btn-primary">새 커밋 기록</button>');
    expect(html).not.toContain('새 판 쓰기');
    expect(html).toContain('커밋 <code>abcdef1234</code>에 있다');
  });

  it('says how to start when there is no version yet', () => {
    expect(detailHtml(detailOf(bare, { versions: [], shown: null }))).toContain('기록한 커밋이 없다');
    expect(detailHtml(detailOf(doc({ latest: null }), { versions: [], shown: null }))).toContain('아직 판이 없다. 「새 판 쓰기」로 첫 판을 쓴다.');
  });
});

describe('writing', () => {
  it('starts the next version from the newest body and saves only a changed one', () => {
    const html = renderToStaticMarkup(<VersionWriter base={detailOf(doc())} onSave={async () => {}} onCancel={() => {}} error={null} />);
    expect(html).toContain('새 판(판 3)을 쓴다');
    expect(html).toContain('# 안내\n\n**둘째** 판이다.</textarea>');
    expect(html).toMatch(/<button type="submit" class="btn btn-primary" disabled="" aria-describedby="[^"]+">판 3 저장<\/button>/);
    expect(html).toContain('본문을 바꾸면 저장할 수 있다.');
  });

  it('offers writing and a preview of Markdown', () => {
    const html = renderToStaticMarkup(<MarkdownField label="본문" value="# 제목" onChange={() => {}} />);
    expect(html).toContain('<button type="button" class="is-on" aria-pressed="true">쓰기</button><button type="button" aria-pressed="false">미리보기</button>');
  });

  it('picks the source of truth only when registering, and shows the Git place for a Git document', () => {
    const fresh = renderToStaticMarkup(
      <DocumentForm mode="new" start={emptyDocDraft()} projects={[]} work={null} decisions={null} onSubmit={async () => {}} onCancel={() => {}} error={null} />,
    );
    expect(fresh).toContain('<legend>정본 위치</legend>');
    expect(fresh).toContain('첫 판 본문');
    expect(fresh).toMatch(/disabled="" aria-describedby="[^"]+">문서 등록하기<\/button>/);
    const editing = renderToStaticMarkup(
      <DocumentForm mode="edit" start={docDraftFrom(detailOf(gitDoc))} projects={[]} work={[]} decisions={[]} onSubmit={async () => {}} onCancel={() => {}} error={null} />,
    );
    expect(editing).not.toContain('<legend>정본 위치</legend>');
    expect(editing).toContain('<legend>Git 위치</legend>');
    expect(editing).toContain('value="docs/governance/decisions/README.md"');
    expect(editing).not.toContain('첫 판의 커밋');
    expect(editing).toContain('고친 내용 저장');
  });
});

describe('the screen', () => {
  it('sits in the sidebar after 결정 and lays out the list and the detail', () => {
    expect(NAV_GROUPS[0].items).toEqual(['inbox', 'sessions', 'work', 'decisions', 'documents', 'runs']);
    expect(matchScreen('문서')?.id).toBe('documents');
    const html = renderToStaticMarkup(<Documents onError={() => {}} onRead={() => {}} chipHost={null} query="" onClearQuery={() => {}} />);
    expect(html).toMatch(/^<main class="panes doc"><h1 class="sr">문서<\/h1><section class="pane-list"/);
    expect(html).toContain('<section class="pane-detail" aria-label="문서 상세">');
    expect(html).toContain('aria-busy="true"');
  });
});
