import type { IconName } from '../icons/Icon';
import type { ItemKind, OrgLink, OrgPerson, OrgView } from '../orgApi';

/*
 * What the organisation screen shows, as plain functions: the groups and rows, the words for kinds
 * and roles, which links can be added from an item, and the address that keeps the selection. The
 * server holds the structure and decides who may change it; this only arranges what it returned.
 * A one-person organisation shows the parts it uses; a second person or a team widens it to every
 * kind (DESIGN.md 「설계 원칙」 확장성).
 */

export type EntryKind = 'org' | 'person' | ItemKind;

export type Entry = { id: string; kind: EntryKind; name: string; sub: string; archived: boolean; mono?: boolean };

export type GroupKind = EntryKind | 'rest';

export type OrgGroup = { kind: GroupKind; label: string; icon: IconName; entries: Entry[]; empty: string };

/** The six roles in the server's order (G02.1, G02.5). */
export const ROLE_ORDER = ['requester', 'assignee', 'reviewer', 'approver', 'operator', 'master_admin'];

export const ROLE_LABELS: Record<string, string> = {
  requester: '요청자',
  assignee: '담당자',
  reviewer: '검토자',
  approver: '승인자',
  operator: '운영자',
  master_admin: '마스터 관리자',
};

/** What each role lets a person do here (the server's judge decides; this only explains it). */
export const ROLE_NOTES: Record<string, string> = {
  requester: '작업을 만들고 결정을 제안한다',
  assignee: '맡은 작업을 옮기고 AI에게 맡긴다',
  reviewer: '받은 사람 검토를 승인·반려한다',
  approver: '결정과 문서판을 승인한다',
  operator: '조직 구조·설정·AI 계정·내보내기를 다룬다',
  master_admin: '충돌·모순 알림을 판정한다',
};

export const KIND_LABELS: Record<EntryKind, string> = {
  org: '조직',
  person: '사람',
  team: '팀',
  product: '제품',
  project: '프로젝트',
  repository: '저장소',
  environment: '환경',
};

export const KIND_ICONS: Record<EntryKind, IconName> = {
  org: 'settings',
  person: 'person',
  team: 'team',
  product: 'doc',
  project: 'project',
  repository: 'git-file',
  environment: 'run',
};

export const ENV_LABELS: Record<string, string> = { development: '개발', staging: '검증', production: '운영', other: '기타' };

export const envLabel = (kind: string) => ENV_LABELS[kind] ?? kind;

/** Why the owner's roles and status cannot change (the server refuses with the same reason). */
export const OWNER_ROLES_REASON = '조직 소유자는 늘 모든 역할을 갖는다';

/** Why an imported project keeps its name. */
export const GIT_NAME_REASON = '정본이 Git인 프로젝트다. 이름은 원본에서 고치고 다시 가져온다';

/** The quiet row of a one-person organisation for the kinds it does not use yet. */
export const REST_LINE = '팀·제품·환경: 아직 없음. 사람을 더하면 팀을 나눈다';

export const roleLabel = (r: string) => ROLE_LABELS[r] ?? r;

/** The roles in order, as words; the owner holds every one. */
export function rolesLine(p: Pick<OrgPerson, 'roles' | 'owner'>): string {
  if (p.owner || ROLE_ORDER.every((r) => p.roles.includes(r))) return '모든 역할';
  if (p.roles.length === 0) return '역할 없음';
  return sortRoles(p.roles).map(roleLabel).join(', ');
}

export const sortRoles = (roles: string[]) => [...roles].sort((a, b) => ROLE_ORDER.indexOf(a) - ROLE_ORDER.indexOf(b));

/** Whether the screen shows every kind: more than one person, or a kind a team uses already exists. */
export const isWide = (o: OrgView) => !o.solo || o.teams.length > 0 || o.products.length > 0 || o.environments.length > 0;

const linkCount = (o: OrgView, id: string, kind: ItemKind, from: boolean) =>
  o.links.filter((l) => (from ? l.from_id === id && l.to_kind === kind : l.to_id === id && l.from_kind === kind)).length;

