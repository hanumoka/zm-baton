// Server API for document contradiction candidates (contract v1 "문서 모순 후보"): the candidates and
// the master's checks about one document or decision, and the master's settings. Same origin as
// the page; a candidate is judged like a conflict, with sessionsApi.judgeNotice.

import { call } from './workApi';

/** One side of a candidate: a document version or a decision, and the sentence quoted from it. */
export type ContradictionSide = {
  kind: 'document' | 'decision' | string;
  id: string;
  version_id: string | null;
  number: number | null;
  ref: string | null;
  title: string | null;
  label: string | null;
  quote: string | null;
};

export type ContradictionStatus = 'open' | 'acknowledged' | 'not_a_conflict' | 'resolved';

export type Contradiction = {
  id: string;
  status: ContradictionStatus;
  reason: string | null;
  sides: ContradictionSide[];
  check_id: string | null;
  run_id: string | null;
  work_item_id: string | null;
  path: 'api' | 'local' | null;
  repeat_count: number;
  created_at: string;
  last_seen_at: string;
  status_by: string | null;
  status_at: string | null;
  status_note: string | null;
  /** The open first-screen item for it, when there is one. */
  inbox_item_id: string | null;
};

export type MasterCheckState = 'requested' | 'running' | 'done' | 'failed' | 'skipped';

export type MasterCheck = {
  id: string;
  trigger: string;
  state: MasterCheckState;
  path: 'api' | 'local' | null;
  reason: string | null;
  subjects: Record<string, unknown>[];
  compared: { key: string; kind: string; id: string; label: string; linked: boolean }[];
  work_item_id: string | null;
  run_id: string | null;
  api_run_id: string | null;
  outcome: { proposed?: number; kept?: number; screened?: number | null; notice_ids?: string[] } | null;
  created_at: string;
  finished_at: string | null;
};

export type ContradictionsAbout = {
  /** Open or acknowledged, newest first. */
  open: Contradiction[];
  closed_count: number;
  /** Its newest checks, newest first. */
  checks: MasterCheck[];
};

export const contradictionsApi = {
  about: (id: string, signal?: AbortSignal) => call<ContradictionsAbout>('GET', `/api/contradictions?about=${encodeURIComponent(id)}`, undefined, signal),
};
