import type { ActorClass, AuditEntry, AuditFacets } from '../auditApi';
import type { IconName } from '../icons/Icon';

/*
 * What the audit screen shows, as plain functions: the words for actors, targets and actions, the
 * filters and the address that keeps them, the day groups, where a target opens and how a row's
 * details read as a table. The server keeps the log and never changes a row; this only arranges it.
 */

export const ACTOR_CLASSES: ActorClass[] = ['person', 'session', 'run', 'system'];

export const ACTOR_LABELS: Record<ActorClass, string> = { person: '사람', session: '세션', run: '실행', system: '시스템' };

const ACTOR_ICONS: Record<ActorClass, IconName> = { person: 'person', session: 'session', run: 'run', system: 'settings' };

/** What each actor class covers, for the detail. */
const ACTOR_NOTES: Record<ActorClass, string> = {
  person: '사람이 화면이나 CLI로 한 일',
  session: '직접 연 AI 세션이 세션 토큰으로 보낸 것',
  run: 'zm-baton이 띄운 실행 하나가 보고한 것',
  system: '서버가 스스로 한 일(기기의 연결 프로그램, 마스터, 보존 규칙, 만료 처리 포함)',
};

/** The class of a row: the server's, or worked out the same way for an older server. */
export const actorClass = (e: Pick<AuditEntry, 'actor_class' | 'actor_kind' | 'actor_id'>): ActorClass =>
  e.actor_class ?? (e.actor_kind === 'system' && e.actor_id.startsWith('run_') ? 'run' : ((e.actor_kind as ActorClass) ?? 'system'));

export const actorIcon = (c: ActorClass): IconName => ACTOR_ICONS[c] ?? 'settings';
export const actorNote = (c: ActorClass) => ACTOR_NOTES[c] ?? '';

/** Target kinds by their prefix (contract 「식별자」 and the prefixes the server added). */
export const TARGET_LABELS: Record<string, string> = {
  wrk: '작업',
  dcs: '결정',
  doc: '문서',
  ses: '세션',
  run: '실행',
  ntc: '알림',
  inb: '첫 화면 항목',
  acc: 'AI 계정',
  dev: '기기',
  usr: '사람 설정',
  org: '조직',
  prj: '프로젝트',
  mck: '모순 찾기',
  api: '거절한 요청',
  none: '대상 없음',
};

export const targetLabel = (kind: string | null) => (kind === null ? TARGET_LABELS.none : (TARGET_LABELS[kind] ?? kind));

/** Actions in words. An action the screen does not know shows as the server names it. */
const ACTIONS: Record<string, string> = {
  'device.registered': '기기 등록',
  'device.token_issued': '기기 토큰 발급',
  'device.revoked': '기기 폐기',
  'device_login.approved': '기기 로그인 허락',
  'device_login.denied': '기기 로그인 거절',
  'auth.login': '로그인',
  'auth.logout': '로그아웃',
  'auth.login_failed': '로그인 실패',
  'auth.password_set': '설정 토큰으로 비밀번호 정함',
  'auth.password_changed': '비밀번호 바꿈',
  'auth.password_change_refused': '비밀번호 바꾸기 거절(지금 비밀번호가 틀림)',
  'auth.setup_token_issued': '설정 토큰 발급',
  'auth.setup_refused': '설정 토큰 거절',
  'device.repository_added': '기기에 저장소 등록',
  'device.repository_removed': '기기에서 저장소 뺌',
  'session.registered': '세션 등록',
  'session.reregistered': '세션 다시 등록',
  'session.sharing_changed': '하는 일 공유 바꿈',
  'session.ended': '세션 끝남',
  'session.deregistered': '세션 등록 끔',
  'session.expired': '세션 토큰 만료',
  'person_action.refused': '사람이 할 일을 세션 토큰으로 요청해 거절',
  'notice.raised': '알림 만듦',
  'notice.reopened': '알림 다시 엶',
  'notice.acknowledged': '알림 확인',
  'notice.not_a_conflict': '알림을 충돌·모순 아님으로 판정',
  'notice.resolved': '알림 해결',
  'focus.changed': '집중 모드 바꿈',
  'relay.to_terminal': '중계를 터미널로 넘김',
  'relay.delivered': '중계한 답을 훅이 가져감',
  'relay.not_relayed': '중계하지 않음',
  'run.requested': '실행 시도 만듦',
  'run.claimed': '실행 청구',
  'run.ended': '실행 끝남',
  'run.lost': '실행 연결 끊김',
  'run.stop_requested': '실행 멈춤 요청',
  'run.input_sent': '실행 중인 AI에게 지시',
  'run.refused_by_account': '계정이 멈춰 실행을 시작하지 않음',
  'work_item.created': '작업 만듦',
  'work_item.updated': '작업 고침',
  'work_item.stage_changed': '작업 단계 넘김',
  'work_item.wait_added': '대기 사유 닮',
  'work_item.wait_removed': '대기 사유 뗌',
  'work_item.delegated': 'AI에게 맡김',
  'work_item.delegation_withdrawn': '맡긴 일 거둠',
  'work_item.imported': '작업 가져옴',
  'work_item.synced': '가져온 작업 갱신',
  'workflow.skipped_stages': '쓰지 않는 검토 단계 바꿈',
  'decision.proposed': '결정 제안',
  'decision.updated': '결정 고침',
  'decision.accepted': '결정 채택',
  'decision.rejected': '결정 기각',
  'decision.superseded': '결정이 대체됨',
  'decision.imported': '결정 가져옴',
  'decision.synced': '가져온 결정 갱신',
  'document.registered': '문서 등록',
  'document.updated': '문서 정보 고침',
  'document.version_added': '문서판 더함',
  'document.version_approved': '문서판 승인',
  'project.imported': '프로젝트 가져옴',
  'project.synced': '가져온 프로젝트 갱신',
  'import.zm_docs.applied': 'zm-docs 가져오기',
  'import.zm_docs.dry_run': 'zm-docs 가져오기 미리 보기',
  'export.downloaded': '내보내기 파일 받음',
  'retention.purged': '보존 기간이 지난 기록 지움',
  'account.registered': 'AI 계정 등록',
  'account.updated': 'AI 계정 고침',
  'account.disabled': 'AI 계정 비활성화',
  'account.enabled': 'AI 계정 다시 씀',
  'account.billing_resumed': '추가 과금 멈춤 풂',
  'account.billing_stopped': '추가 과금 신호로 계정 멈춤',
  'account.limit_reached': '공급자 한도로 계정 멈춤',
  'master_check.requested': '모순 찾기 요청',
  'master_check.skipped': '모순 찾기 건너뜀',
  'master_check.fallback': '모순 찾기를 연결 프로그램에 넘김',
  'master_check.done': '모순 찾기 끝남',
  'master_check.failed': '모순 찾기 실패',
};

