import type { IconName } from '../icons/Icon';
import { BUCKETS, groupByBucket, inboxHref } from '../inboxModel';
import { sessionsApi, type InboxItem, type NoticeStatus, type SessionView } from '../sessionsApi';
import { isRelay, relayDetailTitle, relayed, relayTitle } from './relayView';

/*
 * What the first screen shows, as plain functions: the five buckets, what kind each item is and
 * what it says, the sessions it names, the judgement a conflict takes, the address that keeps the
 * selected item and which empty state applies. The server decides what waits for me and in which
 * order; this only arranges what it returned.
 */

export type Bucket = InboxItem['bucket'];

const MARKS: Record<Bucket, IconName> = { 1: 'lock', 2: 'wait', 3: 'conflict', 4: 'alert', 5: 'bell' };

export type InboxGroup = {
  bucket: Bucket;
  label: string;
  mark: IconName;
  /** False while the server has no path that raises items here (inboxModel BUCKETS). */
  raised: boolean;
  items: InboxItem[];
};

/** The five groups in the contract's order, each with the longest wait first. Every group shows, even empty. */
export function inboxGroups(items: InboxItem[]): InboxGroup[] {
  const by = groupByBucket(items);
  return BUCKETS.map(({ bucket, label, raised }) => ({ bucket, label, mark: MARKS[bucket], raised, items: by.get(bucket) ?? [] }));
}

/** The rows in the order they are drawn, for the arrow keys. */
export const rowOrder = (groups: InboxGroup[]) => groups.flatMap((g) => g.items.map((i) => i.id));

/** What an empty group says: a group the server fills says it is clear; one it does not fill yet says so. */
export function groupNote(g: Pick<InboxGroup, 'bucket' | 'raised'>): string {
  if (!g.raised) return '서버가 아직 만들지 않는다';
  if (g.bucket === 1) return '권한 요청이 없다';
  if (g.bucket === 2) return '기다리는 질문과 세션이 없다';
  if (g.bucket === 4) return '사람이 볼 위험 결과가 없다';
  if (g.bucket === 5) return '고른 표본과 알림이 없다';
  return '겹친 파일이 없다';
}

/**
 * Relays are off by default until person authentication exists: any program on this PC could answer
 * one (hardening backlog H-21). Turning them on is a server setting (ZM_BATON_RELAY=on), never a button here.
 */
export const RELAY_OFF_NOTE = '꺼져 있다(사람 인증이 생기기 전에는 기본 꺼짐)';

/** What a bucket's head says beside its name: relays off, focus mode, or why it is empty. */
export function groupAside(g: Pick<InboxGroup, 'bucket' | 'raised'>, waiting: number, focusOn: boolean, relayOn: boolean | null): string | undefined {
  if (relayOn === false && g.bucket === 1) return RELAY_OFF_NOTE;
  if (focusOn && relayOn !== false) {
    const note = focusNote(g.bucket);
    if (note) return note;
  }
  return waiting === 0 ? groupNote(g) : undefined;
}

/** While focus mode is on, the buckets that relay say what it changes (contract 「알림과 집중 모드」 4, relay 12). */
export function focusNote(bucket: Bucket): string | null {
  if (bucket === 1) return '집중 모드: 새 요청은 터미널에서';
  if (bucket === 2) return '집중 모드: 새 질문은 터미널에서';
  return null;
}

export type KindView = { mark: IconName; label: string };

/** Short names of a waiting session's kinds (contract 「세션이 보내는 사건」 waiting). */
const WAIT_LABELS: Record<string, { short: string; long: string }> = {
  permission_prompt: { short: '권한 확인', long: 'AI 세션이 터미널에서 권한 확인을 기다린다' },
  idle_prompt: { short: '다음 지시', long: 'AI 세션이 할 일을 마치고 다음 지시를 기다린다' },
  elicitation_dialog: { short: '선택 질문', long: 'AI 세션이 터미널에서 선택형 질문의 답을 기다린다' },
  elicitation_url_dialog: { short: '선택 질문', long: 'AI 세션이 터미널에서 선택형 질문의 답을 기다린다' },
  agent_needs_input: { short: '입력 필요', long: 'AI 세션이 터미널에서 입력을 기다린다' },
};
const WAIT_OTHER = { short: '입력 기다림', long: 'AI 세션이 터미널에서 입력을 기다린다' };

