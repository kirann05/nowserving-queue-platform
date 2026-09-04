/**
 * One restaurant card, shared by the homepage's preview list and the full
 * "/nearby" list — a NowServing tenant and a bare OpenStreetMap listing
 * render from the SAME component, the second just has fewer facts and no
 * button that implies you can join a line it doesn't have.
 *
 * Card contents (golden-standard "scan, don't read" density):
 *   name · cuisine · distance
 *   open/closed (NowServing) or open now/closed/hours unavailable (external)
 *   NOWSERVING badge + current wait, when participating
 *   Join Waitlist / Reserve — only the ones actually supported
 */
import { Link } from 'react-router-dom';
import type { NearbyPlaceResponse, VenueSummary } from '../api/types';

export interface RestaurantCardModel {
  key: string;
  name: string;
  cuisine: string | null;
  address: string | null;
  distanceMiles: number | null;
  onNowServing: boolean;
  open: boolean | null; // null = unknown / not applicable (external place)
  waitLow: number | null;
  waitHigh: number | null;
  partiesWaiting: number | null;
  joinToken: string | null;
  remoteAllowed: boolean | null;
  reservationsEnabled: boolean | null;
  tooFar: boolean;
  openingStatus: 'OPEN_NOW' | 'CLOSED' | 'UNKNOWN' | null;
  latitude: number | null;
  longitude: number | null;
}

export function fromVenueSummary(v: VenueSummary): RestaurantCardModel {
  return {
    key: v.joinToken,
    name: v.businessName,
    cuisine: null, // NowServing doesn't collect this yet — see queueName below
    address: v.queueName,
    distanceMiles: v.distanceMiles,
    onNowServing: true,
    open: v.open,
    waitLow: v.estimatedWaitMinutes,
    waitHigh: v.estimatedWaitMaxMinutes,
    partiesWaiting: v.partiesWaiting,
    joinToken: v.joinToken,
    remoteAllowed: v.remoteJoinAllowed,
    reservationsEnabled: v.reservationsEnabled,
    tooFar: v.tooFarToJoinRemotely,
    openingStatus: null,
    latitude: v.venueLatitude,
    longitude: v.venueLongitude,
  };
}

export function fromNearbyPlace(p: NearbyPlaceResponse): RestaurantCardModel {
  return {
    key: p.joinToken ?? `osm:${p.name}:${p.distanceMiles}`,
    name: p.name,
    cuisine: p.cuisine,
    address: p.address,
    distanceMiles: p.distanceMiles,
    onNowServing: p.onNowServing,
    open: p.joinableNow,
    waitLow: p.currentWaitMinutes,
    waitHigh: p.currentWaitMinutes, // nearby doesn't widen into a range
    partiesWaiting: p.partiesWaiting,
    joinToken: p.joinToken,
    remoteAllowed: p.remoteJoinEnabled,
    reservationsEnabled: p.reservationsEnabled,
    tooFar: false, // remote-distance is enforced at join time, not in the list
    openingStatus: p.openingStatus,
    latitude: p.latitude,
    longitude: p.longitude,
  };
}

/**
 * OpenStreetMap's `cuisine` tag is a raw, semicolon-joined, snake_case list
 * written by whoever last edited the map — "thai;salad;soup;noodle;noodles",
 * "greek;turkish;mediterranean;kebab;middle_eastern". Rendered verbatim it
 * read as debug output, and the long ones were wide enough to shove the
 * card's own badges outside its border.
 *
 * Two cuisines is the useful amount: it says what kind of place this is
 * without turning the subtitle into a tag dump.
 */
const MAX_CUISINES = 2;

function formatCuisine(raw: string | null): string | null {
  if (!raw) return null;
  const parts = raw
    .split(';')
    .map((s) => s.trim().replace(/_/g, ' '))
    .filter(Boolean)
    // "TEX-MEX" and "middle eastern" both need every word capitalised.
    .map((s) => s.replace(/\b\w/g, (c) => c.toUpperCase()));
  if (parts.length === 0) return null;
  return parts.slice(0, MAX_CUISINES).join(' · ');
}

/** cuisine, or the queue name, or a street address — whichever the source
 *  actually has, so the subtitle line is never left visibly empty. */
function subtitle(v: RestaurantCardModel): string | null {
  return formatCuisine(v.cuisine) ?? v.address;
}

/** The one-line status shown under the name. Null when we genuinely don't
 *  know, so the card can stay quiet rather than assert something false. */
function statusLabel(v: RestaurantCardModel): { text: string; tone: 'ok' | 'muted' } | null {
  if (v.onNowServing) return { text: v.open ? 'Open' : 'Closed', tone: v.open ? 'ok' : 'muted' };
  if (v.openingStatus === 'OPEN_NOW') return { text: 'Open now', tone: 'ok' };
  if (v.openingStatus === 'CLOSED') return { text: 'Closed', tone: 'muted' };
  return null;
}

