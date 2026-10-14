// Server API for the organisation's structure (FN-0101) and my manager record (FN-0402). The
// structure is small in stage 1, so it is read in one call and every change returns it whole. Same
// origin as the page; the server's refusals carry a reason in `message`, shown as it is.

import { call, type WorkItem } from './workApi';

export type RoleId = 'requester' | 'assignee' | 'reviewer' | 'approver' | 'operator' | 'master_admin';

export type Label = { id: string; label: string };

export type OrgPerson = {
  id: string;
  display_name: string;
  status: 'active' | 'inactive';
  roles: string[];
  manager_id: string | null;
  teams: string[];
  projects: string[];
  /** The organisation's owner: holds every role, approves in stage 1, and their roles do not change. */
  owner: boolean;
};

export type OrgTeam = { id: string; name: string; members: string[]; archived_at: string | null };
export type OrgProduct = { id: string; name: string; archived_at: string | null };
export type OrgProject = { id: string; name: string; external_ref: string | null; kind: string | null; members: string[] };
export type OrgRepository = { id: string; name: string; repo_key: string; remote: string | null; devices: number; archived_at: string | null };
export type OrgEnvironment = { id: string; name: string; kind: string; archived_at: string | null };

export type ItemKind = 'team' | 'product' | 'project' | 'repository' | 'environment';

export type OrgLink = { from_kind: ItemKind; from_id: string; to_kind: ItemKind; to_id: string };

export type OrgView = {
  id: string;
  name: string;
  owner_user_id: string;
  /** One active person: the screen stays simple until a second person joins. */
  solo: boolean;
  roles: Label[];
  visibilities: Label[];
  environment_kinds: string[];
  people: OrgPerson[];
  teams: OrgTeam[];
  products: OrgProduct[];
  projects: OrgProject[];
  repositories: OrgRepository[];
  environments: OrgEnvironment[];
  links: OrgLink[];
  /** Whether I may change the structure (role operator). */
  can_manage: boolean;
};

/** What a new item is made with; the server checks every field. */
export type NewItem = { name: string; kind?: string; repo_key?: string; remote?: string };

export type ItemChange = Partial<{ name: string; archived: boolean; kind: string; remote: string }>;

export type PersonChange = Partial<{ display_name: string; roles: string[]; status: 'active' | 'inactive' }>;

/** The path segment of an item kind: `/api/org/teams`. */
export const PLURAL: Record<ItemKind, string> = {
  team: 'teams',
  product: 'products',
  project: 'projects',
  repository: 'repositories',
  environment: 'environments',
};

export type ManagerNote = { id: string; body: string; created_at: string; updated_at: string };

export type OwnSession = {
  id: string;
  executor: string;
  device_name: string;
  repo_key: string | null;
  branch: string | null;
  /** My own summary, shown here even when it is not shared. */
  current_work: string | null;
  shared: boolean;
  current_work_at: string | null;
  state: 'live' | 'ended' | 'deregistered' | 'expired';
  last_seen_at: string;
};

export type Activity = { action: string; actor_kind: string; actor_id: string; target_kind: string | null; target_id: string | null; at: string };

export type ManagerRecord = {
  person: { id: string; display_name: string; status: string };
  manager_id: string;
  org: { id: string; name: string };
  roles: string[];
  teams: { id: string; name: string }[];
  status_card: string;
  work: WorkItem[];
  sessions: OwnSession[];
  notes: ManagerNote[];
  activity: Activity[];
};

const enc = encodeURIComponent;

export const orgApi = {
  view: (signal?: AbortSignal) => call<OrgView>('GET', '/api/org', undefined, signal),
  rename: (name: string) => call<OrgView>('PATCH', '/api/org', { name }),
  addPerson: (display_name: string, roles: string[]) => call<OrgView>('POST', '/api/org/people', { display_name, roles }),
  changePerson: (id: string, change: PersonChange) => call<OrgView>('PATCH', `/api/org/people/${enc(id)}`, change),
  addItem: (kind: ItemKind, item: NewItem) => call<OrgView>('POST', `/api/org/${PLURAL[kind]}`, item),
  changeItem: (kind: ItemKind, id: string, change: ItemChange) => call<OrgView>('PATCH', `/api/org/${PLURAL[kind]}/${enc(id)}`, change),
  setMembers: (kind: 'team' | 'project', id: string, userIds: string[]) =>
    call<OrgView>('PUT', `/api/org/${PLURAL[kind]}/${enc(id)}/members`, { user_ids: userIds }),
  link: (fromId: string, toId: string) => call<OrgView>('POST', '/api/org/links', { from_id: fromId, to_id: toId }),
  unlink: (fromId: string, toId: string) => call<OrgView>('DELETE', `/api/org/links?from_id=${enc(fromId)}&to_id=${enc(toId)}`),
};

/** A delete answers 204 with no body, which `call` cannot read. */
async function noContent(path: string): Promise<void> {
  const res = await fetch(path, { method: 'DELETE' });
  if (res.status === 204 || res.ok) return;
  let reason = '';
  try {
    reason = ((await res.json()) as { message?: string }).message ?? '';
  } catch {
    // not JSON: the status says enough
  }
  throw new Error(reason ? `HTTP ${res.status}: ${reason}` : `DELETE ${path}: HTTP ${res.status}`);
}

export const meApi = {
  record: (signal?: AbortSignal) => call<ManagerRecord>('GET', '/api/me/record', undefined, signal),
  addNote: (body: string) => call<ManagerNote>('POST', '/api/me/notes', { body }),
  changeNote: (id: string, body: string) => call<ManagerNote>('PATCH', `/api/me/notes/${enc(id)}`, { body }),
  deleteNote: (id: string) => noContent(`/api/me/notes/${enc(id)}`),
};