/** Kinds named as the contract does. The server does not raise run_question, risk_review and sample_review yet. */
const OTHER_KINDS: Record<string, KindView> = {
  permission_request: { mark: 'lock', label: '권한 요청' },
  run_question: { mark: 'ai', label: '실행의 질문' },
  session_question: { mark: 'ai', label: '선택 질문' },
  risk_review: { mark: 'alert', label: '위험 검토' },
  sample_review: { mark: 'done', label: '표본 검토' },
};

const text = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v : null);

/** The wait kind of a waiting session: the item's own field, else its detail. */
const waitKind = (item: InboxItem) => item.wait_kind ?? text(item.detail?.wait_kind) ?? '';

/** A notice that an AI account's usage limit or a provider's signal stopped work (server UsageService), not a conflict. */
export const isUsageAlert = (item: Pick<InboxItem, 'kind' | 'detail'>) => item.kind === 'notice' && item.detail?.notice_kind === 'usage_alert';

/** A document contradiction candidate the master found (contract 「문서 모순 후보」, server ContradictionNotices). */
export const isContradiction = (item: Pick<InboxItem, 'kind' | 'detail'>) => item.kind === 'notice' && item.detail?.notice_kind === 'doc_contradiction';

/** The two sides a contradiction candidate quotes, by their labels. */
function contradictionTitle(item: InboxItem): string {
  const sides = Array.isArray(item.detail?.sides) ? (item.detail.sides as Record<string, unknown>[]) : [];
  const labels = sides.map((s) => text(s?.label)).filter((l): l is string => l !== null);
  return labels.length === 2 ? `${labels[0]}, ${labels[1]}` : item.summary;
}

/** The kind on a row: a shape and a word, never colour alone (DESIGN.md 「색」). */
export function kindOf(item: InboxItem): KindView {
  if (isUsageAlert(item)) return { mark: 'alert', label: '사용량' };
  if (isContradiction(item)) return { mark: 'doc', label: '모순 후보' };
  if (item.kind === 'notice') return { mark: 'conflict', label: '충돌' };
  if (item.kind === 'session_waiting') return { mark: 'wait', label: (WAIT_LABELS[waitKind(item)] ?? WAIT_OTHER).short };
  if (item.kind === 'run_question') return { mark: 'ai', label: item.detail?.from_run === true ? '실행의 질문' : '질문' };
  return OTHER_KINDS[item.kind] ?? { mark: MARKS[item.bucket] ?? 'inbox', label: '알림' };
}

/** The conflicting file of a notice, as the server's evidence names it. */
export const filePath = (item: InboxItem): string | null => (item.kind === 'notice' ? text(item.detail?.path) : null);

/** The words a waiting session's executor sent (only 「Claude needs your permission to use <도구>」 is ever sent). */
export const waitMessage = (item: InboxItem): string | null => (item.kind === 'session_waiting' ? text(item.detail?.message) : null);

/** A session the item names: who touched the file, or the session that waits. */
export type ItemSession = { id: string; executor: string | null; branch: string | null; touchedAt: string | null };

export function itemSessions(item: InboxItem): ItemSession[] {
  if (item.kind === 'session_waiting' || item.source_kind === 'ses') {
    const where = item.detail?.work_location;
    const branch = text(item.detail?.branch) ?? (where && typeof where === 'object' ? text((where as Record<string, unknown>).branch) : null);
    return [{ id: item.source_id, executor: null, branch, touchedAt: null }];
  }
  const raw = item.detail?.sessions;
  if (!Array.isArray(raw)) return [];
  return raw.flatMap((s): ItemSession[] => {
    if (typeof s !== 'object' || s === null) return [];
    const r = s as Record<string, unknown>;
    const id = text(r.session_id);
    return id ? [{ id, executor: text(r.executor), branch: text(r.branch), touchedAt: text(r.touched_at) }] : [];
  });
}

/** The live session with this id, or null when it is not live (or the list has not arrived). */
export const liveSession = (sessions: SessionView[] | null, id: string) => sessions?.find((s) => s.id === id && s.state === 'live') ?? null;

