import type { ReactNode } from 'react';

/*
 * Our own icons (apps/web/DESIGN.md 「정한 것 넷」 2): a 16px grid, round line ends, and the line
 * width from the `--stroke` token. The shapes carry no colour or width; `.ico` in ui/ui.css sets
 * them, so every icon follows the token. Only the icons a screen uses live here. Past 30, look
 * again at moving to one icon set.
 */

export type IconName =
  | 'inbox'
  | 'session'
  | 'work'
  | 'decision'
  | 'run'
  | 'my-work'
  | 'team'
  | 'project'
  | 'doc'
  | 'settings'
  | 'search'
  | 'close'
  | 'plus'
  | 'sidebar-expand'
  | 'sidebar-collapse'
  | 'person'
  | 'ai'
  | 'wait'
  | 'done'
  | 'git-file'
  | 'lock'
  | 'bell'
  | 'alert'
  | 'chevron-down'
  | 'more'
  | 'arrow-up'
  | 'arrow-down';

const SHAPES: Record<IconName, ReactNode> = {
  inbox: (
    <>
      <rect x="2.25" y="2.75" width="11.5" height="10.5" rx="1.75" />
      <path d="M2.25 8.75h3l1 1.5h3.5l1-1.5h3" />
    </>
  ),
  session: (
    <>
      <rect x="1.75" y="2.75" width="12.5" height="10.5" rx="1.75" />
      <path d="M4.75 6.25 6.75 8l-2 1.75M8.75 9.75h2.5" />
    </>
  ),
  work: <path d="M6.25 4.25h7.25M6.25 8h7.25M6.25 11.75h7.25M2.75 4.25h.5M2.75 8h.5M2.75 11.75h.5" />,
  decision: (
    <>
      <circle cx="4.25" cy="3.75" r="1.5" />
      <circle cx="11.75" cy="3.75" r="1.5" />
      <path d="M4.25 5.25v.75c0 1.75 3.75 2 3.75 4.25v3.5M11.75 5.25v.75c0 1.75-3.75 2-3.75 4.25" />
    </>
  ),
  run: <path d="M5.25 3.5v9l7.25-4.5z" />,
  'my-work': (
    <>
      <rect x="3.25" y="2.75" width="9.5" height="11.5" rx="1.5" />
      <path d="M6.25 1.75h3.5v2h-3.5z" />
      <path d="M5.75 9.25 7.25 10.75 10.25 7.5" />
    </>
  ),
  team: (
    <>
      <circle cx="6" cy="5.75" r="2.25" />
      <path d="M1.75 13c.45-2.05 2-3.25 4.25-3.25S9.8 10.95 10.25 13" />
      <path d="M10.5 3.75a2 2 0 0 1 0 4M12 9.9c1.2.4 2 1.4 2.25 3.1" />
    </>
  ),
  project: <path d="M2.25 4.75a1.5 1.5 0 0 1 1.5-1.5h2.5l1.5 1.75h4.5a1.5 1.5 0 0 1 1.5 1.5v5.25a1.5 1.5 0 0 1-1.5 1.5h-8.5a1.5 1.5 0 0 1-1.5-1.5z" />,
  doc: (
    <>
      <path d="M4.25 1.75h4.5l3 3v8a1.5 1.5 0 0 1-1.5 1.5h-6a1.5 1.5 0 0 1-1.5-1.5v-9.5a1.5 1.5 0 0 1 1.5-1.5z" />
      <path d="M8.75 1.75v3h3M5.75 9h4.5M5.75 11.5h3" />
    </>
  ),
  settings: (
    <>
      <path d="M2.5 5h5.25M11.5 5h2M2.5 11h2M8.25 11h5.25" />
      <circle cx="9.75" cy="5" r="1.75" />
      <circle cx="6.25" cy="11" r="1.75" />
    </>
  ),
  search: (
    <>
      <circle cx="7" cy="7" r="4.25" />
      <path d="M10.25 10.25 13.5 13.5" />
    </>
  ),
  close: <path d="M4.5 4.5l7 7M11.5 4.5l-7 7" />,
  plus: <path d="M8 3.5v9M3.5 8h9" />,
  'sidebar-expand': (
    <>
      <rect x="2.25" y="2.75" width="11.5" height="10.5" rx="1.75" />
      <path d="M6.25 2.75v10.5M9.25 6.25 11 8l-1.75 1.75" />
    </>
  ),
  'sidebar-collapse': (
    <>
      <rect x="2.25" y="2.75" width="11.5" height="10.5" rx="1.75" />
      <path d="M6.25 2.75v10.5M11 6.25 9.25 8 11 9.75" />
    </>
  ),
  person: (
    <>
      <circle cx="8" cy="5.5" r="2.5" />
      <path d="M3.25 13.25c.6-2.4 2.5-3.75 4.75-3.75s4.15 1.35 4.75 3.75" />
    </>
  ),
  ai: (
    <>
      <rect x="2.75" y="5.25" width="10.5" height="8" rx="2" />
      <path d="M8 5.25V3.5" />
      <circle className="dot" cx="8" cy="2.5" r="1" />
      <circle className="dot" cx="6" cy="9.25" r="1" />
      <circle className="dot" cx="10" cy="9.25" r="1" />
    </>
  ),
  wait: (
    <path d="M4.25 2.25h7.5M4.25 13.75h7.5M5.25 2.25v1.5c0 1.75 2.75 2.75 2.75 4.25s-2.75 2.5-2.75 4.25v1.5M10.75 2.25v1.5c0 1.75-2.75 2.75-2.75 4.25s2.75 2.5 2.75 4.25v1.5" />
  ),
  done: (
    <>
      <circle cx="8" cy="8" r="6" />
      <path d="M5.5 8.25 7.25 10l3.25-3.5" />
    </>
  ),
  'git-file': (
    <>
      <path d="M4.25 1.75h4.5l3 3v8a1.5 1.5 0 0 1-1.5 1.5h-6a1.5 1.5 0 0 1-1.5-1.5v-9.5a1.5 1.5 0 0 1 1.5-1.5z" />
      <path d="M8.75 1.75v3h3" />
      <circle cx="7.25" cy="10" r="1.5" />
      <path d="M7.25 6.25v2.25M7.25 11.5v1.75" />
    </>
  ),
  lock: (
    <>
      <rect x="3.25" y="7.25" width="9.5" height="6.5" rx="1.5" />
      <path d="M5.25 7.25V5.5a2.75 2.75 0 0 1 5.5 0v1.75" />
    </>
  ),
  bell: (
    <>
      <path d="M3.5 12h9l-1-1.5V7.25a3.5 3.5 0 0 0-7 0v3.25z" />
      <path d="M6.75 14.25h2.5" />
    </>
  ),
  alert: (
    <>
      <circle cx="8" cy="8" r="6" />
      <path d="M8 4.75v3.75" />
      <circle className="dot" cx="8" cy="11" r=".9" />
    </>
  ),
  'chevron-down': <path d="M4.75 6.5 8 9.75 11.25 6.5" />,
  more: (
    <>
      <circle className="dot" cx="3.5" cy="8" r="1.25" />
      <circle className="dot" cx="8" cy="8" r="1.25" />
      <circle className="dot" cx="12.5" cy="8" r="1.25" />
    </>
  ),
  'arrow-up': <path d="M8 13V3.5M4.5 7 8 3.5 11.5 7" />,
  'arrow-down': <path d="M8 3v9.5M4.5 9 8 12.5 11.5 9" />,
};

