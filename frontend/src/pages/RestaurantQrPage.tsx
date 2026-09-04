/**
 * /qr/:publicToken — where a scanned RESTAURANT QR code lands.
 *
 * This route is deliberately thin. It resolves the permanent restaurant
 * token to whatever queues that restaurant is running right now and then
 * hands off to the EXISTING venue page, which already knows how to do join,
 * reserve, directions, the 428 location retry, tickets, Leave-Now and
 * notifications. Re-implementing any of that here would mean two versions of
 * the customer journey drifting apart — the QR is a new front door, not a
 * second building.
 *
 * Three outcomes:
 *   exactly one queue  -> straight through to /v/{joinToken}, so the common
 *                         case is indistinguishable from scanning the old
 *                         queue-level code
 *   several queues     -> ask which line, because only the customer knows
 *   none               -> say so plainly. The poster on the door outlives any
 *                         individual queue, so this is a real state, not a 404
 */
import { useEffect } from 'react';
import { Navigate, useParams } from 'react-router-dom';
import { getRestaurant } from '../api/endpoints';
import { usePolling } from '../hooks/usePolling';
import { useOptionalLocation } from '../hooks/useOptionalLocation';
import { RestaurantCard, fromVenueSummary } from '../components/RestaurantCard';
import type { RestaurantDetail } from '../api/types';

export function RestaurantQrPage() {
  const { publicToken } = useParams();
  // Location is optional here exactly as everywhere else: it only sorts and
  // pre-warns about the remote radius. A scan with no location still works.
  const { coords } = useOptionalLocation();

  const { data, error } = usePolling<RestaurantDetail>(
    () => getRestaurant(publicToken!, coords?.lat, coords?.lng),
    30_000,
    [publicToken, coords?.lat, coords?.lng],
  );

  useEffect(() => {
    if (data) document.title = `${data.businessName} — NowServing`;
  }, [data?.businessName]);

  if (error != null) {
    return (
      <div className="center-page">
        <div className="panel stack" style={{ textAlign: 'center' }}>
          <h1 style={{ fontSize: '1.3rem', margin: 0 }}>Restaurant not found</h1>
          <p className="venue-meta">
            This code doesn’t match a restaurant on NowServing. It may have been
            replaced — please ask a member of staff.
          </p>
        </div>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="center-page">
        <span className="spin" />
      </div>
    );
  }

  // One line: don't make anyone choose from a list of one.
  if (data.queues.length === 1) {
    return <Navigate to={`/v/${data.queues[0].joinToken}`} replace />;
  }

  return (
    <div className="discover-page">
      <div className="discover-narrow" style={{ marginBottom: 20 }}>
        <span className="micro">You scanned</span>
        <h1 style={{ margin: '4px 0 0' }}>{data.businessName}</h1>
      </div>

      {data.queues.length === 0 ? (
        <div className="panel stack discover-narrow" style={{ textAlign: 'center' }}>
          <span style={{ fontSize: '2rem' }}>🍽️</span>
          <p className="venue-meta">
            {data.businessName} isn’t taking sign-ups through NowServing right
            now. Please ask a member of staff.
          </p>
        </div>
      ) : (
        <>
          <p className="venue-meta discover-narrow" style={{ marginBottom: 16 }}>
            Which line would you like to join?
          </p>
          <div className="venue-grid">
            {data.queues.map((q) => (
              <RestaurantCard
                key={q.joinToken}
                // The restaurant's name is already the page heading; repeating
                // it on all five cards would leave the ONE thing that tells
                // them apart — the line's name — as small print underneath.
                card={{ ...fromVenueSummary(q), name: q.queueName, address: null }}
              />
            ))}
          </div>
        </>
      )}
    </div>
  );
}
