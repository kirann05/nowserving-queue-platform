/**
 * /nearby — the full restaurant list. The homepage only teases a handful of
 * NowServing venues near you; this is where "show me everything" leads.
 *
 * Three data sources, same rule the old homepage used before it was split:
 *   searching (?q=... or the search box here)  -> NowServing venues only, text match
 *   granted a location, not searching          -> NowServing + OpenStreetMap, merged
 *   neither yet                                 -> every listed NowServing venue,
 *                                                   unsorted, with a location prompt
 *                                                   above it rather than a blocking gate
 */
import { useEffect, useState, type FormEvent } from 'react';
import { useSearchParams } from 'react-router-dom';
import { getNearbyVenues, searchVenues } from '../api/endpoints';
import { fromNearbyPlace, fromVenueSummary, RestaurantCard, type RestaurantCardModel } from '../components/RestaurantCard';
import type { NearbyPlaceResponse, VenueSummary } from '../api/types';
import { usePolling } from '../hooks/usePolling';
import { useOptionalLocation } from '../hooks/useOptionalLocation';

/** "just now" / "Xs ago" / "Xm ago" — recomputed on a slow tick so the label
 *  stays honest between polls without a per-second re-render. */
function useRelativeLabel(timestamp: number | null): string | null {
  const [, forceTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => forceTick((n) => n + 1), 5_000);
    return () => clearInterval(id);
  }, []);
  if (timestamp == null) return null;
  const secs = Math.max(0, Math.round((Date.now() - timestamp) / 1000));
  if (secs < 10) return 'Updated just now';
  if (secs < 60) return `Updated ${secs}s ago`;
  return `Updated ${Math.round(secs / 60)}m ago`;
}

type Filter = 'all' | 'nowserving' | 'open';

function isOpenNow(c: RestaurantCardModel): boolean {
  return c.onNowServing ? c.open === true : c.openingStatus === 'OPEN_NOW';
}

