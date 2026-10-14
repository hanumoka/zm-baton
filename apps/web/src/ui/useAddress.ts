import { useEffect, useRef, useState, type Dispatch, type SetStateAction } from 'react';
import { screenFromHash, type ScreenId } from '../shell/screens';

const currentHash = () => (typeof window === 'undefined' ? '' : window.location.hash);

/**
 * A screen's filters and selection kept in the address (`#work?stage=…&item=…`), so a reload or a
 * pasted link shows the same list (DESIGN.md 「상태와 문구」). Each change replaces the address, so
 * Back leaves the screen instead of stepping through filters; [push] adds a step for a jump the
 * person may want to come back from. When the address changes from outside (the sidebar, back and
 * forward, a pasted link) the view follows it. [navigation] changes only for these address moves
 * and explicit pushes, not for selection changes within the list.
 */
export function useAddress<V>(
  screen: ScreenId,
  read: (hash: string) => V,
  write: (view: V) => string,
): [V, Dispatch<SetStateAction<V>>, (view: V) => void, number] {
  const [state, setState] = useState(() => ({ view: read(currentHash()), navigation: 0 }));
  const { view, navigation } = state;
  const setView: Dispatch<SetStateAction<V>> = (action) => {
    setState((s) => {
      const next = typeof action === 'function' ? (action as (v: V) => V)(s.view) : action;
      return Object.is(next, s.view) ? s : { ...s, view: next };
    });
  };
  const codec = useRef({ read, write });
  codec.current = { read, write };

  useEffect(() => {
    const next = codec.current.write(view);
    if (window.location.hash !== next) window.history.replaceState(window.history.state, '', next);
  }, [view]);

  useEffect(() => {
    const follow = () => {
      if (screenFromHash(window.location.hash) !== screen) return;
      const { read: r, write: w } = codec.current;
      const next = r(window.location.hash);
      // The same address read twice (popstate and hashchange both fire on Back) changes nothing.
      setState((s) => (w(s.view) === w(next) ? s : { view: next, navigation: s.navigation + 1 }));
    };
    window.addEventListener('hashchange', follow);
    window.addEventListener('popstate', follow);
    return () => {
      window.removeEventListener('hashchange', follow);
      window.removeEventListener('popstate', follow);
    };
  }, [screen]);

  const push = (next: V) => {
    const hash = codec.current.write(next);
    if (window.location.hash !== hash) window.history.pushState(window.history.state, '', hash);
    setState((s) => ({ view: next, navigation: s.navigation + 1 }));
  };

  return [view, setView, push, navigation];
}
