import type { IconName } from '../icons/Icon';

/*
 * The screens and where they show. The sidebar, the bottom tab bar and the command line all
 * read this list, so a new screen is one entry here, not three edits. Screens keep their hash
 * address (#inbox, #work, ...) so a reload stays on the same screen.
 */

export type ScreenId = 'inbox' | 'sessions' | 'work' | 'decisions' | 'documents' | 'risk' | 'runs' | 'me' | 'org' | 'accounts' | 'audit' | 'kit' | 'server';

export type Screen = {
  id: ScreenId;
  label: string;
  /** The name in the bottom tab bar, where there is room for about four Korean letters. */
  short: string;
  icon: IconName;
  /** Other names the command line accepts. */
  aliases: string[];
  /** False for screens opened only by address, like the design kit. */
  inNav: boolean;
  /** What a count next to the name means, for the tooltip and screen readers. */
  countLabel?: (n: number) => string;
};

export const SCREENS: Screen[] = [
  { id: 'inbox', label: '나를 기다리는 것', short: '기다림', icon: 'inbox', aliases: ['기다림', 'inbox'], inNav: true, countLabel: (n) => `${n}건` },
  {
    id: 'sessions',
    label: '세션 현황',
    short: '세션',
    icon: 'session',
    aliases: ['세션', 'sessions'],
    inNav: true,
    countLabel: (n) => `살아 있는 세션 ${n}개`,
  },
  { id: 'work', label: '작업', short: '작업', icon: 'work', aliases: ['work'], inNav: true },
  { id: 'decisions', label: '결정', short: '결정', icon: 'decision', aliases: ['결정 기록', 'decisions'], inNav: true },
  { id: 'documents', label: '문서', short: '문서', icon: 'doc', aliases: ['documents', 'docs'], inNav: true },
  { id: 'risk', label: '위험 목록', short: '위험', icon: 'alert', aliases: ['위험 경로', '사람 검토', 'risk'], inNav: true },
  { id: 'runs', label: '실행 시험', short: '실행', icon: 'run', aliases: ['실행', 'runs'], inNav: true },
  { id: 'me', label: '나', short: '나', icon: 'person', aliases: ['내 기록', '매니저', 'me'], inNav: true },
  { id: 'org', label: '조직', short: '조직', icon: 'team', aliases: ['팀', '사람', '역할', '저장소', '환경', 'org'], inNav: true },
  { id: 'accounts', label: 'AI 계정', short: '계정', icon: 'key', aliases: ['계정', '설정', '상한', '사용량', 'accounts', 'settings'], inNav: true },
  // The icon set is at its 30-icon limit (DESIGN.md 「정한 것 넷」 2), so the audit log borrows the settings mark of its place.
  { id: 'audit', label: '감사 기록', short: '감사', icon: 'settings', aliases: ['감사', '보존', '내보내기', 'audit', 'export'], inNav: true },
  { id: 'kit', label: '디자인 키트', short: '키트', icon: 'doc', aliases: [], inNav: false },
  { id: 'server', label: '서버 관리', short: '서버', icon: 'settings', aliases: [], inNav: false },
];

/**
 * Sidebar groups. A one-person organisation has one group; a team adds 「나 / 팀 / AI / 저장한 보기」
 * here (DESIGN.md 「화면 배치」 1) and the sidebar draws group names once there is more than one.
 */
export const NAV_GROUPS: { id: string; label: string; items: ScreenId[] }[] = [
  { id: 'screens', label: '화면', items: ['inbox', 'sessions', 'work', 'decisions', 'documents', 'risk', 'runs'] },
];

/**
 * The screens at the foot of the sidebar, in the settings place (DESIGN.md 「화면 배치」 1: 설정 last):
 * 「나」 (my manager record, FN-0402), 「조직」 (the organisation's structure and roles, FN-0101), the AI
 * accounts and the audit log. Usage limits and retention are organisation settings (G39.4, G13.1): a
 * settings screen will hold the last three as its parts. On narrow screens all four are under 「더 보기」.
 */
export const FOOT_ITEMS: ScreenId[] = ['me', 'org', 'accounts', 'audit'];

/** The four screens in the bottom tab bar; the rest go under 「더 보기」. */
export const TAB_ITEMS: ScreenId[] = ['inbox', 'sessions', 'work', 'decisions'];

export function screenById(id: ScreenId): Screen {
  return SCREENS.find((s) => s.id === id)!;
}

/**
 * `#work` and `#/work` both open the work screen, and so does `#work?item=...`: what follows `?`
 * belongs to the screen (its filters and selection). Anything else opens the first screen.
 */
export function screenFromHash(hash: string): ScreenId {
  const id = hash.replace(/^#\/?/, '').split(/[?/]/)[0];
  return SCREENS.find((s) => s.id === id)?.id ?? 'inbox';
}

const norm = (s: string) => s.replace(/\s+/g, '').toLowerCase();
const names = (s: Screen) => [s.label, s.short, ...s.aliases].map(norm);

/**
 * The screen a command-line query names: an exact name first, then a name that starts with the
 * query, then one that contains it. A leading `>` (the command mark) is ignored. Screens opened
 * only by address are not offered.
 */
export function matchScreen(query: string): Screen | null {
  const q = norm(query.replace(/^\s*>/, ''));
  if (q === '') return null;
  const offered = SCREENS.filter((s) => s.inNav);
  return (
    offered.find((s) => names(s).includes(q)) ??
    offered.find((s) => names(s).some((n) => n.startsWith(q))) ??
    offered.find((s) => names(s).some((n) => n.includes(q))) ??
    null
  );
}

/** The screen whose name is exactly the query, ignoring spaces and case; used while the line also finds. */
export function exactScreen(query: string): Screen | null {
  const q = norm(query);
  if (q === '') return null;
  return SCREENS.find((s) => s.inNav && names(s).includes(q)) ?? null;
}

/** A leading `>` marks a command (a screen to open), not words to find. */
export const isCommand = (query: string) => /^\s*>/.test(query);

/**
 * Where Enter goes. On a screen that does not find, any name that matches (as before). On a
 * screen that finds, plain words are a search, so only an exact screen name or a `>` command
 * opens a screen; 「세」 should find work about sessions, not jump to 「세션 현황」.
 */
export function commandTarget(query: string, finding: boolean): Screen | null {
  if (!finding || isCommand(query)) return matchScreen(query);
  return exactScreen(query);
}
