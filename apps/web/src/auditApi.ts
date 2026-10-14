// Server API for the audit screen (FN-1404): the audit log with its filters and chip counts, one row,
// the retention settings (read-only: they are server settings) and the export, which the browser
// downloads from GET /api/export. Same origin as the page.

import { call } from './workApi';

export type ActorClass = 'person' | 'session' | 'run' | 'system';

export type AuditEntry = {
  id: string;
  actor_kind: 'person' | 'session' | 'system' | string;
  actor_id: string;
  on_behalf_of_user_id: string | null;
  action: string;
  target_kind: string | null;
  target_id: string | null;
  details: Record<string, unknown>;
  at: string;
  /** `run` when the system acted as one run. An older server leaves it out. */
  actor_class?: ActorClass;
  /** The work item of a run or master check target. */
  target_work_item_id?: string | null;
};

export type AuditFacets = {
  total: number;
  actor_classes: Record<ActorClass, number>;
  /** By target kind; rows without a target under `none`. */
  target_kinds: Record<string, number>;
};

export type RetentionKind = { kind: string; label: string; days: number | null; setting: string };

export type RetentionView = {
  kinds: RetentionKind[];
  last_run: { ran_at: string; days: Record<string, number>; deleted: Record<string, number> } | null;
};

/** The filters the screen sends; empty ones are left out. */
export type AuditParams = { actorClass?: string; targetKind?: string; targetId?: string; after?: string; limit?: number };

function query(p: AuditParams): string {
  const q = new URLSearchParams();
  if (p.actorClass) q.set('actor_class', p.actorClass);
  if (p.targetKind) q.set('target_kind', p.targetKind);
  if (p.targetId) q.set('target_id', p.targetId);
  if (p.after) q.set('after', p.after);
  if (p.limit) q.set('limit', String(p.limit));
  const s = q.toString();
  return s ? `?${s}` : '';
}

export const auditApi = {
  list: (p: AuditParams, signal?: AbortSignal) => call<AuditEntry[]>('GET', `/api/audit${query(p)}`, undefined, signal),
  facets: (p: AuditParams, signal?: AbortSignal) => call<AuditFacets>('GET', `/api/audit/facets${query({ ...p, limit: undefined })}`, undefined, signal),
  one: (id: string, signal?: AbortSignal) => call<AuditEntry>('GET', `/api/audit/${encodeURIComponent(id)}`, undefined, signal),
  retention: (signal?: AbortSignal) => call<RetentionView>('GET', '/api/settings/retention', undefined, signal),
};

/** Where the browser downloads the export bundle (a file, `Content-Disposition: attachment`). */
export const EXPORT_HREF = '/api/export';
