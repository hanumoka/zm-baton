import { useEffect, useRef } from 'react';

type Args = {
  /** Show the first row while nothing is selected (wide, detail beside the list). */
  fill: boolean;
  /** Move to the first row when a filter change hides the shown one (wide). */
  follow: boolean;
  selected: string | null;
  /** The rows as drawn. */
  order: string[];
  /** Every filter and search word the person can change, as one string. */
  filterKey: string;
  /** True once the list for the current server-side filters has arrived. */
  settled: boolean;
  choose: (id: string | null) => void;
};

/**
 * Wide, the detail is always beside the list, so it shows something: the first row rather than an
 * empty half, and when a filter or search the person changed hides the shown item, the first row
 * that is left. Only once the list for the new filter has arrived; an item that leaves the list
 * for another reason (it moved stage, it was replaced) stays shown.
 */
export function useShownRow({ fill, follow, selected, order, filterKey, settled, choose }: Args) {
  const pick = useRef(choose);
  pick.current = choose;
  const first = order[0] ?? null;

  useEffect(() => {
    if (fill && selected === null && first !== null) pick.current(first);
  }, [fill, selected, first]);

  const lastFilter = useRef(filterKey);
  const shown = order.join(' ');
  useEffect(() => {
    if (!settled || lastFilter.current === filterKey) return;
    lastFilter.current = filterKey;
    if (follow && selected !== null && !shown.split(' ').includes(selected)) pick.current(first);
  }, [settled, filterKey, follow, selected, shown, first]);
}
