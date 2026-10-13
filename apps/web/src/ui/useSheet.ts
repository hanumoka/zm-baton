import { useRef, useState } from 'react';

/**
 * The narrow screen's sheet (shell/Sheet.tsx): open remembers what had the focus, close gives it
 * back, so a keyboard user lands on the row they opened.
 */
export function useSheet() {
  const [open, setOpen] = useState(false);
  const opener = useRef<HTMLElement | null>(null);
  return {
    open,
    show() {
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      setOpen(true);
    },
    hide() {
      setOpen(false);
      const back = opener.current;
      requestAnimationFrame(() => back?.focus());
    },
  };
}