/** What the session works on, when it shares it. */
export const sharedWork = (s: SessionView | null) => (s && s.shared ? text(s.current_work) : null);

/**
 * A row's title, cut to one line: the file two sessions touched; for a waiting session the words
 * its executor sent, else what the session works on, else what it waits for.
 */
export function rowTitle(item: InboxItem, sessions: SessionView[] | null): string {
  if (isContradiction(item)) return contradictionTitle(item);
  if (item.kind === 'notice') return filePath(item) ?? item.summary;
  if (isRelay(item)) return relayTitle(item);
  // A review request: the work item it is about; its file goes on the second line.
  if (item.kind === 'risk_review' || item.kind === 'sample_review') return text(item.detail?.title) ?? item.summary;
  // A question: what is asked; the work item it is about goes on the second line.
  if (item.kind === 'run_question') return text(item.detail?.body) ?? item.summary;
  if (item.kind === 'session_waiting') {
    return waitMessage(item) ?? sharedWork(liveSession(sessions, item.source_id)) ?? (WAIT_LABELS[waitKind(item)] ?? WAIT_OTHER).long;
  }
  return item.summary;
}

/** A review request's first caught (or changed) file and how many more, for its second line. */
export function reviewFiles(item: InboxItem): { first: string; more: number } | null {
  const files = item.detail?.files;
  if (!Array.isArray(files) || files.length === 0 || typeof files[0] !== 'string') return null;
  return { first: files[0], more: files.length - 1 };
}

/** The detail's title: what waits for me, in one sentence. */
export function detailTitle(item: InboxItem): string {
  if (isUsageAlert(item)) return item.summary;
  if (isContradiction(item)) return '두 기록이 서로 어긋나 보인다';
  if (item.kind === 'notice') return '두 세션이 같은 파일을 고쳤다';
  if (isRelay(item)) return relayDetailTitle(item);
  if (item.kind === 'session_waiting') return (WAIT_LABELS[waitKind(item)] ?? WAIT_OTHER).long;
  return item.summary;
}

/** Why it waits for me: what happened and what my part is. */
export function whyMe(item: InboxItem): string {
  if (isUsageAlert(item)) {
    return '맡긴 AI 실행이 AI 계정의 사용량 상한이나 공급자의 신호에 닿았다. 실행을 멈췄으면 그 작업에 대기 사유가 붙어 있다. 작업과 계정을 확인한 뒤 「확인」을 누르면 이 목록에서 빠진다.';
  }
  if (isContradiction(item)) {
    return '문서판이 승인되거나 결정이 채택되어 마스터가 관련 문서·결정과 비교했고, 함께 참일 수 없어 보이는 두 문장을 찾았다. 서버가 두 문장이 실제 본문에 있는지 확인했다. 후보일 뿐이고 일을 막지 않는다. 두 기록을 읽고 판정하면 이 목록에서 빠진다.';
  }
  if (item.kind === 'notice') {
    return '내가 등록한 세션 둘이 같은 저장소의 같은 파일을 고쳤다. 이 알림은 일을 막지 않는다. 겹친 것이 문제인지 보고 판정하면 이 목록에서 빠진다.';
  }
  if (item.kind === 'permission_request') {
    if (!relayed(item)) return '세션이 도구를 쓰기 전에 허락을 묻는다. 이 요청은 중계하지 않아 그 세션의 터미널에서만 답할 수 있다.';
    return '세션이 도구를 쓰기 전에 허락을 묻는다. 터미널에도 같은 질문이 떠 있고, 먼저 답한 쪽이 쓰인다. 여기서 답하면 그 세션의 훅이 실행기에 넘긴다. 허용해도 조직의 거부 규칙은 넘지 못한다.';
  }
  if (item.kind === 'session_question') {
    if (!relayed(item)) return '세션이 선택지를 골라 달라고 묻는다. 중계하지 않아 그 세션의 터미널에 질문 창이 떠 있다.';
    return '세션이 선택지를 골라 달라고 묻는다. 중계하는 동안에는 터미널에 질문 창이 뜨지 않는다. 기한 안에 고르거나 터미널로 넘긴다.';
  }
  if (item.kind === 'session_waiting') {
    const k = waitKind(item);
    if (k === 'permission_prompt') return '세션이 도구를 쓰기 전에 터미널에서 허락을 묻고 있다. 답할 때까지 그 세션은 멈춰 있다.';
    if (k === 'idle_prompt') return '세션이 턴을 끝내고 다음 지시를 기다린다. 일을 마친 것일 수도, 글로 질문을 남긴 것일 수도 있다.';
    return '세션이 터미널에서 사람의 입력을 기다린다. 입력할 때까지 그 세션은 멈춰 있다.';
  }
  return '이 종류는 서버가 아직 만들지 않는다. 계약에는 있어서 화면이 이름만 보인다.';
}