export function NearbyPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialQuery = searchParams.get('q') ?? '';
  const [query, setQuery] = useState(initialQuery);
  const [submitted, setSubmitted] = useState(initialQuery);
  const [filter, setFilter] = useState<Filter>('all');
  const { coords, asking, denied, ask } = useOptionalLocation();

  useEffect(() => {
    document.title = 'Nearby restaurants — NowServing';
  }, []);

  const searching = submitted !== '';
  const browsingNearby = !searching && coords !== null;

  const { data: searchResults, error: searchError, loading: searchLoading } = usePollingWithLoading<VenueSummary[]>(
    () => searchVenues(submitted, coords?.lat, coords?.lng),
    20_000,
    [submitted, coords?.lat, coords?.lng],
  );
  const { data: nearbyResults, error: nearbyError, loading: nearbyLoading } = usePollingWithLoading<NearbyPlaceResponse[]>(
    () => (coords ? getNearbyVenues(coords.lat, coords.lng) : Promise.resolve([])),
    20_000,
    [coords?.lat, coords?.lng],
  );

  const cards: RestaurantCardModel[] | null = searching
    ? (searchResults?.map(fromVenueSummary) ?? null)
    : browsingNearby
      ? (nearbyResults?.map(fromNearbyPlace) ?? null)
      : (searchResults?.map(fromVenueSummary) ?? null);
  const error = searching ? searchError : browsingNearby ? nearbyError : searchError;
  const loading = searching ? searchLoading : browsingNearby ? nearbyLoading : searchLoading;
  const [lastUpdatedAt, setLastUpdatedAt] = useState<number | null>(null);
  // Depend on the RAW result arrays, never on `cards`.
  //
  // `cards` is rebuilt by .map() on every render, so its identity always
  // differs — this effect re-ran after every render, set a new timestamp,
  // caused another render, and looped until React aborted with "Maximum
  // update depth exceeded". usePolling holds its data in state, so these two
  // references are stable between fetches and the effect fires exactly when
  // fresh data actually lands.
  const activeResults = browsingNearby ? nearbyResults : searchResults;
  useEffect(() => {
    if (activeResults) setLastUpdatedAt(Date.now());
  }, [activeResults]);
  const updatedLabel = useRelativeLabel(lastUpdatedAt);

  const nowServingCount = cards?.filter((c) => c.onNowServing).length ?? 0;
  const visible = cards?.filter((c) => {
    if (filter === 'nowserving') return c.onNowServing;
    if (filter === 'open') return isOpenNow(c);
    return true;
  });

  function onSearch(e: FormEvent) {
    e.preventDefault();
    const trimmed = query.trim();
    setSubmitted(trimmed);
    setSearchParams(trimmed ? { q: trimmed } : {});
  }

  return (
    <div className="discover-page">
      <h1 style={{ marginBottom: 4 }}>Nearby restaurants</h1>
      <p className="micro" style={{ marginTop: 0, marginBottom: 16 }}>
        {searching
          ? `Results for “${submitted}”`
          : coords
            ? 'Sorted by distance from you'
            : 'Every listed restaurant — share your location to sort by distance'}
      </p>

      <form className="row" onSubmit={onSearch} style={{ marginBottom: 16, maxWidth: 560 }}>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search restaurants…"
          aria-label="Search restaurants"
          style={{ flex: 1 }}
        />
        <button className="btn-accent btn-sm">Search</button>
      </form>

      {!coords && !searching && (
        <div className="panel stack locate-prompt" style={{ marginBottom: 16 }}>
          <span style={{ fontWeight: 600 }}>See what's actually close to you</span>
          <span className="micro" style={{ opacity: 0.8 }}>
            Share your location to sort every restaurant below by distance.
          </span>
          <button className="btn-accent btn-sm" onClick={ask} disabled={asking} style={{ alignSelf: 'flex-start' }}>
            {asking ? 'Using your location…' : '📍 Use my location'}
          </button>
          {denied && (
            <span className="micro" style={{ opacity: 0.7 }}>
              No location? No problem — every listed restaurant is still below.
            </span>
          )}
        </div>
      )}

      {/* Once location IS granted the page used to say nothing about it, so
          "sorted by distance" looked like a guess and there was no way to
          re-read a position that had gone stale (you walked somewhere). */}
      {coords && !searching && (
        <div className="row spread locate-confirmed" style={{ marginBottom: 16 }}>
          <span className="micro">✓ Using your current location</span>
          <button className="btn-ghost btn-sm" onClick={ask} disabled={asking}>
            {asking ? 'Refreshing…' : 'Refresh location'}
          </button>
        </div>
      )}

      <div className="row spread" style={{ marginBottom: 16, flexWrap: 'wrap', gap: 10 }}>
        <div className="row" role="group" aria-label="Filter" style={{ gap: 0 }}>
          <button className={filter === 'all' ? 'btn-accent btn-sm' : 'btn-ghost btn-sm'} onClick={() => setFilter('all')}>
            All
          </button>
          <button className={filter === 'nowserving' ? 'btn-accent btn-sm' : 'btn-ghost btn-sm'} onClick={() => setFilter('nowserving')}>
            On NowServing
          </button>
          <button className={filter === 'open' ? 'btn-accent btn-sm' : 'btn-ghost btn-sm'} onClick={() => setFilter('open')}>
            Open now
          </button>
        </div>
        {updatedLabel && <span className="micro" style={{ opacity: 0.6 }}>{updatedLabel}</span>}
      </div>

      {/* A location/OSM hiccup never blocks the page — it degrades to the
          last good list, with an explicit way to try again rather than a
          silent stall. */}
      {error != null && (
        <div className="row spread panel" style={{ marginBottom: 16, padding: '10px 14px' }}>
          <span className="micro" style={{ opacity: 0.8 }}>Couldn't refresh just now — showing the last known list.</span>
          <button className="btn-ghost btn-sm" onClick={() => window.location.reload()}>Retry</button>
        </div>
      )}

      {filter === 'all' && cards && cards.length > 0 && nowServingCount === 0 && (
        <div className="micro" style={{ opacity: 0.75, marginBottom: 12 }}>
          No participating NowServing restaurants nearby, but here are other restaurants around you.
        </div>
      )}

      {loading && !cards && (
        <div className="venue-grid">
          <CardSkeleton />
          <CardSkeleton />
          <CardSkeleton />
        </div>
      )}

      {!loading && visible && visible.length === 0 && (
        <div className="panel stack" style={{ textAlign: 'center' }}>
          <span style={{ fontSize: '2rem' }}>🍽️</span>
          <p style={{ color: 'var(--muted)', margin: 0 }}>
            {searching
              ? `No restaurants match “${submitted}”.`
              : cards && cards.length > 0
                // The unfiltered list has results — this is "your filter is
                // too narrow", a different fact from "nothing is nearby at
                // all", and deserves different words plus a way out.
                ? filter === 'nowserving'
                  ? 'No NowServing restaurants match right now.'
                  : 'Nothing is open right now.'
                : 'No restaurants are listed yet.'}
          </p>
          {cards && cards.length > 0 && filter !== 'all' && (
            <button className="btn-ghost btn-sm" onClick={() => setFilter('all')}>
              Show all nearby restaurants
            </button>
          )}
        </div>
      )}

      {/* Grid, not a stack: 30+ results in a single column was a scroll
          marathon on desktop. See .venue-grid — the column count comes from
          the available width, not a breakpoint table. */}
      <div className="venue-grid">
        {visible?.map((c) => (
          <RestaurantCard key={c.key} card={c} />
        ))}
      </div>
    </div>
  );
}

function CardSkeleton() {
  return (
    <div className="panel skeleton-card" aria-hidden="true">
      <div className="skeleton-line" style={{ width: '55%', height: 20 }} />
      <div className="skeleton-line" style={{ width: '35%', height: 12, marginTop: 8 }} />
      <div className="skeleton-line" style={{ width: '90%', height: 36, marginTop: 16 }} />
    </div>
  );
}

/**
 * usePolling, plus a `loading` flag that's true only until the FIRST
 * response lands — after that a slow poll just keeps the old data visible
 * (see usePolling's own contract), which is correct for steady-state but
 * indistinguishable from "still loading" without this.
 */
function usePollingWithLoading<T>(fetcher: () => Promise<T>, intervalMs: number, deps: unknown[]) {
  const { data, error } = usePolling<T>(fetcher, intervalMs, deps);
  const [hasLoadedOnce, setHasLoadedOnce] = useState(false);
  useEffect(() => {
    if (data !== null) setHasLoadedOnce(true);
  }, [data]);
  // A new dependency set (e.g. location just granted) starts a fresh load.
  useEffect(() => {
    setHasLoadedOnce(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return { data, error, loading: !hasLoadedOnce && data === null };
}
