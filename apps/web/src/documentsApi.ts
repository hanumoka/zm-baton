// Server API for documents (FN-0601, FN-0602): each with one source of truth, its versions and
// their approval, and its links to work and decisions. Same origin as the page; the server's
// refusals carry a reason in `message`, shown as it is.

import { call, type Decision, type HistoryEntry, type LinkedWork, type ProjectRef } from './workApi';

export type DocSource = 'product' | 'git';

export type VersionState = 'draft' | 'approved';

/** Where a Git document lives: a repository, a path from its root, and a branch when given. */
export type GitLocation = { repo?: string; path?: string; branch?: string };

export type VersionSummary = { number: number; state: VersionState; git_commit_sha: string | null; created_at: string };

/** One document as a list shows it. Not called `Document`, which is the browser's. */
export type Doc = {
  id: string;
  title: string;
  project: ProjectRef | null;
  source_of_truth: DocSource;
  git_ref: GitLocation | null;
  owner_user_id: string;
  version: number;
  /** The newest version; null before the first. */
  latest: VersionSummary | null;
  /** The highest approved version number; null when none is approved. */
  approved_number: number | null;
  updated_at: string;
  /** The sharing scope (G06.1); an older server leaves it out, which means `org`. */
  visibility?: string;
};

export type DocVersion = {
  id: string;
  number: number;
  state: VersionState;
  note: string | null;
  /** Only on the version a detail shows, and only for a product document. */
  body_markdown: string | null;
  git_commit_sha: string | null;
  created_by: string;
  created_at: string;
  approved_by: string | null;
  approved_at: string | null;
  approve: { allowed: boolean; reason: string | null };
};

export type LinkedDecision = { id: string; ref: string | null; question: string; status: Decision['status'] };

export type DocDetail = {
  document: Doc;
  /** Newest first, without bodies. */
  versions: DocVersion[];
  /** The version asked for (the newest by default) with its body; null before the first. */
  shown: DocVersion | null;
  work_items: LinkedWork[];
  decisions: LinkedDecision[];
  approver: string;
  /** Set for a Git document: its body is not written here. */
  read_only_reason: string | null;
  history: HistoryEntry[];
};

/** What a person writes when registering or changing a document; a change sends only what changed. */
export type DocFields = {
  title?: string;
  source_of_truth?: DocSource;
  project_id?: string | null;
  body_markdown?: string;
  note?: string | null;
  git?: { repo: string; path: string; branch?: string; commit?: string };
  work_item_ids?: string[];
  decision_ids?: string[];
};

export type NewVersion = { based_on: number | null; body_markdown?: string; git_commit_sha?: string; note?: string };

const enc = encodeURIComponent;

function query(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') q.set(k, String(v));
  return q.toString();
}

export const documentsApi = {
  list: (f: { projectId?: string; q?: string }, signal?: AbortSignal) =>
    call<Doc[]>('GET', `/api/documents?${query({ limit: 500, project_id: f.projectId, q: f.q })}`, undefined, signal),
  detail: (id: string, version?: number, signal?: AbortSignal) =>
    call<DocDetail>('GET', `/api/documents/${enc(id)}${version === undefined ? '' : `?version=${version}`}`, undefined, signal),
  register: (fields: DocFields) => call<DocDetail>('POST', '/api/documents', fields),
  update: (id: string, version: number, fields: DocFields) => call<DocDetail>('PATCH', `/api/documents/${enc(id)}`, { version, ...fields }),
  addVersion: (id: string, body: NewVersion) => call<DocDetail>('POST', `/api/documents/${enc(id)}/versions`, body),
  approve: (id: string, number: number, note?: string) =>
    call<DocDetail>('POST', `/api/documents/${enc(id)}/versions/${number}/approvals`, { decision: 'approved', note }),
};
