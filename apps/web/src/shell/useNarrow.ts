import { useEffect, useState } from 'react';

/** The width where the sidebar becomes the bottom tab bar and details open as sheets (shell.css). */
export const NARROW = '(max-width: 760px)';

/** True on narrow screens; follows the window as it is resized. False where there is no window. */
export function useNarrow(): boolean {
  const [narrow, setNarrow] = useState(() => typeof window !== 'undefined' && window.matchMedia?.(NARROW).matches === true);
  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return;
    const mq = window.matchMedia(NARROW);
    const on = () => setNarrow(mq.matches);
    on();
    mq.addEventListener('change', on);
    return () => mq.removeEventListener('change', on);
  }, []);
  return narrow;
}
