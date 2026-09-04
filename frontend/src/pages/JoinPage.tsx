/**
 * NS-10 / P1 / V9: the join form — /j/{joinToken}, reached by scanning the QR
 * or by tapping "Join Waitlist" on the venue page.
 *
 * V9's rule for this screen: IT ASKS FOR TWO THINGS. Name, party size, done.
 *
 * Everything else moved out or later. Notification choice and location
 * sharing are now offered on the ticket page, AFTER the customer is already
 * in the line — because a permission prompt shown before someone has anything
 * to lose is a prompt they decline. The one exception is the remote
 * eligibility check, and even that is demand-driven: we don't touch
 * geolocation until the server answers 428 and tells us it's required.
 *
 * Multi-ticket devices: a family shares one phone, so ONE ticket per queue in
 * localStorage was a real bug (the second join silently overwrote the first).
 * We keep an ARRAY of tickets per queue. The entry token in each URL remains
 * the actual authorisation — localStorage is only a convenience index.
 */
import { useMemo, useState, type FormEvent } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { getVenue, joinQueue, type JoinOptions } from '../api/endpoints';
import { usePolling } from '../hooks/usePolling';
import type { VenueDetail } from '../api/types';
import { saveTicket } from '../api/ticketStorage';
import { useLiveTicketsForQueue } from '../hooks/useActiveTicket';

/** Ask the browser for a one-off position. Rejects rather than resolving null
 *  so the caller can tell "declined" from "granted but empty". */
function currentPosition(): Promise<{ latitude: number; longitude: number }> {
  return new Promise((resolve, reject) => {
    if (!('geolocation' in navigator)) return reject(new Error('unsupported'));
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude }),
      reject,
      { timeout: 10_000 },
    );
  });
}

