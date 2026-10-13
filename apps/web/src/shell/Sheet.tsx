import { useEffect, useId, useRef, type ReactNode } from 'react';
import { Icon } from '../icons/Icon';

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * A sheet that rises from the bottom over the page on narrow screens (DESIGN.md 「화면 배치」 6).
 * It is a modal dialog: focus moves in and stays in, and the close button, the dimmed page
 * behind it and Esc each close it. The caller returns focus to what opened it.
 */
export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  const box = useRef<HTMLDivElement>(null);
  const close = useRef(onClose);
  close.current = onClose;
  const titleId = useId();

  useEffect(() => {
    const el = box.current;
    el?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        close.current();
      } else if (e.key === 'Tab' && el) {
        const items = [...el.querySelectorAll<HTMLElement>(FOCUSABLE)];
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, []);

  return (
    <div className="sheet-layer">
      <div className="scrim" aria-hidden="true" onClick={() => close.current()} />
      <div ref={box} className="sheet" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className="sheet-bar">
          <span className="grab" aria-hidden="true" />
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="btn btn-quiet" onClick={() => close.current()}>
            <Icon name="close" />
            닫기
          </button>
        </div>
        <div className="sheet-body">{children}</div>
      </div>
    </div>
  );
}
