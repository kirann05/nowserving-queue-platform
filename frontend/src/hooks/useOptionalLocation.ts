/**
 * Shared by DiscoverPage and NearbyPage so granting location once doesn't
 * mean asking again a page later. Location here is a CONVENIENCE, not a
 * gate: every page that uses this renders identically without it, and we
 * never call getCurrentPosition on mount — a permission dialog before the
 * user has seen anything is the fastest way to get a permanent "no".
 *
 * sessionStorage (not localStorage) on purpose: "I shared my location" is a
 * fact about THIS visit, not a standing preference — a new tab tomorrow
 * should ask again rather than silently reuse a stale position.
 */
import { useEffect, useState } from 'react';

const SESSION_KEY = 'ns_location';

interface Coords {
  lat: number;
  lng: number;
}

function readSession(): Coords | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as Coords) : null;
  } catch {
    return null;
  }
}

export function useOptionalLocation() {
  const [coords, setCoords] = useState<Coords | null>(() => readSession());
  const [asking, setAsking] = useState(false);
  const [denied, setDenied] = useState(false);

  // If another tab/page granted it moments ago, adopt it without asking —
  // this only fires once per mount, never polls.
  useEffect(() => {
    if (!coords) {
      const found = readSession();
      if (found) setCoords(found);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function ask() {
    if (!('geolocation' in navigator)) return;
    setAsking(true);
    setDenied(false);
    navigator.geolocation.getCurrentPosition(
      (p) => {
        const next = { lat: p.coords.latitude, lng: p.coords.longitude };
        setCoords(next);
        setAsking(false);
        try {
          sessionStorage.setItem(SESSION_KEY, JSON.stringify(next));
        } catch {
          /* private browsing or storage disabled — coords still work for
             this page, just won't carry over to the next one */
        }
      },
      () => {
        setAsking(false);
        setDenied(true);
      },
      { timeout: 10_000 },
    );
  }

  return { coords, asking, denied, ask };
}
