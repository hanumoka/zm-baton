import { useEffect, useState } from 'react';

/** The value after it has stopped changing for [ms], so typing sends one request, not one per letter. */
export function useSettled<T>(value: T, ms: number): T {
  const [settled, setSettled] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setSettled(value), ms);
    return () => clearTimeout(id);
  }, [value, ms]);
  return settled;
}
