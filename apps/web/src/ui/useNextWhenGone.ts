import { useEffect, useRef } from 'react';

type Args = {
  /** The rows as drawn. */
  order: string[];
  selected: string | null;
  /** True once the list has arrived; before that a missing row says nothing. */
  settled: boolean;
  /** False while the selected item must stay put (an open sheet says it closed instead). */
  enabled: boolean;
  choose: (id: string | null) => void;
};

/** The row that takes the place of [gone]: the next one still drawn, else the one before, else the first. */
export function nextInPlace(before: string[], after: string[], gone: string): string | null {
  const at = before.indexOf(gone);
  const still = new Set(after);
  if (at >= 0) {
    for (let i = at + 1; i < before.length; i++) if (still.has(before[i])) return before[i];
    for (let i = at - 1; i >= 0; i--) if (still.has(before[i])) return before[i];
  }
  return after[0] ?? null;
}

/**
 * For lists whose items leave once handled (the first screen): when the selected row leaves the
 * list, because it was judged here or closed elsewhere, the row that took its place is selected,
 * so the next thing to handle is already open. A selection the list never had (an old address)
 * goes to the first row.
 */
export function useNextWhenGone({ order, selected, settled, enabled, choose }: Args) {
  const pick = useRef(choose);
  pick.current = choose;
  const last = useRef<string[]>(order);
  const shown = order.join(' ');
  useEffect(() => {
    if (!settled) return;
    const now = shown === '' ? [] : shown.split(' ');
    const before = last.current;
    last.current = now;
    if (!enabled || selected === null || now.includes(selected)) return;
    pick.current(nextInPlace(before, now, selected));
  }, [settled, shown, selected, enabled]);
}
