import { orgApi, type ItemKind, type OrgView } from '../orgApi';
import type { EntryKind, NewDraft } from './orgView';

/*
 * The changes a person makes on the organisation screen, and the one server call each becomes. Every
 * call returns the whole structure, which the screen shows at once. The server judges who may make
 * them (role operator) and refuses with a reason the screen shows next to the control.
 */

export type Change =
  | { kind: 'rename'; name: string }
  | { kind: 'roles'; roles: string[] }
  | { kind: 'status'; status: 'active' | 'inactive' }
  | { kind: 'members'; ids: string[] }
  | { kind: 'link'; fromId: string; toId: string }
  | { kind: 'unlink'; fromId: string; toId: string }
  | { kind: 'archive'; archived: boolean }
  | { kind: 'itemKind'; value: string }
  | { kind: 'remote'; value: string };

/** Which control a change came from, so a refusal shows next to it. */
export type Where = Change['kind'];

const isItem = (k: EntryKind): k is ItemKind => k !== 'org' && k !== 'person';

/** The server call for [change] on the row [id] of [kind]. */
export function applyChange(kind: EntryKind, id: string, change: Change): Promise<OrgView> {
  switch (change.kind) {
    case 'rename':
      if (kind === 'org') return orgApi.rename(change.name.trim());
      if (kind === 'person') return orgApi.changePerson(id, { display_name: change.name.trim() });
      return orgApi.changeItem(kind, id, { name: change.name.trim() });
    case 'roles':
      return orgApi.changePerson(id, { roles: change.roles });
    case 'status':
      return orgApi.changePerson(id, { status: change.status });
    case 'members':
      if (kind !== 'team' && kind !== 'project') throw new Error('only a team or a project has members');
      return orgApi.setMembers(kind, id, change.ids);
    case 'link':
      return orgApi.link(change.fromId, change.toId);
    case 'unlink':
      return orgApi.unlink(change.fromId, change.toId);
    case 'archive':
      if (!isItem(kind)) throw new Error('only an item is archived');
      return orgApi.changeItem(kind, id, { archived: change.archived });
    case 'itemKind':
      if (!isItem(kind)) throw new Error('only an item has a kind');
      return orgApi.changeItem(kind, id, { kind: change.value });
    case 'remote':
      return orgApi.changeItem('repository', id, { remote: change.value.trim() });
  }
}

/** The server call that adds what the form holds. */
export function addFrom(d: NewDraft): Promise<OrgView> {
  const name = d.name.trim();
  switch (d.kind) {
    case 'person':
      return orgApi.addPerson(name, d.roles);
    case 'repository':
      return orgApi.addItem('repository', { name, repo_key: d.repoKey.trim(), ...(d.remote.trim() ? { remote: d.remote.trim() } : {}) });
    case 'environment':
      return orgApi.addItem('environment', { name, kind: d.envKind });
    case 'project':
      return orgApi.addItem('project', { name, ...(d.projectKind.trim() ? { kind: d.projectKind.trim() } : {}) });
    default:
      return orgApi.addItem(d.kind, { name });
  }
}

/** The id of a row the server just added: the one in [after] that [before] did not have. */
export function addedId(before: OrgView | null, after: OrgView, kind: NewDraft['kind']): string | null {
  const ids = (o: OrgView | null): string[] => {
    if (o === null) return [];
    const list = { person: o.people, team: o.teams, product: o.products, project: o.projects, repository: o.repositories, environment: o.environments }[kind];
    return list.map((x) => x.id);
  };
  const had = new Set(ids(before));
  return ids(after).find((id) => !had.has(id)) ?? null;
}
