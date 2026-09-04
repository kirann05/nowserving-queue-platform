/**
 * NS-10 / V9: the customer's live ticket — /t/{entryToken}. THE hero screen
 * of the product.
 *
 * V9 restructured it around one idea: the screen should answer the question
 * the customer actually has, which is not "what number am I?" but "what do I
 * do right now?". So the page reads as a single sentence, top to bottom:
 *
 *      YOU'RE #6  ·  5 parties ahead
 *      Expected turn   7:15 – 7:25 PM
 *      Current drive   18 min (+4 traffic)
 *      ──────────────────────────────
 *      LEAVE IN 12 MIN        ← the answer
 *      [I'm on my way] [Directions]
 *
 * A plain waitlist app stops at the first line. Everything below it is the
 * part that makes the wait usable, and it's why this screen — not the
 * dashboard — is the one worth showing people.
 *
 * The URL contains the private entry_token, so a bookmark or refresh always
 * works. Position arrives by WebSocket push, with polling as the fallback.
 */
import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { getNotificationCapabilities, getPosition, leaveQueue, sendFeedback, setNotifyPreference } from '../api/endpoints';
import { errorMessage } from '../api/client';
import { usePolling } from '../hooks/usePolling';
import { useLiveTopic } from '../realtime/useLiveTopic';
import { usePushNotifications } from '../push/usePushNotifications';
import { useLeaveNow } from '../push/useLeaveNow';
import type { PositionResponse } from '../api/types';

const fmtClock = (iso: string | null) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : '—';
const addMinutes = (iso: string, m: number) => new Date(new Date(iso).getTime() + m * 60_000).toISOString();
/** "12 min" / "1 hr 15 min" — a raw minute count is fine for a 20-minute
 *  wait and unreadable for anything longer ("wait time: 1821 min"). */
const fmtDuration = (mins: number) => {
  if (mins < 60) return `${mins} min`;
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return m === 0 ? `${h} hr` : `${h} hr ${m} min`;
};
const minutesUntil = (iso: string | null) =>
  iso ? Math.max(0, Math.round((new Date(iso).getTime() - Date.now()) / 60_000)) : 0;

