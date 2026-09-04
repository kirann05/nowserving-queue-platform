/**
 * The homepage — a landing page, not the full restaurant list. That list
 * lives at /restaurants; this page's job is to get someone there (or straight to
 * a restaurant they already know) as fast as possible.
 *
 *   Home
 *    ↓ Use my location
 *   Nearby NowServing venues preview (top few, compact)
 *    ↓ [View all nearby restaurants]
 *   /restaurants — everything around you
 *
 * This replaced a homepage that redirected straight to an "Owner console"
 * login, which meant a customer who typed the domain instead of scanning a QR
 * code was shown a sign-in form for a product they don't own. Discovery
 * fixes the role confusion by giving the majority audience somewhere to land
 * — and this version keeps that first screen light rather than dumping the
 * entire list (NowServing + OpenStreetMap, filters and all) on it at once.
 */
import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getNearbyVenues } from '../api/endpoints';
import { RestaurantCard, type RestaurantCardModel } from '../components/RestaurantCard';
import { useActiveTicket } from '../hooks/useActiveTicket';
import { useOptionalLocation } from '../hooks/useOptionalLocation';
import { usePolling } from '../hooks/usePolling';

const PREVIEW_COUNT = 3;

export function DiscoverPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const { coords, asking, denied, ask } = useOptionalLocation();
  // Server-confirmed: a served or abandoned ticket must not keep announcing
  // itself here. See hooks/useActiveTicket.ts.
  const activeTicket = useActiveTicket();

  useEffect(() => {
    document.title = 'NowServing — find a table';
  }, []);

  const { data: nearbyResults } = usePolling(
    () => (coords ? getNearbyVenues(coords.lat, coords.lng) : Promise.resolve([])),
    30_000,
    [coords?.lat, coords?.lng],
  );

  const preview: RestaurantCardModel[] = (nearbyResults ?? [])
    .filter((p) => p.onNowServing)
    .slice(0, PREVIEW_COUNT)
    .map((p) => ({
      key: p.joinToken!,
      name: p.name,
      cuisine: p.cuisine,
      address: p.address,
      distanceMiles: p.distanceMiles,
      onNowServing: true,
      open: p.joinableNow,
      waitLow: p.currentWaitMinutes,
      waitHigh: p.currentWaitMinutes,
      partiesWaiting: p.partiesWaiting,
      joinToken: p.joinToken,
      remoteAllowed: p.remoteJoinEnabled,
      reservationsEnabled: p.reservationsEnabled,
      tooFar: false,
      openingStatus: null,
      latitude: p.latitude,
      longitude: p.longitude,
    }));

  function onSearch(e: FormEvent) {
    e.preventDefault();
    const trimmed = query.trim();
    navigate(trimmed ? `/restaurants?q=${encodeURIComponent(trimmed)}` : '/restaurants');
  }

  return (
    <div className="discover-page">
      <header className="discover-header discover-narrow">
        <h1 className="display">
          now<span className="glow">serving</span>
        </h1>
        <p style={{ color: 'var(--muted)', margin: 0 }}>
          See the wait before you go. Join the line from wherever you are.
        </p>
      </header>

      {/* The one persistent action worth interrupting the funnel for: don't
          make someone who already has a ticket dig through search to check
          on it. */}
      {activeTicket && (
        <Link to={`/t/${activeTicket.entryToken}`} className="panel row spread active-ticket-card discover-narrow">
          <div className="stack" style={{ gap: 2 }}>
            <span style={{ fontWeight: 600 }}>🎫 You have an active ticket</span>
            <span className="micro" style={{ opacity: 0.75 }}>
              {activeTicket.businessName ?? 'View your place in line'}
            </span>
          </div>
          <span className="btn-accent btn-sm">View my ticket</span>
        </Link>
      )}

      <form className="row discover-narrow" onSubmit={onSearch} style={{ marginBottom: 12, marginTop: activeTicket ? 16 : 0 }}>
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search restaurants…"
          aria-label="Search restaurants"
          style={{ flex: 1 }}
        />
        <button className="btn-accent btn-sm">Search</button>
      </form>

      {/* Location is asked for with an inviting prompt, never sprung on page
          load and never presented as something having gone wrong. A denied
          or unavailable permission still leaves the page fully usable. */}
      {!coords && (
        <div className="panel stack locate-prompt discover-narrow" style={{ marginBottom: 16 }}>
          <span style={{ fontWeight: 600 }}>Find restaurants near you</span>
          <span className="micro" style={{ opacity: 0.8 }}>
            Share your location to see nearby restaurants and live wait times.
          </span>
          <button className="btn-accent btn-sm" onClick={ask} disabled={asking} style={{ alignSelf: 'flex-start' }}>
            {asking ? 'Using your location…' : '📍 Use my location'}
          </button>
          {denied && (
            <span className="micro" style={{ opacity: 0.7 }}>
              No location? No problem — <Link to="/restaurants">browse every listed restaurant</Link> instead.
            </span>
          )}
        </div>
      )}

      {coords && (
        <div className="stack" style={{ marginBottom: 16 }}>
          <div className="row spread locate-confirmed">
            <span className="micro">✓ Using your current location</span>
            <button className="btn-ghost btn-sm" onClick={ask} disabled={asking}>
              {asking ? 'Refreshing…' : 'Refresh location'}
            </button>
          </div>
          <span className="micro">Near you</span>
          {preview.length > 0 ? (
            <div className="venue-grid venue-grid-compact">
              {preview.map((c) => (
                <RestaurantCard key={c.key} card={c} compact />
              ))}
            </div>
          ) : (
            <div className="micro" style={{ opacity: 0.7 }}>
              No participating NowServing restaurants right nearby — there may still be other restaurants close by.
            </div>
          )}
        </div>
      )}

      {/* sessionStorage (via useOptionalLocation) carries the granted
          coordinates over to /restaurants — no need to thread them through the
          URL, and no second permission click if they're already granted. */}
      <Link to="/restaurants" className="btn-primary discover-narrow" style={{ display: 'block', textAlign: 'center', width: '100%' }}>
        View all nearby restaurants →
      </Link>

      {/* The owner door — present, findable, and deliberately small. Owners
          are a handful of people who bookmark it; customers are everyone
          else and should never have to scroll past it. */}
      <footer style={{ marginTop: 32, textAlign: 'center' }}>
        <Link className="micro" to="/owner" style={{ color: 'var(--muted)' }}>
          Run a restaurant? Open the owner console →
        </Link>
      </footer>
    </div>
  );
}
