/**
 * FR-10 — the customer's booking page, at /b/{joinToken}.
 *
 * Pick a date, pick a free slot, book. Mobile-first like the join page.
 */
import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { bookSlot, getAvailability, getVenue } from '../api/endpoints';
import type { VenueDetail } from '../api/types';
import { usePolling } from '../hooks/usePolling';

/** Today in the BROWSER's zone, as YYYY-MM-DD for the date input. */
function todayIso(): string {
  const now = new Date();
  const offsetMs = now.getTimezoneOffset() * 60_000;
  return new Date(now.getTime() - offsetMs).toISOString().slice(0, 10);
}

export function BookPage() {
  const { joinToken } = useParams();
  const navigate = useNavigate();
  const [date, setDate] = useState(todayIso());
  const [name, setName] = useState('');
  const [partySize, setPartySize] = useState('1');
  const [selected, setSelected] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  // Availability refreshes on a slow poll: someone else may take a seat while
  // this page is open, and a slot that silently 409s on submit is a bad
  // experience.
  const { data, error: loadError } = usePolling(
    () => getAvailability(joinToken!, date),
    20_000,
    [joinToken, date],
  );

  /**
   * One idempotency key per booking ATTEMPT, generated when the form is
   * first rendered for a chosen slot. If the customer double-taps or the
   * response is lost and they retry, the server recognises the key and
   * returns the SAME reservation rather than eating another seat.
   */
  const idempotencyKey = useMemo(
    () => `${crypto.randomUUID()}`,
    // A new key only when they pick a different slot — a retry of the *same*
    // booking must reuse it, which is the whole point.
    [selected],
  );

  async function onBook() {
    if (!selected) return;
    setBusy(true);
    setError('');
    try {
      const reservation = await bookSlot(
        joinToken!,
        { customerName: name, partySize: Number(partySize), slotStart: selected },
        idempotencyKey,
      );
      localStorage.setItem(`ns_reservation_${joinToken}`, reservation.reservationToken);
      navigate(`/r/${reservation.reservationToken}`, { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const slots = data?.slots ?? [];
  // Same reasoning as the join form: a booking screen must name the place
  // being booked. "NOWSERVING" alone told the customer nothing about which
  // restaurant they were reserving at.
  const { data: venue } = usePolling<VenueDetail>(() => getVenue(joinToken!), 60_000, [joinToken]);

  return (
    <div className="center-page">
      <div className="panel stack" style={{ maxWidth: 520 }}>
        <div className="stack" style={{ gap: 4 }}>
          <span className="micro">Book ahead</span>
          {venue ? (
            <h1 style={{ margin: 0, fontSize: '1.6rem' }}>{venue.venue.businessName}</h1>
          ) : (
            <div className="skeleton-line" style={{ width: '60%', height: 26 }} aria-hidden="true" />
          )}
          <p style={{ color: 'var(--muted)', margin: 0 }}>
            Reserve a time instead of waiting in line.
          </p>
        </div>

        <label>
          <span className="micro">Date</span>
          <input type="date" value={date} min={todayIso()} onChange={(e) => setDate(e.target.value)} />
        </label>

        {loadError != null && <div className="error">{errorMessage(loadError)}</div>}
        {data === null && <span className="spin" />}
        {data !== null && slots.length === 0 && (
          <p style={{ color: 'var(--muted)' }}>No slots on this date.</p>
        )}

        {slots.length > 0 && (
          <>
            <span className="micro">Pick a time</span>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
              {slots.map((slot) => {
                const time = new Date(slot.startTime).toLocaleTimeString([], {
                  hour: '2-digit',
                  minute: '2-digit',
                });
                const isSelected = selected === slot.startTime;
                return (
                  <button
                    key={slot.startTime}
                    className={isSelected ? 'btn-accent btn-sm' : 'btn-ghost btn-sm'}
                    disabled={!slot.available}
                    onClick={() => setSelected(slot.startTime)}
                    /* The server marks a slot unavailable for TWO different
                       reasons — it already started, or it filled up. Calling
                       every disabled slot "Fully booked" told lunchtime
                       browsers a restaurant was slammed when really the time
                       had simply passed. capacity/booked distinguishes them. */
                    title={
                      slot.available
                        ? `${slot.capacity - slot.booked} left`
                        : slot.booked >= slot.capacity
                          ? 'Fully booked'
                          : 'This time has passed'
                    }
                  >
                    {time}
                  </button>
                );
              })}
            </div>
          </>
        )}

        {selected && (
          <>
            <label>
              <span className="micro">Your name</span>
              <input value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} />
            </label>
            <label>
              <span className="micro">Party size</span>
              <input
                type="number"
                min={1}
                max={20}
                value={partySize}
                onChange={(e) => setPartySize(e.target.value)}
              />
            </label>
            {error && <div className="error">{error}</div>}
            <button className="btn-accent" onClick={onBook} disabled={busy || !name.trim()}>
              {busy ? 'Booking…' : `Book ${new Date(selected).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`}
            </button>
          </>
        )}
      </div>
    </div>
  );
}