export function TicketPage() {
  const { entryToken } = useParams();
  const [params] = useSearchParams();
  // Set by the join flow. Distinguishes "just got here, offer them things"
  // from "came back to check" — a returning customer shouldn't be asked the
  // same two questions every visit.
  const justJoined = params.get('joined') === '1';

  // Sprint 2: the server PUSHES fresh PositionResponses on this topic the
  // moment the line changes — same JSON shape as the REST endpoint, so both
  // transports feed the same `latest` state.
  const [pushed, setPushed] = useState<PositionResponse | null>(null);
  const { live } = useLiveTopic<PositionResponse>(`/topic/entries/${entryToken}`, setPushed);

  // Polling stays as the fallback, relaxed to 30s now that push does the
  // real work (was 10s when it was the only transport).
  const { data: polled, error } = usePolling(() => getPosition(entryToken!), 30_000, [entryToken]);

  // Whichever transport spoke LAST wins; a push always beats an older poll.
  const data = pushed ?? polled;

  const push = usePushNotifications(entryToken);
  const journey = useLeaveNow(entryToken, data?.status === 'WAITING');

  const [feedbackGiven, setFeedbackGiven] = useState(false);
  const [stars, setStars] = useState(0);
  const [comment, setComment] = useState('');
  const [left, setLeft] = useState(false);
  // The one-time post-join offer ("want us to tell you when to leave?").
  const [offerDismissed, setOfferDismissed] = useState(false);

  async function onLeave() {
    if (!confirm('Leave the line? Your spot will be given up.')) return;
    try {
      await leaveQueue(entryToken!);
      setPushed(null);
      setLeft(true);
    } catch { /* terminal states can't leave; the next poll shows why */ }
  }

  async function onFeedback() {
    if (stars === 0) return;
    try {
      await sendFeedback(entryToken!, stars, comment.trim() || undefined);
      setFeedbackGiven(true);
    } catch { /* non-critical */ }
  }

  if (error != null) {
    return (
      <div className="center-page">
        <div className="panel stack">
          <span className="micro">Your ticket</span>
          <div className="error">{errorMessage(error)}</div>
        </div>
      </div>
    );
  }

  if (data === null) {
    return (
      <div className="center-page">
        <span className="spin" />
      </div>
    );
  }

  const j = journey.status;
  const sharing = journey.locationState === 'on' && j?.sharingLocation;
  // Show the post-join offer only to someone who has just arrived and hasn't
  // already turned location on.
  const showJoinedOffer = justJoined && !offerDismissed && !sharing && journey.locationState === 'off';
  // The three states the restaurant is done with. CALLED is deliberately not
  // here: it means "your table is ready", which is the opposite of finished.
  const isFinished =
    data.status === 'SERVED' || data.status === 'NO_SHOW' || data.status === 'LEFT';

  return (
    <div className="center-page">
      <div className="panel stack" style={{ textAlign: 'center', alignItems: 'center' }}>
        {/* V9: name the restaurant. This page is now opened hours later,
            from a notification, by someone who browsed several venues —
            "you're #6" with no name on it is genuinely ambiguous. */}
        {data.businessName && <h1 style={{ margin: 0, fontSize: '1.4rem' }}>{data.businessName}</h1>}
        {/* "your place in line · ● live" is only true while there IS a place
            in line. A finished ticket kept claiming a live connection to a
            queue it had already left. */}
        <span className="micro">
          {isFinished ? 'your visit' : 'your place in line'}
          {live && !isFinished && <span style={{ color: 'var(--ok)' }}> · ● live</span>}
        </span>

        {data.status === 'WAITING' && (
          <>
            {/* ── 1. WHERE YOU ARE ─────────────────────────────────────── */}
            <div className="stack" style={{ gap: 2, alignItems: 'center' }}>
              <span className="micro">you're</span>
              <div className={`ticket-number ${data.position === 1 ? 'glow' : ''}`}>#{data.position}</div>
              <p style={{ color: 'var(--muted)', margin: 0 }}>
                {data.position === 1
                  ? 'You’re next — head over now'
                  : `${data.peopleAhead} ${data.peopleAhead === 1 ? 'party' : 'parties'} ahead`}
              </p>
            </div>

            {/* ── 2. WHEN IT WILL BE YOUR TURN ─────────────────────────── */}
            {/* Needs no location at all — a window, not a fake-precise minute. */}
            {j?.expectedTurnAt && (
              <div className="hero-stat">
                <span className="micro">Estimated turn</span>
                <div className="glow" style={{ fontSize: '1.5rem', fontWeight: 800 }}>
                  {fmtClock(j.expectedTurnAt)} – {fmtClock(addMinutes(j.expectedTurnAt, j.turnWindowMinutes))}
                </div>
              </div>
            )}

            {/* ── 3. HOW FAR AWAY YOU ARE ──────────────────────────────── */}
            {sharing && j && (
              <div className="hero-stat">
                <span className="micro">Current drive</span>
                <div style={{ fontSize: '1.5rem', fontWeight: 800 }}>{j.travelMinutes} min</div>
                <span className="micro" style={{ opacity: 0.75 }}>
                  {j.trafficAware
                    ? j.trafficDelayMinutes
                      ? `+${j.trafficDelayMinutes} min traffic`
                      : 'in current traffic'
                    : 'rough estimate'}
                </span>
              </div>
            )}

            {/* ── 4. THE ANSWER ────────────────────────────────────────── */}
            {sharing && j && (
              <>
                <hr style={{ width: '100%', border: 0, borderTop: '1px solid var(--border)' }} />
                {j.shouldLeaveNow ? (
                  <div className="leave-now-banner">
                    <div className="glow" style={{ fontSize: '2.2rem', fontWeight: 900, letterSpacing: '0.04em' }}>
                      LEAVE NOW
                    </div>
                  </div>
                ) : (
                  <div className="stack" style={{ gap: 2, alignItems: 'center' }}>
                    <span className="micro">leave in</span>
                    <div className="glow" style={{ fontSize: '2.2rem', fontWeight: 900, lineHeight: 1 }}>
                      {minutesUntil(j.leaveBy)} MIN
                    </div>
                    <span className="micro" style={{ opacity: 0.75 }}>
                      You can stay where you are for now · around {fmtClock(j.leaveBy)}
                    </span>
                  </div>
                )}

                <div className="row" style={{ justifyContent: 'center', flexWrap: 'wrap' }}>
                  {!j.enRoute && (
                    <button className="btn-accent btn-sm" onClick={journey.onMyWay}>
                      I'm on my way
                    </button>
                  )}
                  {j.venueLatitude != null && (
                    <a className="btn-ghost btn-sm" target="_blank" rel="noreferrer"
                      href={`https://www.google.com/maps/dir/?api=1&destination=${j.venueLatitude},${j.venueLongitude}`}>
                      Directions
                    </a>
                  )}
                  {j.enRoute && !j.graceExpiresAt && (
                    <button className="btn-ghost btn-sm" onClick={journey.requestHold}>
                      Hold my spot
                    </button>
                  )}
                </div>

                {j.enRoute && (
                  <span className="micro" style={{ color: 'var(--ok)' }}>✓ Staff know you're on the way</span>
                )}
                {j.graceExpiresAt && (
                  <span className="micro" style={{ color: 'var(--accent)' }}>
                    Spot held until {fmtClock(j.graceExpiresAt)}
                  </span>
                )}
                <button className="btn-ghost btn-sm" onClick={journey.disableLocation}
                  style={{ color: 'var(--muted)' }}>
                  Stop sharing location
                </button>
              </>
            )}

            {/* ── 5. THE POST-JOIN OFFER ───────────────────────────────── */}
            {/* Asked HERE, not on the join form. They already have a ticket,
                so the value of the permission is now obvious and concrete. */}
            {showJoinedOffer && (
              <div className="panel stack" style={{ background: 'var(--bg)', width: '100%' }}>
                <span style={{ fontWeight: 600 }}>Want us to tell you when to leave?</span>
                <span className="micro" style={{ opacity: 0.75 }}>
                  We’ll work out your drive time and buzz you at the right moment. Your position is
                  stored roughly (~1km) and only until you're served.
                </span>
                <div className="row" style={{ justifyContent: 'center' }}>
                  <button className="btn-accent btn-sm" onClick={journey.enableLocation}>
                    Share location
                  </button>
                  <button className="btn-ghost btn-sm" onClick={() => setOfferDismissed(true)}>
                    Not now
                  </button>
                </div>
              </div>
            )}

            {journey.locationState === 'requesting' && <span className="micro">Locating…</span>}
            {journey.locationState === 'denied' && (
              <span className="micro">
                Location is blocked for this site — you'll still get your position and "you're next".
              </span>
            )}
            {/* Something went wrong that a second tap might fix, so SAY so and
                offer the tap. This branch is the whole reason 'failed' exists
                separately from 'denied': the old code silently fell back to
                'off', which redrew the same button and looked like nothing
                had happened at all. */}
            {journey.locationState === 'failed' && (
              <div className="stack" style={{ gap: 6, alignItems: 'center' }}>
                <span className="micro" style={{ color: 'var(--danger)' }}>
                  {journey.locationProblem ?? 'Couldn’t share your location.'}
                </span>
                <button className="btn-ghost btn-sm" onClick={journey.enableLocation}>
                  Try again
                </button>
              </div>
            )}
            {!showJoinedOffer && !sharing && journey.locationState === 'off' && (
              <button className="btn-ghost btn-sm" onClick={journey.enableLocation}>
                📍 Tell me when to leave
              </button>
            )}

            {/* ── 6. HOW WE REACH YOU ──────────────────────────────────── */}
            <NotifyChoice entryToken={entryToken!} push={push} />

            <button className="btn-ghost btn-sm" onClick={onLeave} style={{ color: 'var(--muted)' }}>
              Leave the line
            </button>
          </>
        )}

        {(data.status === 'SERVED' || data.status === 'CALLED') && (
          <>
            <div className="ticket-number glow">★</div>
            <h2 className="glow">You're all set 🎉</h2>
            {data.servedAt && (
              <p style={{ color: 'var(--muted)' }}>
                Served at {fmtClock(data.servedAt)}
                {data.waitedMinutes != null && <> · waited {fmtDuration(data.waitedMinutes)}</>}
              </p>
            )}
            {!feedbackGiven ? (
              <div className="stack" style={{ alignItems: 'center', width: '100%' }}>
                <span className="micro">How was your visit?</span>
                <div className="row" role="radiogroup" aria-label="Rate 1 to 5 stars">
                  {[1, 2, 3, 4, 5].map((n) => (
                    <button
                      key={n}
                      aria-label={`${n} star${n > 1 ? 's' : ''}`}
                      onClick={() => setStars(n)}
                      style={{
                        background: 'none', border: 'none', padding: 4,
                        fontSize: '1.8rem', cursor: 'pointer',
                        filter: n <= stars ? 'none' : 'grayscale(1) opacity(0.35)',
                      }}
                    >
                      ⭐
                    </button>
                  ))}
                </div>
                {stars > 0 && (
                  <>
                    <textarea
                      value={comment}
                      onChange={(e) => setComment(e.target.value)}
                      maxLength={1000}
                      rows={2}
                      placeholder="Anything we should improve? (optional)"
                      style={{
                        width: '100%', background: 'var(--bg)', color: 'var(--text)',
                        border: '1px solid var(--border)', borderRadius: 8, padding: 10,
                        font: 'inherit', resize: 'vertical',
                      }}
                    />
                    <button className="btn-accent btn-sm" onClick={onFeedback}>
                      Send feedback
                    </button>
                  </>
                )}
              </div>
            ) : (
              <span className="micro" style={{ color: 'var(--ok)' }}>
                ✓ Thanks, {'⭐'.repeat(stars)} saved!
              </span>
            )}
          </>
        )}

        {(left || data.status === 'LEFT') && (
          <>
            <h2>You left the line</h2>
            <p style={{ color: 'var(--muted)' }}>Changed your mind? Scan the QR again to rejoin.</p>
          </>
        )}

        {data.status === 'NO_SHOW' && (
          <>
            <h2>Removed from the line</h2>
            <p style={{ color: 'var(--muted)' }}>
              You were marked as a no-show. Scan the QR again to rejoin.
            </p>
          </>
        )}

        {/* Every finished ticket needs a road out. Without this the last
            screen of the whole customer journey — the one a demo ends on —
            had no action at all: the story just stopped. */}
        {isFinished && (
          <Link className="btn-ghost btn-sm" to="/restaurants" style={{ marginTop: 8 }}>
            Back to restaurants
          </Link>
        )}
      </div>
    </div>
  );
}

