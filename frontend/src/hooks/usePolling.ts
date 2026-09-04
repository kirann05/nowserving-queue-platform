/**
 * usePolling — run an async fetch now and every `intervalMs` after.
 *
 * This is NS-10's deliberate "slow way": the client asks over and over
 * ("am I up yet?") instead of the server pushing. Sprint 2 replaces the
 * transport with WebSockets; this hook is written so pages won't care —
 * they just receive new data.
 *
 * Details that make a polling hook correct (and are easy to get wrong):
 *  - cleanup: clearInterval on unmount, or the page keeps polling forever
 *    in the background (a memory leak + wasted requests).
 *  - the `stopped` flag: an in-flight response landing AFTER unmount must
 *    not call setState on a dead component.
 */
import { useEffect, useRef, useState } from 'react';

export function usePolling<T>(fetcher: () => Promise<T>, intervalMs: number, deps: unknown[] = []) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  // Keep the latest fetcher without re-creating the interval every render.
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    let stopped = false;

    const tick = () =>
      fetcherRef
        .current()
        .then((d) => {
          if (!stopped) {
            setData(d);
            setError(null);
          }
        })
        .catch((e) => {
          if (!stopped) setError(e);
        });

    tick(); // fetch immediately, then on the interval
    const id = setInterval(tick, intervalMs);
    return () => {
      stopped = true;
      clearInterval(id);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, error };
}
