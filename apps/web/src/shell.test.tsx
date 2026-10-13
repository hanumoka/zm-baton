import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { CommandBar, isCommandShortcut } from './shell/CommandBar';
import { commandTarget, exactScreen, matchScreen, NAV_GROUPS, screenFromHash, SCREENS, TAB_ITEMS } from './shell/screens';
import { readSideOpen, SIDE_KEY, writeSideOpen } from './shell/sidePref';
import { NotifyItem, Sidebar } from './shell/Sidebar';
import { TabBar } from './shell/TabBar';

const sidebar = (open: boolean, counts = { inbox: 3, sessions: 4 }) =>
  renderToStaticMarkup(<Sidebar current="work" counts={counts} open={open} onToggle={() => {}} permission="default" onAskPermission={() => {}} />);

describe('screens', () => {
  it('keeps the hash addresses and falls back to the first screen', () => {
    expect(screenFromHash('#work')).toBe('work');
    expect(screenFromHash('#/decisions')).toBe('decisions');
    expect(screenFromHash('#kit')).toBe('kit');
    expect(screenFromHash('')).toBe('inbox');
    expect(screenFromHash('#nothing')).toBe('inbox');
    // What follows ? belongs to the screen: its filters and selection.
    expect(screenFromHash('#work?stage=ready&item=wrk_a')).toBe('work');
    expect(screenFromHash('#/work?item=wrk_a')).toBe('work');
  });

  it('matches a screen by its name, a short name or the start of one, and never offers the kit', () => {
    expect(matchScreen('작업')?.id).toBe('work');
    expect(matchScreen('기다')?.id).toBe('inbox');
    expect(matchScreen(' 세션 ')?.id).toBe('sessions');
    expect(matchScreen('>결정')?.id).toBe('decisions');
    expect(matchScreen('시험')?.id).toBe('runs');
    expect(matchScreen('WORK')?.id).toBe('work');
    expect(matchScreen('')).toBeNull();
    expect(matchScreen('키트')).toBeNull();
    expect(matchScreen('없는 화면')).toBeNull();
  });

  it('puts every navigable screen in the sidebar once and leaves the kit to its address', () => {
    const inNav = NAV_GROUPS.flatMap((g) => g.items);
    expect(inNav).toEqual(SCREENS.filter((s) => s.inNav).map((s) => s.id));
    expect(inNav).not.toContain('kit');
    expect(TAB_ITEMS.every((id) => inNav.includes(id))).toBe(true);
  });
});

describe('sidebar', () => {
  it('starts folded: icons with name tooltips, counts, and a button to open it', () => {
    const html = sidebar(false);
    expect(html).toContain('data-mode="rail"');
    expect(html).toContain('data-tip="나를 기다리는 것, 3건"');
    expect(html).toContain('data-tip="세션 현황, 살아 있는 세션 4개"');
    expect(html).toContain('aria-expanded="false"');
    expect(html).toContain('사이드바 펴기');
    // Names stay in the page for screen readers even while folded.
    expect(html).toContain('<span class="nm">결정</span>');
  });

  it('opens with names and no name tooltips, and a button to fold it', () => {
    const html = sidebar(true);
    expect(html).toContain('data-mode="open"');
    expect(html).toContain('aria-expanded="true"');
    expect(html).toContain('사이드바 접기');
    expect(html).not.toContain('data-tip="작업"');
  });

  it('marks the current screen without the accent and shows no count for zero', () => {
    const html = sidebar(true, { inbox: 0, sessions: 0 });
    expect(html).toMatch(/<a class="it" href="#work" aria-current="page">/);
    expect(html).not.toContain('class="n num"');
    expect(html).not.toContain('accent');
  });

  it('locks settings with the reason on hover, on focus and for screen readers', () => {
    const html = sidebar(false);
    expect(html).toMatch(/<button type="button" class="it tipped" aria-disabled="true" aria-describedby="[^"]+" data-tip="설정: 설정 화면은 아직 없다/);
    expect(html).toContain('검토 단계는 작업 화면에서 켜고 끈다');
  });

  it('asks for browser notifications with a button, and states granted or blocked', () => {
    expect(renderToStaticMarkup(<NotifyItem permission="default" onAsk={() => {}} rail={false} />)).toContain('<button');
    expect(renderToStaticMarkup(<NotifyItem permission="granted" onAsk={() => {}} rail={false} />)).toContain('브라우저 알림 켜짐');
    const denied = renderToStaticMarkup(<NotifyItem permission="denied" onAsk={() => {}} rail={false} />);
    expect(denied).toContain('사이트 설정에서 알림을 허용한다');
    expect(renderToStaticMarkup(<NotifyItem permission="unsupported" onAsk={() => {}} rail={false} />)).toBe('');
  });
});

