import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { meApi, orgApi, type OrgView } from './orgApi';
import { addedId, addFrom, applyChange } from './org/orgChange';
import { OrgDetail } from './org/OrgDetail';
import { OrgForm } from './org/OrgForm';
import { OrgList } from './org/OrgList';
import { draftProblem, groupOrg, isRepoKey, isWide, linkOptions, listState, newDraft, readView, rolesLine, rowOrder, writeView } from './org/orgView';
import { visibilityLabel } from './ui/visibility';

const ROLES = ['requester', 'assignee', 'reviewer', 'approver', 'operator', 'master_admin'];

const solo = (over: Partial<OrgView> = {}): OrgView => ({
  id: 'org_owner',
  name: 'owner',
  owner_user_id: 'usr_owner',
  solo: true,
  roles: ROLES.map((id) => ({ id, label: id })),
  visibilities: ['personal', 'restricted', 'team', 'org', 'public'].map((id) => ({ id, label: id })),
  environment_kinds: ['development', 'staging', 'production', 'other'],
  people: [{ id: 'usr_owner', display_name: 'owner', status: 'active', roles: ROLES, manager_id: 'mgr_owner', teams: [], projects: [], owner: true }],
  teams: [],
  products: [],
  projects: [{ id: 'prj_a', name: 'zm-baton', external_ref: 'PRJ-009', kind: null, members: [] }],
  repositories: [{ id: 'rep_a', name: 'zm-baton', repo_key: 'git:0123456789abcdef0123456789abcdef01234567', remote: 'https://github.com/me/zm-baton', devices: 1, archived_at: null }],
  environments: [],
  links: [],
  can_manage: true,
  ...over,
});

const team = (): OrgView =>
  solo({
    solo: false,
    people: [
      ...solo().people,
      { id: 'usr_b', display_name: '김개발', status: 'active', roles: ['assignee'], manager_id: 'mgr_b', teams: ['tem_a'], projects: ['prj_a'], owner: false },
    ],
    teams: [{ id: 'tem_a', name: '백엔드', members: ['usr_b'], archived_at: null }],
    environments: [{ id: 'env_a', name: '운영', kind: 'production', archived_at: null }],
    links: [{ from_kind: 'team', from_id: 'tem_a', to_kind: 'project', to_id: 'prj_a' }],
  });

const list = (o: OrgView, canManage = o.can_manage) =>
  renderToStaticMarkup(
    <OrgList
      state={listState(o, null)}
      groups={groupOrg(o)}
      head={{ base: '조직 항목', count: rowOrder(groupOrg(o)).length, narrowed: false }}
      selected={null}
      canManage={canManage}
      onSelect={() => {}}
      onPick={() => {}}
      onOpen={() => {}}
      onNew={() => {}}
      onReload={() => {}}
    />,
  );

const detail = (o: OrgView, id: string) =>
  renderToStaticMarkup(<OrgDetail org={o} id={id} run={async () => {}} refusal={null} onOpen={() => {}} />);

