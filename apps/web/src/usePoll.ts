import { useEffect } from 'react';

/**
 * Polls fn every ms for one selection (deps). When the selection changes, the old request
 * is aborted and fn's alive() turns false, so a late answer for the old selection is
 * dropped instead of written into the new one. Ticks never overlap.
 */
export function usePoll(
  fn: (alive: () => boolean, signal: AbortSignal) => Promise<void>,
  ms: number,
  deps: unknown[],
  onError: (e: unknown) => void,
) {
  useEffect(() => {
    const controller = new AbortController();
    let stopped = false;
    let running = false;
    const tick = async () => {
      if (running) return;
      running = true;
      try {
        await fn(() => !stopped, controller.signal);
      } catch (e) {
        if (!stopped) onError(e);
      } finally {
        running = false;
      }
    };
    void tick();
    const id = setInterval(tick, ms);
    return () => {
      stopped = true;
      controller.abort();
      clearInterval(id);
    };
  }, deps); // the caller lists what fn reads
}
