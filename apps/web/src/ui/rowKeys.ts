import type { KeyboardEvent } from 'react';

/*
 * Keyboard steps through a list of rows (DESIGN.md 「사용성」): the arrows, Home and End move the
 * selection and the focus, Enter opens the row. Rows carry `data-row` with their id; only the
 * selected row is in the Tab order, so Tab leaves the list in one step.
 */

/** The next row for an arrow key, Home or End; nothing selected starts at the first row. */
export function step(ids: string[], current: string | null, key: string): string | null {
  if (ids.length === 0) return null;
  const i = current === null ? -1 : ids.indexOf(current);
  switch (key) {
    case 'ArrowDown':
      return i < 0 ? ids[0] : ids[Math.min(i + 1, ids.length - 1)];
    case 'ArrowUp':
      return i < 0 ? ids[0] : ids[Math.max(i - 1, 0)];
    case 'Home':
      return ids[0];
    case 'End':
      return ids[ids.length - 1];
    default:
      return current;
  }
}

/** The row that takes the Tab stop: the selected one while it shows, else the first. */
export const tabStop = (order: string[], selected: string | null) => (selected !== null && order.includes(selected) ? selected : (order[0] ?? null));

/** The list body's key handler. [order] is the rows as drawn. */
export function rowKeys(order: string[], onSelect: (id: string) => void, onOpen: (id: string) => void) {
  return (e: KeyboardEvent<HTMLElement>) => {
    const row = (e.target as HTMLElement).closest<HTMLElement>('[data-row]');
    if (!row) return;
    const id = row.dataset.row ?? null;
    if (e.key === 'Enter' && id) {
      e.preventDefault();
      onOpen(id);
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(e.key)) return;
    e.preventDefault();
    const next = step(order, id, e.key);
    if (next === null || next === id) return;
    onSelect(next);
    e.currentTarget.querySelector<HTMLElement>(`[data-row="${CSS.escape(next)}"]`)?.focus();
  };
}

/** Puts the focus back on the row of [id] inside [root], if it shows. */
export function focusRow(root: HTMLElement | null | undefined, id: string | null) {
  if (!root || id === null) return;
  root.querySelector<HTMLElement>(`[data-row="${CSS.escape(id)}"]`)?.focus();
}