describe('organisation view', () => {
  it('keeps a one-person organisation simple and folds the kinds it does not use into one row', () => {
    const groups = groupOrg(solo());
    expect(groups.map((g) => g.kind)).toEqual(['org', 'person', 'project', 'repository', 'rest']);
    expect(groups[1].entries[0].sub).toBe('모든 역할');
    expect(groups[0].entries[0].sub).toBe('사람 1명, 1인 조직');
    const html = list(solo());
    expect(html).toContain('팀·제품·환경: 아직 없음. 사람을 더하면 팀을 나눈다');
    expect(html).toContain('팀 더하기');
    expect(html).not.toContain('data-group="team"');
  });

  it('widens to every kind once a second person or a team is there, each group shown even when empty', () => {
    expect(isWide(solo())).toBe(false);
    expect(isWide(solo({ teams: [{ id: 'tem_x', name: 't', members: [], archived_at: null }] }))).toBe(true);
    const groups = groupOrg(team());
    expect(groups.map((g) => g.kind)).toEqual(['org', 'person', 'team', 'product', 'project', 'repository', 'environment']);
    expect(groups.find((g) => g.kind === 'product')?.entries).toEqual([]);
    expect(groups.find((g) => g.kind === 'team')?.entries[0].sub).toBe('사람 1명, 프로젝트 1개');
    const html = list(team());
    expect(html).toContain('아직 제품이 없다');
    expect(html).toContain('김개발');
    expect(html).not.toContain('팀·제품·환경: 아직 없음');
  });

  it('offers no add button to a person who may not change the structure', () => {
    expect(list(solo({ can_manage: false }), false)).not.toContain('더하기');
  });

  it('names roles in order, all of them for the owner, and none when there are none', () => {
    expect(rolesLine({ roles: ROLES, owner: true })).toBe('모든 역할');
    expect(rolesLine({ roles: ['approver', 'assignee'], owner: false })).toBe('담당자, 승인자');
    expect(rolesLine({ roles: [], owner: false })).toBe('역할 없음');
  });

  it('offers only the links the server takes and that are not there yet', () => {
    const o = team();
    expect(linkOptions(o, 'team', 'tem_a')).toEqual([]);
    expect(linkOptions(o, 'project', 'prj_a').map((x) => [x.fromId, x.toId])).toEqual([
      ['prj_a', 'rep_a'],
      ['prj_a', 'env_a'],
    ]);
    expect(linkOptions(o, 'repository', 'rep_a').map((x) => x.label)).toEqual(['프로젝트 zm-baton']);
  });

  it('keeps the selection in the address and checks the add form before sending', () => {
    expect(readView('#org?item=usr_b')).toEqual({ selected: 'usr_b' });
    expect(writeView({ selected: null })).toBe('#org');
    expect(draftProblem(newDraft('team'))).toBe('이름을 쓴다');
    expect(draftProblem({ ...newDraft('repository'), name: 'r', repoKey: 'C:\\code\\r' })).toContain('저장소 키');
    expect(isRepoKey('git:0123abc')).toBe(true);
    expect(isRepoKey('/home/me/r')).toBe(false);
    expect(draftProblem({ ...newDraft('repository'), name: 'r', repoKey: 'git:0123abc' })).toBeNull();
  });
});

describe('organisation detail', () => {
  it('locks the owner roles with the reason and shows every role checked', () => {
    const html = detail(solo(), 'usr_owner');
    expect(html).toContain('조직 소유자는 늘 모든 역할을 갖는다');
    expect((html.match(/type="checkbox" disabled="" checked=""/g) ?? []).length).toBe(6);
    expect(html).toContain('mgr_owner');
    expect(html).not.toContain('역할 저장하기');
  });

  it('lets an operator change another person and shows their links as names', () => {
    const html = detail(team(), 'usr_b');
    expect(html).toContain('역할 저장하기');
    expect(html).toContain('비활성으로 바꾸기');
    expect(html).toContain('백엔드');
    const teamHtml = detail(team(), 'tem_a');
    expect(teamHtml).toContain('이어진 항목');
    expect(teamHtml).toContain('끊기');
    expect(teamHtml).toContain('보관하기');
  });

  it('reads the structure without controls for someone who is not an operator', () => {
    const html = detail({ ...team(), can_manage: false }, 'usr_b');
    expect(html).toContain('조직 구조는 운영자 역할만 바꾼다');
    expect(html).not.toContain('역할 저장하기');
    expect(html).toContain('담당자');
  });

  it('explains roles and sharing scopes on the organisation row, and keeps an imported project name', () => {
    const html = detail(solo(), 'org_owner');
    expect(html).toContain('1인 조직');
    expect(html).toContain('본인만 본다. 조직 운영자도 못 본다');
    expect(detail(solo(), 'prj_a')).toContain('정본이 Git인 프로젝트다');
  });

  it('draws the add form by kind', () => {
    const repo = renderToStaticMarkup(<OrgForm start={newDraft('repository')} envKinds={['development']} onSubmit={async () => {}} onCancel={() => {}} error={null} />);
    expect(repo).toContain('저장소 키');
    const person = renderToStaticMarkup(<OrgForm start={newDraft('person')} envKinds={[]} onSubmit={async () => {}} onCancel={() => {}} error="HTTP 403: 이 일(조직 구조 관리)에는 운영자 역할 가운데 하나가 필요하다" />);
    expect(person).toContain('운영자 역할');
    expect(person).toContain('매니저 기록');
  });
});