export const actionLabel = (action: string) => ACTIONS[action] ?? (action.startsWith('relay.') ? `중계: ${action.slice(6)}` : action);

/** Whether the screen has words for the action; otherwise the row shows the action's code. */
export const knownAction = (action: string) => action in ACTIONS;

// ---------------------------------------------------------------- filters and address

export type Period = '' | 'today' | '7d' | '30d';

export const PERIOD_LABELS: Record<Period, string> = { '': '전체', today: '오늘', '7d': '최근 7일', '30d': '최근 30일' };

export type AuditFilters = { actor: ActorClass | ''; target: string; period: Period; targetId: string };

export const NO_FILTERS: AuditFilters = { actor: '', target: '', period: '', targetId: '' };

/** The time the period starts at, from [now], in the browser's zone; empty for 「전체」. */
export function periodAfter(period: Period, now: number): string {
  if (period === '') return '';
  const d = new Date(now);
  d.setHours(0, 0, 0, 0);
  if (period === '7d') d.setDate(d.getDate() - 6);
  if (period === '30d') d.setDate(d.getDate() - 29);
  return d.toISOString();
}

/** The filters in words, for the empty state. */
export function conditions(f: AuditFilters): string[] {
  const out: string[] = [];
  if (f.actor) out.push(`행위자 ${ACTOR_LABELS[f.actor]}`);
  if (f.target) out.push(`대상 ${targetLabel(f.target)}`);
  if (f.period) out.push(PERIOD_LABELS[f.period]);
  if (f.targetId) out.push(`대상 번호 ${f.targetId}`);
  return out;
}

export const hasFilters = (f: AuditFilters) => conditions(f).length > 0;

/** The selection, the filters and the open pane, kept in the address (`#audit?actor=run&period=7d&item=aud_…`). */
export type AuditView = AuditFilters & { selected: string | null; pane: '' | 'retention' };

export function readView(hash: string): AuditView {
  const at = hash.indexOf('?');
  const p = new URLSearchParams(at < 0 ? '' : hash.slice(at + 1));
  const actor = p.get('actor') ?? '';
  const period = p.get('period') ?? '';
  return {
    actor: (ACTOR_CLASSES as string[]).includes(actor) ? (actor as ActorClass) : '',
    target: /^[a-z_]{1,20}$/.test(p.get('target') ?? '') ? (p.get('target') as string) : '',
    period: period in PERIOD_LABELS ? (period as Period) : '',
    targetId: (p.get('tid') ?? '').slice(0, 100),
    selected: p.get('item') || null,
    pane: p.get('pane') === 'retention' ? 'retention' : '',
  };
}

export function writeView(v: AuditView): string {
  const p = new URLSearchParams();
  if (v.actor) p.set('actor', v.actor);
  if (v.target) p.set('target', v.target);
  if (v.period) p.set('period', v.period);
  if (v.targetId) p.set('tid', v.targetId);
  if (v.pane) p.set('pane', v.pane);
  else if (v.selected) p.set('item', v.selected);
  const q = p.toString();
  return q ? `#audit?${q}` : '#audit';
}

// ---------------------------------------------------------------- list

