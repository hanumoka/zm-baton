import type { DecisionFields } from '../decisionsApi';
import type { DecisionDetail, DecisionRef, HistoryEntry } from '../workApi';
import { refOf } from './decisionView';

/*
 * The decision form as plain data: what the fields hold, how a decision fills them, what the
 * server is sent and whether it can be sent yet. The server checks every field again; this only
 * keeps the form honest about what it will send.
 */

export type DraftOption = { key: string; title: string; body: string };

export type DecisionDraft = {
  question: string;
  context: string;
  options: DraftOption[];
  /** The chosen option's key, or '' for none. */
  chosen: string;
  choice: string;
  rationale: string;
  review: string;
  driver: string;
  /** Names separated by commas, as typed. */
  contributors: string;
  informed: string;
  projectIds: string[];
  workIds: string[];
  /** The decision this one replaces when accepted. */
  supersedes: DecisionRef | null;
};

export function emptyDraft(projectId = ''): DecisionDraft {
  return {
    question: '',
    context: '',
    options: [],
    chosen: '',
    choice: '',
    rationale: '',
    review: '',
    driver: 'usr_owner',
    contributors: '',
    informed: '',
    projectIds: projectId ? [projectId] : [],
    workIds: [],
    supersedes: null,
  };
}

/** A proposal as the form shows it for a change. */
export function draftFrom(d: DecisionDetail): DecisionDraft {
  return {
    question: d.decision.question,
    context: d.context ?? '',
    options: d.options.map((o) => ({ key: o.key, title: o.title, body: o.body ?? '' })),
    chosen: d.chosen_option ?? '',
    choice: d.choice ?? '',
    rationale: d.rationale ?? '',
    review: d.review_conditions ?? '',
    driver: d.roles.driver ?? '',
    contributors: d.roles.contributors.join(', '),
    informed: d.roles.informed.join(', '),
    projectIds: d.decision.projects.map((p) => p.id),
    workIds: d.work_items.map((w) => w.id),
    supersedes: d.decision.supersedes,
  };
}

/** A new proposal that replaces [d] when accepted: the same question and projects to start from. */
export function supersedeDraft(d: DecisionDetail): DecisionDraft {
  const v = d.decision;
  return {
    ...emptyDraft(),
    question: v.question,
    projectIds: v.projects.map((p) => p.id),
    workIds: d.work_items.map((w) => w.id),
    supersedes: { id: v.id, external_ref: v.external_ref, question: v.question, ref: v.ref },
  };
}

const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');
const KEYS = [...LETTERS, ...LETTERS.flatMap((a) => LETTERS.map((b) => a + b))];

/** The next option key no current or earlier option took, so a removed letter does not come back. */
export function nextKey(taken: string[]): string {
  return KEYS.find((k) => !taken.includes(k)) ?? `${taken.length + 1}`;
}

const names = (s: string) => [...new Set(s.split(/[,\n]/).map((n) => n.trim()).filter(Boolean))];
const textOrNull = (s: string) => (s.trim() === '' ? null : s.trim());

/** Every field, for a new proposal. */
export function draftFields(d: DecisionDraft): DecisionFields {
  return {
    question: d.question.trim(),
    context: textOrNull(d.context),
    options: d.options.filter((o) => o.title.trim() !== '').map((o) => ({ key: o.key, title: o.title.trim(), body: textOrNull(o.body) })),
    chosen_option: d.chosen === '' || !d.options.some((o) => o.key === d.chosen && o.title.trim() !== '') ? null : d.chosen,
    choice: textOrNull(d.choice),
    rationale: textOrNull(d.rationale),
    review_conditions: textOrNull(d.review),
    roles: { driver: textOrNull(d.driver), contributors: names(d.contributors), informed: names(d.informed) },
    project_ids: d.projectIds,
    work_item_ids: d.workIds,
    supersedes_id: d.supersedes?.id ?? null,
  };
}

/** Only the fields that differ from [before], for a change; empty when nothing changed. */
export function changedFields(before: DecisionDraft, after: DecisionDraft): DecisionFields {
  const a = draftFields(before);
  const b = draftFields(after);
  const out: Record<string, unknown> = {};
  for (const k of Object.keys(b) as (keyof DecisionFields)[]) {
    if (JSON.stringify(a[k]) !== JSON.stringify(b[k])) out[k] = b[k];
  }
  return out as DecisionFields;
}

/** Why the form cannot be sent yet, or null. */
export function draftProblem(d: DecisionDraft): string | null {
  if (d.question.trim() === '') return '질문을 쓰면 보낼 수 있다.';
  if (d.options.some((o) => o.title.trim() === '' && o.body.trim() !== '')) return '설명을 적은 선택지에는 이름도 쓴다.';
  return null;
}

const FIELD_LABELS: Record<string, string> = {
  question: '질문',
  context: '맥락',
  options: '선택지',
  chosen_option: '고른 안',
  choice: '결정',
  rationale: '근거',
  review_conditions: '재검토 조건',
  roles: '결정 역할',
  project_ids: '프로젝트',
  work_item_ids: '관련 작업',
  supersedes_id: '대체할 결정',
};

/** One line for an entry of a decision's history. */
export function decisionHistoryLine(h: HistoryEntry): string {
  const d = h.details;
  const note = d.note ? ` — ${String(d.note)}` : '';
  switch (h.action) {
    case 'decision.proposed':
      return `제안함 (${String(d.ref ?? '')})`;
    case 'decision.updated':
      return `고침: ${((d.fields as string[] | undefined) ?? []).map((f) => FIELD_LABELS[f] ?? f).join(', ')}`;
    case 'decision.accepted':
      return `채택함${d.supersedes ? `, ${String(d.supersedes)}을(를) 대체` : ''}${note}`;
    case 'decision.rejected':
      return `기각함${note}`;
    case 'decision.superseded':
      return `${String(d.by ?? '다른 결정')}이(가) 대체함`;
    default:
      return h.action;
  }
}

/** 「DCS-0003 질문」 for the decision a form replaces. */
export const supersedesLabel = (r: DecisionRef) => [refOf(r), r.question].filter(Boolean).join(' ');
