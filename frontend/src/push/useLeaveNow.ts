/**
 * FR-11 … FR-16 on the customer's ticket page.
 *
 * Location is requested ONLY when the customer taps the button. The browser's
 * permission prompt is a one-shot resource: ask on page load and most people
 * reflexively decline, which is permanent and cannot be undone from code.
 * Asking after they've expressed intent is both more polite and more effective.
 */
import { useCallback, useEffect, useState } from 'react';
import { errorMessage } from '../api/client';
import { getLeaveNow, holdSpot, revokeLocation, sendOnMyWay, shareLocation } from '../api/endpoints';
import type { LeaveNowResponse } from '../api/types';

export type LocationState =
  | 'unsupported'
  | 'off'
  | 'requesting'
  | 'on'
  | 'denied'   // the BROWSER refused — only the user can undo this, in site settings
  | 'failed';  // we couldn't complete it — retrying is worth a try

/** Why sharing didn't work, in words a customer can act on. Null unless the
 *  state is 'denied' or 'failed'. */
export type LocationProblem = string | null;

export function useLeaveNow(entryToken: string | undefined, active: boolean) {
  const [status, setStatus] = useState<LeaveNowResponse | null>(null);
  const [locationState, setLocationState] = useState<LocationState>(
    'geolocation' in navigator ? 'off' : 'unsupported',
  );
  const [locationProblem, setLocationProblem] = useState<LocationProblem>(null);

  const refresh = useCallback(async () => {
    if (!entryToken) return;
    try {
      const next = await getLeaveNow(entryToken);
      setStatus(next);
      if (next.sharingLocation) setLocationState('on');
    } catch {
      /* a missing journey is not an error worth showing */
    }
  }, [entryToken]);

  // Poll while the ticket is live. Slow on purpose: the underlying travel
  // estimate is cached server-side for 3 minutes, so asking more often would
  // just return the same number.
  useEffect(() => {
    if (!active) return;
    refresh();
    const id = setInterval(refresh, 60_000);
    return () => clearInterval(id);
  }, [active, refresh]);

  const enableLocation = useCallback(() => {
    if (!entryToken || !('geolocation' in navigator)) return;
    setLocationState((current) => {
      // Ignore a second tap while one request is already in flight, rather
      // than firing a second getCurrentPosition and a second POST.
      if (current === 'requesting') return current;
      return 'requesting';
    });
    setLocationProblem(null);

    navigator.geolocation.getCurrentPosition(
      async (position) => {
        try {
          await shareLocation(entryToken, position.coords.latitude, position.coords.longitude);
          setLocationState('on');
          refresh();
        } catch (err) {
          // THE BUG THIS REPLACES: this used to setLocationState('off'), which
          // put the button back exactly as it was and said nothing at all. The
          // browser had granted location, the POST had failed, and the only
          // visible result of tapping "Share location" was that nothing
          // happened — which is indistinguishable from a dead button, and is
          // what made this feel intermittent rather than broken.
          setLocationState('failed');
          setLocationProblem(errorMessage(err));
        }
      },
      (geoError) => {
        // A denial is permanent and only the user can lift it. A timeout or a
        // temporarily unavailable fix is neither — telling someone their
        // location is "blocked" when the GPS just took too long sends them to
        // browser settings to fix something that isn't broken.
        if (geoError.code === geoError.PERMISSION_DENIED) {
          setLocationState('denied');
          setLocationProblem(null);
        } else {
          setLocationState('failed');
          setLocationProblem(
            geoError.code === geoError.TIMEOUT
              ? 'Couldn’t get a location fix in time. Try again.'
              : 'Your device couldn’t provide a location just now. Try again.',
          );
        }
      },
      { enableHighAccuracy: false, timeout: 10_000, maximumAge: 300_000 },
    );
  }, [entryToken, refresh]);

  const disableLocation = useCallback(async () => {
    if (!entryToken) return;
    await revokeLocation(entryToken);
    setLocationState('off');
    refresh();
  }, [entryToken, refresh]);

  const onMyWay = useCallback(async () => {
    if (!entryToken) return;
    await sendOnMyWay(entryToken);
    refresh();
  }, [entryToken, refresh]);

  const requestHold = useCallback(async () => {
    if (!entryToken) return;
    await holdSpot(entryToken);
    refresh();
  }, [entryToken, refresh]);

  return { status, locationState, locationProblem, enableLocation, disableLocation, onMyWay, requestHold };
}