/** One day of rows, newest day first; within a day the server's order (newest first). */
export type DayGroup = { day: string; label: string; items: AuditEntry[] };

const pad = (n: number) => String(n).padStart(2, '0');

/** `2026-10-14` in the browser's zone. */
export const dayOf = (iso: string) => {
  const d = new Date(iso);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
};

/** `13:04:59` in the browser's zone. */
export const timeOf = (iso: string) => {
  const d = new Date(iso);
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
};

export function groupByDay(items: AuditEntry[], now: number): DayGroup[] {
  const today = dayOf(new Date(now).toISOString());
  const y = new Date(now);
  y.setDate(y.getDate() - 1);
  const yesterday = dayOf(y.toISOString());
  const groups: DayGroup[] = [];
  for (const e of items) {
    const day = dayOf(e.at);
    let g = groups[groups.length - 1];
    if (!g || g.day !== day) {
      g = { day, label: day === today ? `오늘 ${day}` : day === yesterday ? `어제 ${day}` : day, items: [] };
      groups.push(g);
    }
    g.items.push(e);
  }
  return groups;
}

export const rowOrder = (groups: DayGroup[]) => groups.flatMap((g) => g.items.map((e) => e.id));

/** 「wrk_01J9…7f3c」: enough to tell ids apart in a row. */
export const shortId = (id: string) => (id.length > 14 ? `${id.slice(0, 8)}…${id.slice(-4)}` : id);

/** The rows read at once: 100 a page, at most five pages (the server's limit is 500). */
export const PAGE = 100;
export const MAX_PAGES = 5;

export type ListState =
  | { kind: 'loading' }
  | { kind: 'error'; message: string }
  | { kind: 'unused' }
  | { kind: 'filtered'; conditions: string[] }
  | { kind: 'rows' };

export function listState(items: AuditEntry[] | null, error: string | null, f: AuditFilters): ListState {
  if (items === null) return error === null ? { kind: 'loading' } : { kind: 'error', message: error };
  if (items.length > 0) return { kind: 'rows' };
  return hasFilters(f) ? { kind: 'filtered', conditions: conditions(f) } : { kind: 'unused' };
}

/** The chips' target kinds: the ones the server counted, most rows first, without empty ones. */
export function targetOptions(facets: AuditFacets | null, chosen: string): { kind: string; count: number }[] {
  const out = Object.entries(facets?.target_kinds ?? {}).map(([kind, count]) => ({ kind, count }));
  if (chosen && !out.some((o) => o.kind === chosen)) out.push({ kind: chosen, count: 0 });
  return out.sort((a, b) => b.count - a.count || a.kind.localeCompare(b.kind));
}

// ---------------------------------------------------------------- detail

/**
 * Where a row's target opens: its own screen for work, decisions, documents, sessions, AI accounts
 * and first-screen items; a run's or master check's work item for those. Null when it has no screen.
 */
export function targetHref(e: Pick<AuditEntry, 'target_kind' | 'target_id' | 'target_work_item_id'>): string | null {
  const id = e.target_id;
  if (!id) return null;
  const enc = encodeURIComponent;
  switch (e.target_kind) {
    case 'wrk':
      return `#work?closed=1&item=${enc(id)}`;
    case 'dcs':
      return `#decisions?past=1&item=${enc(id)}`;
    case 'doc':
      return `#documents?item=${enc(id)}`;
    case 'ses':
      return `#sessions?ended=1&item=${enc(id)}`;
    case 'acc':
      return `#accounts?item=${enc(id)}`;
    case 'inb':
      return `#inbox?item=${enc(id)}`;
    case 'run':
    case 'mck':
      return e.target_work_item_id ? `#work?closed=1&item=${enc(e.target_work_item_id)}` : null;
    default:
      return null;
  }
}

/** What following a target's link opens, in words. */
export function targetLinkLabel(kind: string | null): string {
  if (kind === 'run') return '그 실행의 작업과 실행 기록 열기';
  if (kind === 'mck') return '그 모순 찾기의 작업과 실행 기록 열기';
  return `${targetLabel(kind)} 화면에서 열기`;
}

/** One line of a row's details: a key, and its value as text or as JSON for nested values. */
export type DetailRow = { key: string; value: string; json: boolean };

export function detailRows(details: Record<string, unknown> | null | undefined): DetailRow[] {
  return Object.entries(details ?? {}).map(([key, v]) => {
    if (v === null || v === undefined) return { key, value: '없음', json: false };
    if (typeof v === 'string') return { key, value: v === '' ? '(빈 글)' : v, json: false };
    if (typeof v === 'number' || typeof v === 'boolean') return { key, value: String(v), json: false };
    if (Array.isArray(v) && v.every((x) => typeof x === 'string' || typeof x === 'number')) {
      return { key, value: v.length === 0 ? '(없음)' : v.join(', '), json: false };
    }
    return { key, value: JSON.stringify(v, null, 2), json: true };
  });
}

/** 「영원히 둔다」 or 「90일」. */
export const keepLabel = (days: number | null) => (days === null ? '영원히 둔다' : `${days}일`);