describe('sidebar memory', () => {
  const store = (initial: Record<string, string> = {}) => {
    const data = { ...initial };
    return { data, getItem: (k: string) => data[k] ?? null, setItem: (k: string, v: string) => void (data[k] = v) };
  };
  const broken = {
    getItem: () => {
      throw new Error('blocked');
    },
    setItem: () => {
      throw new Error('blocked');
    },
  };

  it('remembers open and folded, and starts folded when nothing is stored', () => {
    const s = store();
    expect(readSideOpen(s)).toBe(false);
    writeSideOpen(true, s);
    expect(s.data[SIDE_KEY]).toBe('open');
    expect(readSideOpen(s)).toBe(true);
    writeSideOpen(false, s);
    expect(readSideOpen(s)).toBe(false);
  });

  it('starts folded and keeps working when storage is blocked', () => {
    expect(readSideOpen(broken)).toBe(false);
    expect(() => writeSideOpen(true, broken)).not.toThrow();
    // Without a window (as here) the default store is missing; that is not an error either.
    expect(readSideOpen()).toBe(false);
  });
});

describe('command line', () => {
  it('takes Ctrl+K and Cmd+K, not K alone or with Alt', () => {
    const k = (over: Partial<KeyboardEvent>) => isCommandShortcut({ key: 'k', ctrlKey: false, metaKey: false, altKey: false, ...over });
    expect(k({ ctrlKey: true })).toBe(true);
    expect(k({ metaKey: true, key: 'K' })).toBe(true);
    expect(k({})).toBe(false);
    expect(k({ ctrlKey: true, altKey: true })).toBe(false);
  });

  it('is a labelled search box with the shortcut shown', () => {
    const html = renderToStaticMarkup(<CommandBar onGo={() => {}} />);
    expect(html).toContain('role="search"');
    expect(html).toContain('aria-label="화면 이름으로 이동"');
    expect(html).toContain('<kbd>K</kbd>');
    expect(html).toContain('aria-live="polite"');
  });
});

describe('command line that also finds', () => {
  it('opens a screen on Enter only for an exact name or a > command while the screen finds', () => {
    // Plain words find on the work screen: 「세」 is not a jump to 「세션 현황」.
    expect(commandTarget('세', true)).toBeNull();
    expect(commandTarget('세', false)?.id).toBe('sessions');
    expect(commandTarget('결정', true)?.id).toBe('decisions');
    expect(commandTarget(' 세션 현황 ', true)?.id).toBe('sessions');
    expect(commandTarget('>세', true)?.id).toBe('sessions');
    expect(commandTarget('>없는 화면', true)).toBeNull();
    expect(exactScreen('키트')).toBeNull();
  });

  it('labels the line as finding work while the work screen is open', () => {
    const html = renderToStaticMarkup(
      <CommandBar onGo={() => {}} find={{ value: '', onChange: () => {}, placeholder: '작업 찾기, 화면 이동은 > 로 시작', label: '작업 찾기 또는 화면 이동', onSubmit: () => {} }} />,
    );
    expect(html).toContain('placeholder="작업 찾기, 화면 이동은 &gt; 로 시작"');
    expect(html).toContain('aria-label="작업 찾기 또는 화면 이동"');
    expect(html).toContain('role="search"');
  });

  it('shows what Enter does for typed words', () => {
    const find = (value: string) =>
      renderToStaticMarkup(<CommandBar onGo={() => {}} find={{ value, onChange: () => {}, placeholder: '', label: '', onSubmit: () => {} }} />);
    expect(find('도커')).toContain('찾은 목록으로');
    expect(find('결정')).toContain('「결정」 열기');
    expect(find('>없는')).toContain('맞는 화면 없음');
  });
});

describe('bottom tab bar', () => {
  it('shows four screens and 「더 보기」, with counts', () => {
    const html = renderToStaticMarkup(<TabBar current="work" counts={{ inbox: 2 }} permission="default" onAskPermission={() => {}} />);
    expect(html.match(/<a /g)).toHaveLength(4);
    for (const label of ['기다림', '세션', '작업', '결정', '더 보기']) expect(html).toContain(label);
    expect(html).toContain('aria-haspopup="dialog"');
    expect(html).toContain('<span class="sr">, 2건</span>');
    expect(html).toMatch(/<a href="#work" aria-current="page">/);
  });

  it('marks 「더 보기」 when the screen shown lives under it', () => {
    const html = renderToStaticMarkup(<TabBar current="runs" counts={{}} permission="default" onAskPermission={() => {}} />);
    expect(html).toContain('class="is-here"');
    expect(html).not.toContain('aria-current');
  });
});
