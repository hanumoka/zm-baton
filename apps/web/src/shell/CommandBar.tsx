import { useEffect, useRef, useState } from 'react';
import { Icon } from '../icons/Icon';
import { matchScreen, type ScreenId } from './screens';

/** Ctrl+K, or Cmd+K on a Mac, puts the cursor in the command line from anywhere. */
export function isCommandShortcut(e: Pick<KeyboardEvent, 'key' | 'ctrlKey' | 'metaKey' | 'altKey'>): boolean {
  return (e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === 'k';
}

function modifierKey(): 'Cmd' | 'Ctrl' {
  if (typeof navigator === 'undefined') return 'Ctrl';
  return /Mac|iPhone|iPad/i.test(navigator.platform || navigator.userAgent) ? 'Cmd' : 'Ctrl';
}

/**
 * The find-and-command line at the top of the content (DESIGN.md 「화면 배치」 2). For now it only
 * moves between screens: type a screen's name and press Enter. Finding work comes next.
 */
export function CommandBar({ onGo }: { onGo: (id: ScreenId) => void }) {
  const [q, setQ] = useState('');
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
  const match = typed ? matchScreen(q) : null;
  const status = !typed ? '' : match ? `Enter를 누르면 「${match.label}」 화면을 연다` : '이름이 맞는 화면이 없다';

  return (
    <div className="cmd" role="search">
      <Icon name="search" />
      <input
        ref={input}
        type="search"
        value={q}
        onChange={(e) => setQ(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' && match) {
            e.preventDefault();
            setQ('');
            onGo(match.id);
          } else if (e.key === 'Escape') {
            setQ('');
            e.currentTarget.blur();
          }
        }}
        placeholder="화면 이름을 쓰고 Enter로 이동"
        aria-label="화면 이름으로 이동"
        autoComplete="off"
        spellCheck={false}
      />
      {!typed && (
        <span className="keys" aria-hidden="true">
          <kbd>{modifierKey()}</kbd>
          <kbd>K</kbd>
        </span>
      )}
      {typed && match && (
        <span className="cmd-hint" aria-hidden="true">
          <kbd>Enter</kbd> 「{match.label}」 열기
        </span>
      )}
      {typed && !match && (
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