/** Whether the server alerts at once or in the twice-daily digest (contract 「알림과 집중 모드」). */
export const notifyLabel = (item: Pick<InboxItem, 'notify'>) => (item.notify === 'immediate' ? '바로 알림' : '묶어 알림');

/** The three judgements of a conflict (contract 「알림 처리」), with what each leaves behind. */
export const JUDGEMENTS: { status: NoticeStatus; label: string; result: string }[] = [
  { status: 'acknowledged', label: '확인', result: '읽었다. 「확인함」으로 남는다' },
  { status: 'not_a_conflict', label: '충돌 아님', result: '이유를 적어 남긴다' },
  { status: 'resolved', label: '해결', result: '고쳐서 겹침이 사라졌다' },
];

/** The same three for a contradiction candidate, in its own words (contract 「알림 처리」). */
export const CONTRADICTION_JUDGEMENTS: { status: NoticeStatus; label: string; result: string }[] = [
  { status: 'acknowledged', label: '확인', result: '읽었다. 「확인함」으로 남는다' },
  { status: 'not_a_conflict', label: '모순 아님', result: '이유를 적어 남긴다' },
  { status: 'resolved', label: '해결', result: '기록을 고쳐 어긋남이 사라졌다' },
];

/** The judgements an item offers: a usage notice is only acknowledged; a conflict or a contradiction takes all three. */
export const judgementsFor = (item: Pick<InboxItem, 'kind' | 'detail'>) =>
  isUsageAlert(item) ? JUDGEMENTS.filter((j) => j.status === 'acknowledged') : isContradiction(item) ? CONTRADICTION_JUDGEMENTS : JUDGEMENTS;

/** What a judgement sends; null for 「충돌 아님」 without a reason, which the server refuses, so nothing is sent. */
export function judgement(status: NoticeStatus, reason: string): { status: NoticeStatus; note?: string } | null {
  if (status !== 'not_a_conflict') return { status };
  const note = reason.trim();
  return note === '' ? null : { status, note };
}

export type Send = (noticeId: string, status: NoticeStatus, note?: string) => Promise<unknown>;

/**
 * Judges the conflict notice behind an inbox item (POST /api/notices/{id}/status). 「충돌 아님」
 * without a reason is not sent: the reason field opens instead.
 */
export async function sendJudgement(item: Pick<InboxItem, 'source_id'>, status: NoticeStatus, reason: string, send: Send = sessionsApi.judgeNotice): Promise<'sent' | 'needs-reason'> {
  const body = judgement(status, reason);
  if (body === null) return 'needs-reason';
  await send(item.source_id, body.status, body.note);
  return 'sent';
}

/** The state of the whole list: loading, a failed read, nothing waiting, or rows. */
export type ListState = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'empty' } | { kind: 'rows' };

export function listState(items: InboxItem[] | null, error: string | null): ListState {
  if (items === null) return error === null ? { kind: 'loading' } : { kind: 'error', message: error };
  return items.length === 0 ? { kind: 'empty' } : { kind: 'rows' };
}

/** The selected item, kept in the address (`#inbox?item=inb_…`) so a reload or a link opens it. */
export type InboxView = { selected: string | null };

export function readView(hash: string): InboxView {
  const at = hash.indexOf('?');
  const p = new URLSearchParams(at < 0 ? '' : hash.slice(at + 1));
  return { selected: p.get('item') || null };
}

export function writeView(v: InboxView): string {
  return v.selected ? inboxHref(v.selected) : '#inbox';
}