/** Every icon with what it stands for, in the order the design kit shows them. */
export const ICONS: { name: IconName; label: string }[] = [
  { name: 'inbox', label: '나를 기다리는 것' },
  { name: 'session', label: '세션' },
  { name: 'work', label: '작업' },
  { name: 'decision', label: '결정' },
  { name: 'run', label: '실행 시험' },
  { name: 'my-work', label: '내 업무' },
  { name: 'team', label: '팀 현황' },
  { name: 'project', label: '프로젝트' },
  { name: 'doc', label: '문서' },
  { name: 'settings', label: '설정' },
  { name: 'search', label: '찾기' },
  { name: 'close', label: '닫기' },
  { name: 'plus', label: '새로 만들기' },
  { name: 'sidebar-expand', label: '사이드바 펴기' },
  { name: 'sidebar-collapse', label: '사이드바 접기' },
  { name: 'person', label: '사람' },
  { name: 'ai', label: 'AI' },
  { name: 'wait', label: '기다림(막힘)' },
  { name: 'done', label: '끝남' },
  { name: 'git-file', label: 'Git 파일' },
  { name: 'lock', label: '잠김' },
  { name: 'bell', label: '브라우저 알림' },
  { name: 'alert', label: '오류' },
  { name: 'chevron-down', label: '펼쳐 고르기' },
  { name: 'more', label: '더 보기' },
  { name: 'arrow-up', label: '위로' },
  { name: 'arrow-down', label: '아래로' },
];

/**
 * One icon. Hidden from screen readers: an icon always stands next to words that say the same
 * thing (DESIGN.md 「설계 원칙 여섯」 직관성), so reading it out would only repeat them.
 */
export function Icon({ name, className }: { name: IconName; className?: string }) {
  return (
    <svg className={className ? `ico ${className}` : 'ico'} viewBox="0 0 16 16" width="16" height="16" aria-hidden="true" focusable="false">
      {SHAPES[name]}
    </svg>
  );
}