export function JoinPage() {
  const { joinToken } = useParams();
  const [params] = useSearchParams();
  const navigate = useNavigate();
  // Carried from a Link's `state` (RestaurantCard, VenuePage) when this page
  // was reached from within the app — absent for a QR scan straight from a
  // poster, in which case the ticket is just saved without a business name.
  const businessName = (useLocation().state as { businessName?: string } | null)?.businessName;

  // ?remote=1 is added ONLY by the discovery page's "Join Waitlist" button.
  // A scanned QR code carries no marker, so it defaults to "I'm here" —
  // which is what keeps every already-printed poster working. See the note
  // in PublicDtos.JoinRequest for why the default falls this way.
  const remote = params.get('remote') === '1';

  const [name, setName] = useState('');
  const [partySize, setPartySize] = useState(2);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  /** Copy from the server's 428, shown while we ask for location. */
  const [locationPrompt, setLocationPrompt] = useState('');

  // The form used to show only the NowServing wordmark, so someone arriving
  // from a QR code (or three tabs deep) had no confirmation of WHICH
  // restaurant they were about to join, or what they were signing up to
  // wait for. One cheap read fixes both.
  const { data: venue } = usePolling<VenueDetail>(() => getVenue(joinToken!), 30_000, [joinToken]);
  const venueName = venue?.venue.businessName ?? businessName;

  // Only tickets the SERVER still considers live. Offering "Continue as …"
  // straight from localStorage meant an already-served customer was invited
  // back to a finished ticket instead of being able to join again.
  // `undefined` = still checking.
  const existing = useLiveTicketsForQueue(joinToken);
  const [dismissedExisting, setDismissedExisting] = useState(false);
  // Default to the form, and only pull it back if live tickets turn up —
  // starting closed would flash an empty panel on the common (no tickets)
  // path while the check is in flight.
  const showForm = dismissedExisting || existing?.length === 0;

  // One key per join ATTEMPT: a double-tap or a retry on flaky wifi returns
  // the SAME ticket instead of minting a second one (Idempotency-Key). It
  // deliberately survives the location retry below — that second call is the
  // same join, so it must not consume a second place in the line.
  const idempotencyKey = useMemo(() => crypto.randomUUID(), []);

  async function attemptJoin(options: JoinOptions) {
    const ticket = await joinQueue(joinToken!, name, partySize, idempotencyKey, {
      remote,
      ...options,
    });
    saveTicket(joinToken!, {
      entryToken: ticket.entryToken,
      customerName: name,
      createdAt: new Date().toISOString(),
      // venueName (fetched) beats the link-state name: a QR scan carries no
      // state, so without this a poster join would save a nameless ticket
      // and the "My Ticket" shortcut would have nothing to label it with.
      businessName: venueName,
    });
    // ?joined=1 tells the ticket page this person just arrived, so it can
    // offer notifications and Leave-Now once — the "ask after, not before"
    // half of the flow.
    navigate(`/t/${ticket.entryToken}?joined=1`, { replace: true });
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    setLocationPrompt('');
    try {
      await attemptJoin({});
    } catch (err) {
      // 428 = "this restaurant limits how far away you can be; I need a
      // location to decide". The status code carries that meaning so we
      // never string-match on error copy.
      const status = (err as { response?: { status?: number } })?.response?.status;
      if (status === 428) {
        setLocationPrompt(errorMessage(err));
        try {
          const pos = await currentPosition();
          await attemptJoin(pos);
          return;
        } catch (locErr) {
          const s = (locErr as { response?: { status?: number } })?.response?.status;
          setError(
            s
              // The server answered — it's a real refusal (too far away), and
              // its message names the actual distance.
              ? errorMessage(locErr)
              : 'We need your rough location to confirm you’re close enough to join. Allow location access, or scan the QR code when you arrive.',
          );
          setLocationPrompt('');
          return;
        }
      }
      setError(errorMessage(err)); // 400 closed/too far, 404 bad link, 429 rate limited
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="center-page">
      <div className="panel stack">
        <div className="stack" style={{ gap: 4 }}>
          <span className="micro">Join the line</span>
          {venueName ? (
            <h1 style={{ margin: 0, fontSize: '1.6rem' }}>{venueName}</h1>
          ) : (
            /* Still loading, or a bare QR link we have no name for yet —
               never leave the heading blank and jumping. */
            <div className="skeleton-line" style={{ width: '60%', height: 26 }} aria-hidden="true" />
          )}
          {venue?.venue.open && (
            <span className="micro">
              Current wait ~{venue.venue.estimatedWaitMinutes}–{venue.venue.estimatedWaitMaxMinutes} min
              {venue.venue.partiesWaiting > 0 && ` · ${venue.venue.partiesWaiting} ahead of you`}
            </span>
          )}
          {venue && !venue.venue.open && (
            <span className="micro" style={{ color: 'var(--danger)' }}>
              This restaurant is closed right now.
            </span>
          )}
        </div>

        {existing && existing.length > 0 && (
          <div className="stack">
            <span className="micro">Your tickets in this queue</span>
            {existing.map((t) => (
              <button key={t.entryToken} className="btn-ghost"
                onClick={() => navigate(`/t/${t.entryToken}`)}>
                Continue as {t.customerName}
              </button>
            ))}
            {!showForm && (
              <button className="btn-accent" onClick={() => setDismissedExisting(true)}>
                Join as someone else
              </button>
            )}
          </div>
        )}

        {showForm && (
          <form className="stack" onSubmit={onSubmit}>
            <label>
              <span className="micro">Name</span>
              <input value={name} onChange={(e) => setName(e.target.value)} required autoFocus maxLength={100} />
            </label>

            {/* A stepper, not a number input: on a phone this is two taps
                instead of a keyboard, and party size is almost always 1–4. */}
            <div>
              <span className="micro">Party size</span>
              <div className="row" style={{ alignItems: 'center', gap: 14, marginTop: 4 }}>
                <button type="button" className="btn-ghost btn-sm" aria-label="Fewer people"
                  onClick={() => setPartySize((n) => Math.max(1, n - 1))}>
                  −
                </button>
                <span aria-live="polite" style={{ fontSize: '1.5rem', fontWeight: 800, minWidth: 32, textAlign: 'center' }}>
                  {partySize}
                </span>
                <button type="button" className="btn-ghost btn-sm" aria-label="More people"
                  onClick={() => setPartySize((n) => Math.min(20, n + 1))}>
                  +
                </button>
              </div>
            </div>

            {locationPrompt && <div className="micro" style={{ color: 'var(--accent)' }}>{locationPrompt}</div>}
            {error && <div className="error">{error}</div>}

            <button className="btn-accent" disabled={busy}>
              {busy ? 'Joining…' : 'Join Waitlist'}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
