/**
 * NS-9: the staff screen for one queue — the QR code customers scan, the live
 * line, and the "Next" button that drives NS-8's advance endpoint.
 *
 * The line polls every 10s AND refetches immediately after any staff action,
 * so the tablet never shows a customer that was just served.
 */
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import QRCode from 'react-qr-code';
import { errorMessage } from '../api/client';
import { advanceQueue, getQueue, getWaitingEntries, markNoShow } from '../api/endpoints';
import { BookingConfigPanel } from '../components/BookingConfigPanel';
import { HistoryPanel, PublishToggle, ReservationsToday, StatsBar, VenueConfigPanel } from '../components/OpsPanels';
import { setQueueStatus } from '../api/endpoints';
import { OwnerLayout } from '../components/OwnerLayout';
import { usePolling } from '../hooks/usePolling';
import { useLiveTopic } from '../realtime/useLiveTopic';

export function QueueDetailPage() {
  const { id } = useParams();
  const queueId = Number(id);

  const [refreshKey, setRefreshKey] = useState(0);
  const { data: queue, error: queueError } = usePolling(() => getQueue(queueId), 15_000, [queueId, refreshKey]);
  // Poll relaxed to 30s — the socket ping below triggers instant refetches.
  const { data: entries } = usePolling(() => getWaitingEntries(queueId), 30_000, [queueId, refreshKey]);

  // Sprint 2: the queue topic carries only a "LINE_CHANGED" ping (no data —
  // this topic is guessable, the JWT-protected REST call is the authority).
  // On ping: refetch the line. "Notify, then fetch" keeps auth in one place.
  const { live } = useLiveTopic(`/topic/queues/${queueId}`, () => setRefreshKey((k) => k + 1));

  const [banner, setBanner] = useState(''); // "Served Ana — next up: Ben"
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const refresh = () => setRefreshKey((k) => k + 1);

  async function onNext() {
    setBusy(true);
    setError('');
    try {
      const result = await advanceQueue(queueId);
      setBanner(
        `Served ${result.served.customerName}` +
          (result.nextUp ? ` — next up: ${result.nextUp.customerName}` : ' — the line is now empty'),
      );
      refresh();
    } catch (err) {
      setError(errorMessage(err)); // e.g. 400 "No one is waiting in this queue"
    } finally {
      setBusy(false);
    }
  }

  async function onNoShow(entryId: number) {
    setError('');
    try {
      await markNoShow(entryId);
      refresh();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  if (queueError != null) {
    return (
      <OwnerLayout>
        <div className="error">{errorMessage(queueError)}</div>
      </OwnerLayout>
    );
  }

  return (
    <OwnerLayout>
      <div className="row spread" style={{ marginBottom: 16 }}>
        <div>
          <Link to="/owner" className="micro">← All queues</Link>
          <h1 className="row" style={{ gap: 10 }}>
            {queue?.name ?? '…'}
            {queue && (
              <span className={queue.status === 'OPEN' ? 'badge open' : 'badge closed'}>
                {queue.status}
              </span>
            )}
          </h1>
          {/* Visibility is a SEPARATE axis from open/closed: a queue can be
              open for walk-ins but still hidden from public search while an
              owner tests it. See PublishToggle's comment for why this is its
              own button rather than a checkbox buried in venue settings. */}
          <PublishToggle queueId={queueId} />
        </div>
        <div className="row">
          {/* P0: open/close without touching the database. Explicit PATCH,
              audited server-side (who, when, why). */}
          {queue && (
            <button
              className={queue.status === 'OPEN' ? 'btn-danger btn-sm' : 'btn-ghost btn-sm'}
              disabled={busy}
              onClick={async () => {
                setBusy(true);
                setError('');
                try {
                  await setQueueStatus(queueId, queue.status === 'OPEN' ? 'CLOSED' : 'OPEN');
                  setRefreshKey((k) => k + 1);
                } catch (err) {
                  setError(errorMessage(err));
                } finally {
                  setBusy(false);
                }
              }}
            >
              {queue.status === 'OPEN' ? 'Close queue' : 'Reopen queue'}
            </button>
          )}
          <button className="btn-accent" onClick={onNext} disabled={busy}>
            {busy ? 'Serving…' : 'Next ▸'}
          </button>
        </div>
      </div>

      {banner && <div className="panel micro" style={{ marginBottom: 16 }}>{banner}</div>}
      {error && <div className="error" style={{ marginBottom: 16 }}>{error}</div>}

      <StatsBar queueId={queueId} refreshKey={refreshKey} />

      <div className="grid cols-2">
        <section className="panel stack">
          <span className="micro">
            The line — {entries?.length ?? 0} waiting
            {live && <span style={{ color: 'var(--ok)' }}> · ● live</span>}
          </span>
          {entries === null && <span className="spin" />}
          {entries?.length === 0 && <p style={{ color: 'var(--muted)' }}>Nobody waiting right now.</p>}
          {entries && entries.length > 0 && (
            <table>
              <thead>
                <tr>
                  <th className="micro">#</th>
                  <th className="micro">Name</th>
                  <th className="micro">Party</th>
                  <th className="micro">Waited</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {entries.map((e) => (
                  <tr key={e.entryId}>
                    <td className={e.position === 1 ? 'glow' : ''} style={{ fontWeight: 700 }}>
                      {e.position}
                    </td>
                    <td>
                      {e.customerName}
                      {/* FR-15's staff half. The backend has sent `enRoute`
                          since Sprint 5 and nothing rendered it, so a
                          customer could tap "I'm on my way" and staff would
                          never know — the button existed but the feature
                          didn't. Deliberately no ETA and no position: staff
                          see THAT someone is travelling, never where they
                          are (§3.8.8). */}
                      {e.enRoute && (
                        <span className="badge" style={{ marginLeft: 8, color: 'var(--ok)' }}>
                          en route
                        </span>
                      )}
                      {e.graceExpiresAt && (
                        <span className="badge" style={{ marginLeft: 8, color: 'var(--accent)' }}>
                          held till{' '}
                          {new Date(e.graceExpiresAt).toLocaleTimeString([], {
                            hour: '2-digit',
                            minute: '2-digit',
                          })}
                        </span>
                      )}
                    </td>
                    <td>{e.partySize}</td>
                    <td className="micro">{e.waitedMinutes} min</td>
                    <td style={{ textAlign: 'right' }}>
                      <button className="btn-danger btn-sm" onClick={() => onNoShow(e.entryId)}>
                        No-show
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </section>

        <section className="panel stack" style={{ alignItems: 'center', textAlign: 'center' }}>
          <span className="micro">Customers scan to join</span>
          {queue && (
            <>
              {/* The QR encodes queue.joinUrl — the /j/{joinToken} page. */}
              <div className="qr-wrap">
                <QRCode value={queue.joinUrl} size={196} />
              </div>
              <a href={queue.joinUrl} target="_blank" rel="noreferrer" className="micro">
                {queue.joinUrl}
              </a>
              <button
                className="btn-ghost btn-sm"
                onClick={() => navigator.clipboard.writeText(queue.joinUrl)}
              >
                Copy link
              </button>
            </>
          )}
        </section>
      </div>

      {/* FR-10: reservations config + the shareable booking link */}
      <BookingConfigPanel queueId={queueId} joinToken={queue?.joinToken} />
      <VenueConfigPanel queueId={queueId} />
      <ReservationsToday queueId={queueId} />
      <HistoryPanel queueId={queueId} refreshKey={refreshKey} />
    </OwnerLayout>
  );
}