/** The groups in their order, each with its rows. A one-person organisation folds the kinds it does not use into one row. */
export function groupOrg(o: OrgView): OrgGroup[] {
  const people = o.people.filter((p) => p.status === 'active').length;
  const org: Entry = { id: o.id, kind: 'org', name: o.name, sub: `사람 ${people}명, ${o.solo ? '1인 조직' : '팀 조직'}`, archived: false };
  const person = (p: OrgPerson): Entry => ({
    id: p.id,
    kind: 'person',
    name: p.display_name,
    sub: p.status === 'inactive' ? `비활성, ${rolesLine(p)}` : rolesLine(p),
    archived: p.status === 'inactive',
  });
  const groups: OrgGroup[] = [
    { kind: 'org', label: '조직', icon: KIND_ICONS.org, entries: [org], empty: '' },
    { kind: 'person', label: '사람', icon: KIND_ICONS.person, entries: o.people.map(person), empty: '사람이 없다' },
  ];
  const wide = isWide(o);
  if (wide) {
    groups.push({
      kind: 'team',
      label: '팀',
      icon: KIND_ICONS.team,
      entries: o.teams.map((t) => ({ id: t.id, kind: 'team', name: t.name, sub: `사람 ${t.members.length}명, 프로젝트 ${linkCount(o, t.id, 'project', true)}개`, archived: t.archived_at !== null })),
      empty: '아직 팀이 없다',
    });
    groups.push({
      kind: 'product',
      label: '제품',
      icon: KIND_ICONS.product,
      entries: o.products.map((p) => ({ id: p.id, kind: 'product', name: p.name, sub: `프로젝트 ${linkCount(o, p.id, 'project', true)}개`, archived: p.archived_at !== null })),
      empty: '아직 제품이 없다',
    });
  }
  groups.push({
    kind: 'project',
    label: '프로젝트',
    icon: KIND_ICONS.project,
    entries: o.projects.map((p) => ({
      id: p.id,
      kind: 'project',
      name: p.name,
      sub: [p.external_ref, p.kind, `저장소 ${linkCount(o, p.id, 'repository', true)}개`].filter(Boolean).join(', '),
      archived: false,
    })),
    empty: '아직 프로젝트가 없다. zm-docs를 가져오거나 여기서 더한다',
  });
  groups.push({
    kind: 'repository',
    label: '저장소',
    icon: KIND_ICONS.repository,
    entries: o.repositories.map((r) => ({ id: r.id, kind: 'repository', name: r.name, sub: `${shortKey(r.repo_key)}, PC ${r.devices}대`, archived: r.archived_at !== null })),
    empty: '아직 저장소가 없다. PC에서 zm-baton repo add로 더하면 여기에도 생긴다',
  });
  if (wide) {
    groups.push({
      kind: 'environment',
      label: '환경',
      icon: KIND_ICONS.environment,
      entries: o.environments.map((e) => ({ id: e.id, kind: 'environment', name: e.name, sub: `${envLabel(e.kind)}, 프로젝트 ${linkCount(o, e.id, 'project', false)}개`, archived: e.archived_at !== null })),
      empty: '아직 환경이 없다',
    });
  } else {
    groups.push({ kind: 'rest', label: '팀·제품·환경', icon: KIND_ICONS.team, entries: [], empty: REST_LINE });
  }
  return groups;
}

export const rowOrder = (groups: OrgGroup[]) => groups.flatMap((g) => g.entries.map((e) => e.id));

/** `git:0123…cdef`: enough of a repository key to tell two apart on one row. */
export function shortKey(key: string): string {
  return key.length > 20 ? `${key.slice(0, 12)}…${key.slice(-4)}` : key;
}

/** The kind of an id from its prefix, or of the organisation's own id. */
export function kindOf(o: OrgView, id: string): EntryKind | null {
  if (id === o.id) return 'org';
  if (o.people.some((p) => p.id === id)) return 'person';
  if (o.teams.some((t) => t.id === id)) return 'team';
  if (o.products.some((p) => p.id === id)) return 'product';
  if (o.projects.some((p) => p.id === id)) return 'project';
  if (o.repositories.some((r) => r.id === id)) return 'repository';
  if (o.environments.some((e) => e.id === id)) return 'environment';
  return null;
}