export function RestaurantCard({ card: v, compact }: { card: RestaurantCardModel; compact?: boolean }) {
  // "Listed" and "open" are different facts and must stay that way.
  // listedPublicly (enforced server-side) decides whether this card exists
  // at all; open decides only what you can DO on it. A closed participating
  // restaurant keeps its card, its name, its status and its Directions —
  // it just stops taking new joins and bookings.
  const showJoin = v.onNowServing && v.open === true && !v.tooFar && v.remoteAllowed !== false;
  // Reservations additionally require the venue to be open: taking a booking
  // through a closed queue would create one nobody is expecting.
  const showReserve = v.onNowServing && v.reservationsEnabled === true && v.open === true;
  const closedNowServing = v.onNowServing && v.open === false;
  const status = statusLabel(v);
  const directionsHref =
    v.latitude != null && v.longitude != null
      ? `https://www.google.com/maps/dir/?api=1&destination=${v.latitude},${v.longitude}`
      // No coordinates on file (an owner who hasn't set a location yet, or an
      // OSM node we only know by name) — fall back to a maps search.
      : `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(
          [v.name, v.address].filter(Boolean).join(' '),
        )}`;

  return (
    <article
      className={`panel stack venue-card${compact ? ' venue-card-compact' : ''}${
        v.onNowServing ? ' venue-card-live' : ''
      }`}
    >
      {/* ONE line of identity, then ONE line of facts.
          This used to be two side-by-side columns: name on the left, a
          stack of badges on the right. The left column had no min-width, so
          a long cuisine string ("greek;turkish;mediterranean;kebab;…")
          could not shrink and pushed the badge column clean outside the
          card, overlapping the neighbouring one. Collapsing the facts into
          a single wrapping meta line removes the collision by construction,
          and leaves the amber badge as the only thing competing with the
          name — which is the one distinction that matters here. */}
      <div className="venue-card-head">
        <h2 className="venue-card-title" style={{ fontSize: compact ? '1.05rem' : '1.2rem' }}>
          {v.name}
        </h2>
        {v.onNowServing && <span className="badge badge-nowserving">NOWSERVING</span>}
      </div>

      <p className="venue-meta">
        {[subtitle(v), v.distanceMiles != null ? `${v.distanceMiles} mi` : null]
          .filter(Boolean)
          .join(' · ')}
        {status && (
          <>
            {(subtitle(v) || v.distanceMiles != null) && ' · '}
            <span className={status.tone === 'ok' ? 'venue-open' : undefined}>{status.text}</span>
          </>
        )}
      </p>

      {/* Said once, quietly, and only where it changes what you can do.
          As a bordered uppercase badge repeated down 30 external cards it
          dominated the page and made the list look like a debug dump. */}
      {!v.onNowServing && <p className="venue-note">Not on NowServing · waitlist unavailable</p>}

      {/* The live numbers are the whole reason this product exists, so on a
          participating card they get the emphasis the badges used to take. */}
      {v.onNowServing && !compact && (
        <div className="venue-stats">
          <div>
            <span className="micro">Current wait</span>
            <div className="glow venue-stat-value">
              {v.open
                ? v.waitLow === v.waitHigh
                  ? `~${v.waitLow} min`
                  : `~${v.waitLow}–${v.waitHigh} min`
                : '—'}
            </div>
          </div>
          {v.partiesWaiting != null && (
            <div>
              <span className="micro">Parties waiting</span>
              <div className="venue-stat-value">{v.partiesWaiting}</div>
            </div>
          )}
        </div>
      )}
      {v.onNowServing && compact && v.open && (
        <span className="micro">
          ~{v.waitLow} min wait
          {v.partiesWaiting != null && ` · ${v.partiesWaiting} waiting`}
        </span>
      )}

      {/* Say NOW what would otherwise be a rejection three screens later. */}
      {v.onNowServing && v.open && v.tooFar && (
        <span className="micro" style={{ color: 'var(--accent)' }}>
          Too far to join remotely from here — you can still scan the QR when you arrive.
        </span>
      )}
      {v.onNowServing && v.open && v.remoteAllowed === false && (
        <span className="micro" style={{ opacity: 0.75 }}>
          Walk-ins only — scan the QR code at the door.
        </span>
      )}

      {/* Closed is a temporary state of a restaurant that still exists, so
          the card SAYS so instead of quietly dropping its buttons and
          leaving the customer to wonder what happened. */}
      {closedNowServing && !compact && (
        <p className="venue-note">Waitlist and reservations are closed right now.</p>
      )}

      {v.onNowServing ? (
        <div className="row venue-actions">
          <Link className="btn-ghost btn-sm" to={`/v/${v.joinToken}`}>
            View restaurant
          </Link>
          {!compact && showJoin && (
            <Link
              className="btn-accent btn-sm"
              to={`/j/${v.joinToken}?remote=1`}
              state={{ businessName: v.name }}
            >
              Join Waitlist
            </Link>
          )}
          {/* Reserve used to render only when Join was absent, so the one
              venue that supports BOTH advertised neither its reservations
              nor a way to reach them from the list. They are different
              intentions — "I'll wait now" vs "hold me a table later" — and
              a venue offering both should show both. */}
          {!compact && showReserve && (
            <Link className="btn-ghost btn-sm" to={`/b/${v.joinToken}`}>
              Reserve
            </Link>
          )}
          {!compact && (
            <a className="btn-ghost btn-sm" href={directionsHref} target="_blank" rel="noreferrer noopener">
              Directions ↗
            </a>
          )}
        </div>
      ) : (
        !compact && (
          /* An external place has no queue, so it gets no Join or Reserve —
             but it must still DO something. Previously these cards ended in
             a line of untappable text, which made roughly 30 of the ~31
             results on a typical search dead ends. */
          <div className="row venue-actions">
            <a className="btn-ghost btn-sm" href={directionsHref} target="_blank" rel="noreferrer noopener">
              Directions ↗
            </a>
          </div>
        )
      )}
    </article>
  );
}
