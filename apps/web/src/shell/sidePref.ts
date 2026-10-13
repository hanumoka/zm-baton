/*
 * Whether the sidebar is open, remembered per browser. Storage can be missing or throw (a private
 * window, blocked site data), so every read and write is guarded and the sidebar then starts
 * folded, which suits the one-person organisation that is the default.
 */

export const SIDE_KEY = 'zm-baton.sidebar';

type Store = Pick<Storage, 'getItem' | 'setItem'>;

function defaultStore(): Store {
  return window.localStorage;
}

export function readSideOpen(store?: Store): boolean {
  try {
    return (store ?? defaultStore()).getItem(SIDE_KEY) === 'open';
  } catch {
    return false;
  }
}

export function writeSideOpen(open: boolean, store?: Store): void {
  try {
    (store ?? defaultStore()).setItem(SIDE_KEY, open ? 'open' : 'rail');
  } catch {
    // Not remembered; the sidebar still opens and folds for this visit.
  }
}
