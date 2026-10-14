import { useRef, useState } from 'react';

/**
 * The narrow screen's sheet (shell/Sheet.tsx): open remembers what had the focus, close gives it
 * back, so a keyboard user lands on the row they opened.
 */
export function useSheet(selected: string | null = null, navigation = 0) {
  // Direct links/reloads and address navigation open the detail. Selection from arrow keys,
  // initial wide layout or polling never opens a closed sheet; a tap/Enter calls show().
  const [state, setState] = useState({ navigation, open: selected !== null });
  const opener = useRef<HTMLElement | null>(null);
  if (state.navigation !== navigation) {
    opener.current = null;
    setState({ navigation, open: selected !== null });
  }
  return {
    open: state.navigation === navigation ? state.open : selected !== null,
    show() {
      opener.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
      setState({ navigation, open: true });
    },
    hide() {
      setState({ navigation, open: false });
      const back = opener.current ?? (selected === null ? null : document.querySelector<HTMLElement>(`[data-row="${CSS.escape(selected)}"]`));
      requestAnimationFrame(() => back?.focus());
    },
  };
}
