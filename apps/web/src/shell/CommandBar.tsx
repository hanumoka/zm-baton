import { useEffect, useRef, useState } from 'react';
import { Icon } from '../icons/Icon';
import { commandTarget, isCommand, type ScreenId } from './screens';

/** Ctrl+K, or Cmd+K on a Mac, puts the cursor in the command line from anywhere. */
export function isCommandShortcut(e: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey'>): boolean {
  return (e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k';
}

function modifierKey(): 'Cmd' | 'Ctrl' {
  if (typeof navigator === 'undefined') return 'Ctrl';
  return /Mac|iPhone|iPad/i.test(navigator.platform || navigator.userAgent) ? 'Cmd' : 'Ctrl';
}

/**
 * A screen that finds through the command line (the work screen finds work by title, number
 * and next action). The screen owns the words; the line only types them.
 */
export type Find = {
  value: string;
  onChange: (q: string) => void;
  placeholder: string;
  label: string;
  /** Enter on plain words, or the down arrow: move into the screen's results. */
  onSubmit: () => void;
};

/**
 * The find-and-command line at the top of the content (DESIGN.md 「화면 배치」 2). Typing a
 * screen's name and Enter opens it. On a screen that finds, plain words find on that screen and
 * a leading `>` (or a screen's exact name) opens another screen.
 */
export function CommandBar({ onGo, find }: { onGo: (id: ScreenId) => void; find?: Find }) {
  const [local, setLocal] = useState('');
  const q = find ? find.value : local;
  const setQ = find ? find.onChange : setLocal;
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!isCommandShortcut(e)) return;
      e.preventDefault();
      input.current?.focus();
      input.current?.select();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const typed = q.trim() !== '';
  const target = typed ? commandTarget(q, find !== undefined) : null;
  const finding = find !== undefined && typed && !isCommand(q) && target === null;
  const status = !typed
    ? ''
    : target
      ? `Enter를 누르면 「${target.label}」 화면을 연다`
      : finding
        ? 'Enter나 아래 화살표를 누르면 찾은 목록으로 간다'
        : '이름이 맞는 화면이 없다';

  return (
    <div className="cmd" role="search">
      <Icon name="search" />
      <input
        ref={input}
        type="search"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && target) {
            e.preventDefault();
            setQ('');
            onGo(target.id);
          } else if (find && (e.key === 'ArrowDown' || (e.key === 'Enter' && finding))) {
            e.preventDefault();
            find.onSubmit();
          } else if (e.key === 'Escape') {
            setQ('');
            e.currentTarget.blur();
          }
        }}
        placeholder={find ? find.placeholder : '화면 이름을 쓰고 Enter로 이동'}
        aria-label={find ? find.label : '화면 이름으로 이동'}
        autoComplete="off"
        spellCheck={false}
      />
      {!typed && (
        <span className="keys" aria-hidden="true">
          <kbd>{modifierKey()}</kbd>
          <kbd>K</kbd>
        </span>
      )}
      {typed && target && (
        <span className="cmd-hint" aria-hidden="true">
          <kbd>Enter</kbd> 「{target.label}」 열기
        </span>
      )}
      {finding && (
        <span className="cmd-hint" aria-hidden="true">
          <kbd>Enter</kbd> 찾은 목록으로
        </span>
      )}
      {typed && !target && !finding && (
        <span className="cmd-hint" aria-hidden="true">
          맞는 화면 없음
        </span>
      )}
      <span className="sr" aria-live="polite">
        {status}
      </span>
    </div>
  );
}