/** An item's or a person's name, for links and member lists. */
export function nameOf(o: OrgView, id: string): string {
  return (
    o.people.find((p) => p.id === id)?.display_name ??
    [...o.teams, ...o.products, ...o.projects, ...o.repositories, ...o.environments].find((x) => x.id === id)?.name ??
    id
  );
}

/** The links an item has, each seen from it: the other end and which way it points. */
export function linksOf(o: OrgView, id: string): { link: OrgLink; other: string; otherKind: ItemKind }[] {
  return o.links
    .filter((l) => l.from_id === id || l.to_id === id)
    .map((l) => (l.from_id === id ? { link: l, other: l.to_id, otherKind: l.to_kind } : { link: l, other: l.from_id, otherKind: l.from_kind }));
}

/** The links the server takes (V13): a team or product to a project, a project to a repository or environment. */
export const LINK_PAIRS: [ItemKind, ItemKind][] = [
  ['team', 'project'],
  ['product', 'project'],
  ['project', 'repository'],
  ['project', 'environment'],
];

export type LinkOption = { fromId: string; toId: string; label: string };

/** The links that can still be added from [id], each named by its other end; archived items are not offered. */
export function linkOptions(o: OrgView, kind: EntryKind, id: string): LinkOption[] {
  const live = (k: ItemKind) => {
    const all: { id: string; name: string; archived_at?: string | null }[] = {
      team: o.teams,
      product: o.products,
      project: o.projects,
      repository: o.repositories,
      environment: o.environments,
    }[k];
    return all.filter((x) => !x.archived_at);
  };
  const linked = new Set(o.links.filter((l) => l.from_id === id || l.to_id === id).map((l) => (l.from_id === id ? l.to_id : l.from_id)));
  const out: LinkOption[] = [];
  for (const [from, to] of LINK_PAIRS) {
    if (from === kind) for (const x of live(to)) if (!linked.has(x.id)) out.push({ fromId: id, toId: x.id, label: `${KIND_LABELS[to]} ${x.name}` });
    if (to === kind) for (const x of live(from)) if (!linked.has(x.id)) out.push({ fromId: x.id, toId: id, label: `${KIND_LABELS[from]} ${x.name}` });
  }
  return out;
}

export type ListState = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'rows' };

export function listState(org: OrgView | null, error: string | null): ListState {
  if (org === null) return error === null ? { kind: 'loading' } : { kind: 'error', message: error };
  return { kind: 'rows' };
}

/** The selected row, kept in the address (`#org?item=…`). */
export type OrgAddress = { selected: string | null };

export function readView(hash: string): OrgAddress {
  const at = hash.indexOf('?');
  const p = new URLSearchParams(at < 0 ? '' : hash.slice(at + 1));
  return { selected: p.get('item') || null };
}

export const writeView = (v: OrgAddress) => (v.selected ? `#org?item=${encodeURIComponent(v.selected)}` : '#org');

/** What the add form holds, by kind. */
export type NewDraft = { kind: Exclude<EntryKind, 'org'>; name: string; roles: string[]; repoKey: string; remote: string; envKind: string; projectKind: string };

export const newDraft = (kind: NewDraft['kind'] = 'person'): NewDraft => ({
  kind,
  name: '',
  roles: ['requester', 'assignee'],
  repoKey: '',
  remote: '',
  envKind: 'development',
  projectKind: '',
});

/** Why the add form cannot be sent yet, or null. */
export function draftProblem(d: NewDraft): string | null {
  if (d.name.trim() === '') return '이름을 쓴다';
  if (d.name.trim().length > 100) return '이름은 100자까지다';
  if (d.kind === 'repository' && !isRepoKey(d.repoKey.trim())) return '저장소 키는 git:<첫 커밋 번호> 같은 이름이다. 이 PC의 경로는 쓰지 않는다';
  return null;
}

/** A repository key as the server takes it (SessionService.isRepoKey): a token, never a path on this PC. */
export function isRepoKey(s: string): boolean {
  return /^[A-Za-z0-9._:@/+-]{1,200}$/.test(s) && !/^([/\\~]|[A-Za-z]:)/.test(s);
}