describe('organisation requests', () => {
  afterEach(() => vi.unstubAllGlobals());

  const reply = (body: unknown, status = 200) => vi.fn(async () => new Response(JSON.stringify(body), { status }));

  it('adds a person with roles and finds the new row in the structure the server returned', async () => {
    const after = team();
    const fetch = reply(after, 201);
    vi.stubGlobal('fetch', fetch);
    const got = await addFrom({ ...newDraft('person'), name: ' 김개발 ', roles: ['assignee'] });
    const [path, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(path).toBe('/api/org/people');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body as string)).toEqual({ display_name: '김개발', roles: ['assignee'] });
    expect(addedId(solo(), got, 'person')).toBe('usr_b');
    expect(groupOrg(got).find((g) => g.kind === 'person')?.entries.map((e) => e.name)).toEqual(['owner', '김개발']);
  });

  it('sends each change to its path', async () => {
    const fetch = reply(team());
    vi.stubGlobal('fetch', fetch);
    await applyChange('person', 'usr_b', { kind: 'roles', roles: ['assignee', 'reviewer'] });
    await applyChange('team', 'tem_a', { kind: 'members', ids: ['usr_b'] });
    await applyChange('project', 'prj_a', { kind: 'link', fromId: 'prj_a', toId: 'rep_a' });
    await applyChange('team', 'tem_a', { kind: 'unlink', fromId: 'tem_a', toId: 'prj_a' });
    await applyChange('environment', 'env_a', { kind: 'archive', archived: true });
    await applyChange('org', 'org_owner', { kind: 'rename', name: ' 우리 팀 ' });
    const calls = (fetch.mock.calls as unknown as [string, RequestInit][]).map(([p, i]) => [i.method, p, i.body ? JSON.parse(i.body as string) : null]);
    expect(calls).toEqual([
      ['PATCH', '/api/org/people/usr_b', { roles: ['assignee', 'reviewer'] }],
      ['PUT', '/api/org/teams/tem_a/members', { user_ids: ['usr_b'] }],
      ['POST', '/api/org/links', { from_id: 'prj_a', to_id: 'rep_a' }],
      ['DELETE', '/api/org/links?from_id=tem_a&to_id=prj_a', null],
      ['PATCH', '/api/org/environments/env_a', { archived: true }],
      ['PATCH', '/api/org', { name: '우리 팀' }],
    ]);
  });

  it('passes the server refusal on as it is', async () => {
    vi.stubGlobal('fetch', reply({ message: '조직 소유자의 역할과 상태는 바꾸지 않는다. 소유자는 늘 모든 역할을 갖는다' }, 409));
    await expect(orgApi.changePerson('usr_owner', { roles: [] })).rejects.toThrow('조직 소유자의 역할과 상태는 바꾸지 않는다');
  });

  it('deletes a note with no body to read back', async () => {
    const fetch = vi.fn(async () => new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetch);
    await meApi.deleteNote('mnt_a');
    const [path, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect([init.method, path]).toEqual(['DELETE', '/api/me/notes/mnt_a']);
  });
});

describe('sharing scope', () => {
  it('names the five scopes and shows an unknown one as the server wrote it', () => {
    expect(['personal', 'restricted', 'team', 'org', 'public'].map(visibilityLabel)).toEqual(['개인', '제한', '팀', '조직', '공개']);
    expect(visibilityLabel(undefined)).toBe('조직');
    expect(visibilityLabel('guests')).toBe('guests');
  });
});
