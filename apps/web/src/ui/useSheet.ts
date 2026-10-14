import { useRef, useState } from 'react';

/**
 * The narrow screen's sheet (shell/Sheet.tsx): open remembers what had the focus, close gives it
 * back, so a keyboard user lands on the row they opened.
 */
export function useSheet(selected: string | null = null) {
  // A direct link/reload starts open. A new selection from Back/Forward opens again, while
  // closing the same selection stays closed across polling renders. Unaddressed forms can
  // still call show() without selecting a row.
  const [state, setState] = useState({ selected, open: selected !== null });
  if (state.selected !== selected) setState({ selected, open: selected !== null });
  const opener = useRef<HTMLElement | null>(null);
  return {
    open: state.selected === selected ? state.open : selected !== null,
    show() {
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      setState({ selected, open: true });
    },
    hide() {
      setState({ selected, open: false });
      const back = opener.current;
      requestAnimationFrame(() => back?.focus());
    },
  };
}