/**
 * V9 — Web Push primary, SMS fallback, exactly the order the PRD recommends
 * for time-critical alerts. Email is deliberately absent: a "leave now" that
 * lands in a promotions tab twenty minutes late is worse than no alert at
 * all, because the customer trusted it.
 *
 * SMS is offered as the answer to a real failure, not as a menu item — it
 * appears once Web Push has been blocked or is unsupported, which is exactly
 * when someone needs a second way to be reached.
 */
function NotifyChoice({
  entryToken,
  push,
}: {
  entryToken: string;
  push: ReturnType<typeof usePushNotifications>;
}) {
  const [phone, setPhone] = useState('');
  const [smsSaved, setSmsSaved] = useState(false);
  const [showSms, setShowSms] = useState(false);
  // Whether the SERVER has SMS credentials. undefined = still asking.
  // Offering "Text me instead" on a deployment with no Twilio account is a
  // promise the product can't keep, and this page is where a customer
  // decides whether it's safe to close the tab.
  const [smsAvailable, setSmsAvailable] = useState<boolean | undefined>(undefined);
  useEffect(() => {
    let cancelled = false;
    getNotificationCapabilities()
      .then((c) => !cancelled && setSmsAvailable(c.smsAvailable))
      .catch(() => !cancelled && setSmsAvailable(false));
    return () => { cancelled = true; };
  }, []);

  async function saveSms() {
    if (!phone.trim()) return;
    try {
      await setNotifyPreference(entryToken, 'SMS', phone.trim());
      setSmsSaved(true);
    } catch { /* non-critical: they still have the live page */ }
  }

  if (push.state === 'enabled') {
    return (
      <span className="micro" style={{ color: 'var(--ok)' }}>
        ✓ We'll buzz you — you can close this page
      </span>
    );
  }
  if (smsSaved) {
    return <span className="micro" style={{ color: 'var(--ok)' }}>✓ We'll text you at {phone}</span>;
  }
  if (push.state === 'subscribing') return <span className="micro">Setting up…</span>;
  // Still finding out whether the server can push at all — show nothing
  // rather than a button that might immediately have to be withdrawn.
  if (push.state === 'unknown') return null;

  // 'disabled' = the SERVER has no VAPID keys, so Web Push cannot work here
  // however willing the browser is. Grouped with denied/unsupported because
  // the customer's next step is identical in all three: use SMS, or keep
  // the live page open.
  const pushUnavailable =
    push.state === 'denied' || push.state === 'unsupported' || push.state === 'disabled';

  return (
    <div className="stack" style={{ alignItems: 'center', width: '100%' }}>
      {push.state === 'available' && (
        <button className="btn-ghost btn-sm" onClick={push.enable}>
          🔔 Notify me when I'm next
        </button>
      )}
      {push.state === 'denied' && (
        <span className="micro">Browser notifications are blocked.</span>
      )}
      {push.state === 'disabled' && (
        <span className="micro" style={{ opacity: 0.75 }}>
          Push alerts aren't switched on for this server.
        </span>
      )}

      {pushUnavailable && !showSms && smsAvailable === true && (
        <button className="btn-ghost btn-sm" onClick={() => setShowSms(true)}>
          💬 Text me instead
        </button>
      )}
      {showSms && (
        <div className="row" style={{ width: '100%' }}>
          <input
            type="tel"
            value={phone}
            onChange={(e) => setPhone(e.target.value)}
            placeholder="+1 555 000 1234"
            aria-label="Mobile number for SMS alerts"
            maxLength={20}
            style={{ flex: 1 }}
          />
          <button className="btn-accent btn-sm" onClick={saveSms}>Save</button>
        </div>
      )}
      {pushUnavailable && !showSms && (
        <span className="micro">Keep this page open to see your position update.</span>
      )}
    </div>
  );
}
