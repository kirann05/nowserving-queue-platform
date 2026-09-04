/**
 * FR-10 — the customer's booking confirmation, at /r/{reservationToken}.
 *
 * Shows the booked time, lets them check in on arrival (which converts the
 * booking into a live place in the line) or cancel.
 */
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { cancelReservation, checkInReservation, getReservation } from '../api/endpoints';
import { usePolling } from '../hooks/usePolling';

export function ReservationPage() {
  const { reservationToken } = useParams();
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const { data, error: loadError } = usePolling(
    () => getReservation(reservationToken!),
    60_000, // a booking barely changes; no need to poll hard
    [reservationToken],
  );

  async function onCheckIn() {
    setBusy(true);
    setError('');
    try {
      const entry = await checkInReservation(reservationToken!);
      // From here on they're an ordinary queue entry — same ticket page a
      // walk-in customer sees. The two front doors have merged.
      navigate(`/t/${entry.entryToken}`, { replace: true });
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function onCancel() {
    if (!confirm('Cancel this reservation?')) return;
    setBusy(true);
    try {
      await cancelReservation(reservationToken!);
      setError('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (loadError != null) {
    return (
      <div className="center-page">
        <div className="panel stack">
          <div className="error">{errorMessage(loadError)}</div>
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

  const when = new Date(data.slotStart);

  return (
    <div className="center-page">
      <div className="panel stack" style={{ textAlign: 'center', alignItems: 'center' }}>
        <span className="micro">your reservation</span>
        {/* Name the restaurant. This screen is opened hours later, often
            from a bookmark, by someone who compared several venues before
            booking — "Dinner Service" alone doesn't say where to go. */}
        <h1 style={{ margin: 0, fontSize: '1.4rem' }}>{data.businessName}</h1>

        {data.status === 'BOOKED' && (
          <>
            <div className="glow" style={{ fontSize: 'clamp(3rem, 18vw, 5rem)', fontWeight: 800 }}>
              {when.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </div>
            <p style={{ color: 'var(--muted)' }}>
              {when.toLocaleDateString([], { weekday: 'long', day: 'numeric', month: 'long' })} ·{' '}
              {data.queueName}
            </p>
            <p className="micro">
              {data.customerName} · party of {data.partySize}
            </p>
            {error && <div className="error">{error}</div>}
            <button className="btn-accent" onClick={onCheckIn} disabled={busy}>
              I'm here — check me in
            </button>
            <button className="btn-ghost btn-sm" onClick={onCancel} disabled={busy}>
              Cancel reservation
            </button>
          </>
        )}

        {data.status === 'REDEEMED' && (
          <>
            <h2 className="glow">Checked in</h2>
            <p style={{ color: 'var(--muted)' }}>You're in the line now.</p>
          </>
        )}

        {data.status === 'CANCELLED' && (
          <>
            <h2>Cancelled</h2>
            <p style={{ color: 'var(--muted)' }}>This reservation was cancelled.</p>
          </>
        )}
      </div>
    </div>
  );
}
