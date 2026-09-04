/**
 * V9 — one restaurant: /v/{joinToken}. A complete detail page, not just a
 * form: everything a customer would want to check before committing —
 * current wait, how many are ahead, whether it's actually open, how to get
 * there — plus the two ways in (Join Waitlist, Reserve) and the one way in
 * for someone standing at the door (the QR code).
 *
 * THE DESIGN POINT OF THIS FILE: the "Join Waitlist" button below and the QR
 * code printed on the counter lead to the SAME queue via the SAME token. The
 * only difference is a `?remote=1` marker on the button's link, which tells
 * the backend to apply the venue's distance limit. A scanned code carries no
 * marker — the scan is itself the evidence of being there.
 *
 *      Restaurant → active queue → joinToken → { QR code, Join button }
 *
 * No second system, no parallel "online queue" that has to be reconciled with
 * the in-person one — which is the trap this design deliberately avoids.
 */
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import QRCode from 'react-qr-code';
import { errorMessage } from '../api/client';
import { getVenue } from '../api/endpoints';
import type { VenueDetail } from '../api/types';
import { usePolling } from '../hooks/usePolling';

export function VenuePage() {
  const { joinToken } = useParams();
  const [showQr, setShowQr] = useState(false);

  const { data, error } = usePolling<VenueDetail>(() => getVenue(joinToken!), 20_000, [joinToken]);

  useEffect(() => {
    if (data) document.title = `${data.venue.businessName} — NowServing`;
  }, [data]);

  if (error) {
    return (
      <div className="center-page">
        <div className="panel stack">
          <div className="error">{errorMessage(error)}</div>
          <Link className="btn-ghost btn-sm" to="/restaurants">← Back to restaurants</Link>
        </div>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="center-page">
        <div className="panel skeleton-card" aria-hidden="true" style={{ width: '100%', maxWidth: 420 }}>
          <div className="skeleton-line" style={{ width: '60%', height: 24, margin: '0 auto' }} />
          <div className="skeleton-line" style={{ width: '40%', height: 14, margin: '10px auto' }} />
          <div className="skeleton-line" style={{ width: '90%', height: 70, margin: '16px auto' }} />
          <div className="skeleton-line" style={{ width: '100%', height: 40, marginTop: 16 }} />
        </div>
      </div>
    );
  }

  const v = data.venue;
  // Remote joining is blocked when the owner turned it off, or when we
  // already know this customer is outside the radius. Either way the QR
  // route stays available, so the answer is never a dead end.
  const remoteBlocked = !v.remoteJoinAllowed || v.tooFarToJoinRemotely;
  const hasLocation = v.venueLatitude != null && v.venueLongitude != null;

  return (
    <div className="center-page">
      <div className="panel stack" style={{ textAlign: 'center', alignItems: 'center' }}>
        <Link className="micro" to="/restaurants" style={{ alignSelf: 'flex-start', color: 'var(--muted)' }}>
          ← All restaurants
        </Link>

        <h1 style={{ margin: 0 }}>{v.businessName}</h1>
        <span className="micro">{v.queueName}</span>

        {v.open ? (
          <>
            <div className="row" style={{ gap: 28, justifyContent: 'center' }}>
              <div>
                <span className="micro">Current wait</span>
                <div className="ticket-number glow" style={{ fontSize: '2.2rem' }}>
                  ~{v.estimatedWaitMinutes}–{v.estimatedWaitMaxMinutes} min
                </div>
              </div>
              <div>
                <span className="micro">Parties waiting</span>
                <div style={{ fontSize: '2.2rem', fontWeight: 800 }}>{v.partiesWaiting}</div>
              </div>
            </div>
            {v.distanceMiles != null && (
              <p style={{ color: 'var(--muted)', margin: 0 }}>{v.distanceMiles} mi away</p>
            )}

            <div className="row" style={{ width: '100%', flexWrap: 'wrap' }}>
              {!remoteBlocked && (
                <Link
                  className="btn-accent"
                  to={`/j/${joinToken}?remote=1`}
                  state={{ businessName: v.businessName }}
                  style={{ flex: 1, textAlign: 'center' }}
                >
                  Join Waitlist
                </Link>
              )}
              {data.reservationsEnabled && (
                <Link
                  className={remoteBlocked ? 'btn-accent' : 'btn-ghost'}
                  to={`/b/${joinToken}`}
                  style={{ flex: 1, textAlign: 'center' }}
                >
                  Reserve Table
                </Link>
              )}
            </div>

            {remoteBlocked && (
              <div className="stack" style={{ alignItems: 'center' }}>
                <span className="micro" style={{ color: 'var(--accent)' }}>
                  {v.tooFarToJoinRemotely
                    ? `You're ${v.distanceMiles} miles away — ${v.businessName} takes remote joins within ${v.maxRemoteJoinMiles} miles.`
                    : `${v.businessName} takes walk-ins only.`}
                </span>
                <span className="micro" style={{ opacity: 0.75 }}>
                  Scan the QR code at the door when you arrive.
                </span>
              </div>
            )}

            {/* A restaurant worth joining is worth being able to find. */}
            {hasLocation && (
              <a
                className="btn-ghost btn-sm"
                target="_blank"
                rel="noreferrer"
                href={`https://www.google.com/maps/dir/?api=1&destination=${v.venueLatitude},${v.venueLongitude}`}
                style={{ width: '100%', textAlign: 'center' }}
              >
                🧭 Directions
              </a>
            )}

            {/* Same token, drawn instead of tapped — useful for showing a
                friend, or for staff putting the code on a table tent. */}
            <div className="row" style={{ gap: 10, width: '100%' }}>
              <hr style={{ flex: 1, border: 0, borderTop: '1px solid var(--border)' }} />
              <span className="micro">or</span>
              <hr style={{ flex: 1, border: 0, borderTop: '1px solid var(--border)' }} />
            </div>
            <button className="btn-ghost btn-sm" onClick={() => setShowQr((s) => !s)}>
              {showQr ? 'Hide QR code' : 'QR Code'}
            </button>
            {showQr && (
              <div className="stack" style={{ alignItems: 'center' }}>
                <div className="qr-wrap">
                  <QRCode value={`${window.location.origin}/j/${joinToken}`} size={172} />
                </div>
                <span className="micro" style={{ opacity: 0.7 }}>
                  The same line — scanning means you're here, so no distance check.
                </span>
              </div>
            )}
          </>
        ) : (
          <>
            <h2 style={{ color: 'var(--muted)' }}>Closed right now</h2>
            <p style={{ color: 'var(--muted)', margin: 0 }}>
              {v.businessName} isn’t taking new customers at the moment.
            </p>
            <div className="row" style={{ width: '100%' }}>
              {data.reservationsEnabled && (
                <Link className="btn-accent btn-sm" to={`/b/${joinToken}`} style={{ flex: 1, textAlign: 'center' }}>
                  Reserve Table
                </Link>
              )}
              {hasLocation && (
                <a
                  className="btn-ghost btn-sm"
                  target="_blank"
                  rel="noreferrer"
                  href={`https://www.google.com/maps/dir/?api=1&destination=${v.venueLatitude},${v.venueLongitude}`}
                  style={{ flex: 1, textAlign: 'center' }}
                >
                  🧭 Directions
                </a>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
}
