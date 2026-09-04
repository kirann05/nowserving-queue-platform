/**
 * NS-9: the owner dashboard — every queue at a glance + create a new one.
 * The list polls every 10s so waiting counts stay roughly fresh (Sprint 2
 * makes this instant via WebSockets).
 */
import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { createQueue, listQueues } from '../api/endpoints';
import { OwnerLayout } from '../components/OwnerLayout';
import { usePolling } from '../hooks/usePolling';
import type { QueueResponse } from '../api/types';

export function DashboardPage() {
  const [refreshKey, setRefreshKey] = useState(0); // bump to refetch after create
  const { data: queues, error: loadError } = usePolling(listQueues, 10_000, [refreshKey]);

  const [name, setName] = useState('');
  const [stations, setStations] = useState('1');
  const [minutes, setMinutes] = useState('15');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function onCreate(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await createQueue(name, Number(stations), Number(minutes));
      setName('');
      setRefreshKey((k) => k + 1);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  // Closed AND unlisted = retired. Either one alone is a normal operating
  // state (shut for the night; open but not advertised), so both are needed
  // before a line drops out of the main list.
  const isArchived = (q: QueueResponse) => q.status === 'CLOSED' && !q.listedPublicly;
  const active = queues?.filter((q) => !isArchived(q));
  const archived = queues?.filter(isArchived);

  return (
    <OwnerLayout>
      <div className="grid cols-2">
        <section className="panel stack">
          <span className="micro">Your queues</span>

          {loadError != null && <div className="error">{errorMessage(loadError)}</div>}
          {queues === null && <span className="spin" />}
          {active?.length === 0 && archived?.length === 0 && (
            <p style={{ color: 'var(--muted)' }}>No queues yet — create your first one.</p>
          )}

          {active?.map((q) => (
            <Link
              key={q.id}
              to={`/owner/queues/${q.id}`}
              className="panel row spread"
              style={{ textDecoration: 'none', color: 'inherit', background: 'var(--panel-2)' }}
            >
              <div style={{ minWidth: 0 }}>
                <strong>{q.name}</strong>
                <div className="micro">
                  {q.stationCount} station{q.stationCount > 1 ? 's' : ''} · ~{q.defaultServiceMinutes} min each
                </div>
                {/* WHO is in this line, not just how many. On a business
                    running several similar queues, a bare count means an
                    owner hunting for one customer has to open each queue in
                    turn — which is how someone appears to "disappear" when
                    they are really standing in a line nobody opened. */}
                {q.waitingNames.length > 0 && (
                  <div className="queue-card-names">
                    {q.waitingNames.join(', ')}
                    {q.waitingCount > q.waitingNames.length &&
                      ` +${q.waitingCount - q.waitingNames.length} more`}
                  </div>
                )}
              </div>
              <div className="row">
                <span className="badge open">{q.status}</span>
                <span className="glow" style={{ fontWeight: 800, fontSize: '1.4rem' }}>
                  {q.waitingCount}
                </span>
                <span className="micro">waiting</span>
              </div>
            </Link>
          ))}

          {/* Retired lines, folded away.
              ARCHIVED = closed AND unlisted — deliberately both. A queue
              that's merely shut for the night is still listed, and must stay
              in the main list where its owner can reopen it. These are still
              one click away and reopening one returns it above; nothing is
              hidden that cannot be got back. */}
          {archived && archived.length > 0 && (
            <details className="archived-queues">
              <summary className="micro">Archived ({archived.length})</summary>
              <div className="stack" style={{ marginTop: 10 }}>
                {archived.map((q) => (
                  <Link
                    key={q.id}
                    to={`/owner/queues/${q.id}`}
                    className="panel row spread archived-queue"
                    style={{ textDecoration: 'none', color: 'inherit' }}
                  >
                    <div style={{ minWidth: 0 }}>
                      <strong>{q.name}</strong>
                      <div className="micro">archived</div>
                    </div>
                    <span className="badge">closed</span>
                  </Link>
                ))}
              </div>
            </details>
          )}
        </section>

        <section className="panel stack">
          <span className="micro">New queue</span>
          <form className="stack" onSubmit={onCreate}>
            <label>
              <span className="micro">Name</span>
              <input value={name} onChange={(e) => setName(e.target.value)} required placeholder="Walk-ins" />
            </label>
            <div className="row">
              <label style={{ flex: 1 }}>
                <span className="micro">Stations</span>
                <input type="number" min={1} value={stations} onChange={(e) => setStations(e.target.value)} />
              </label>
              <label style={{ flex: 1 }}>
                <span className="micro">Minutes / customer</span>
                <input type="number" min={1} value={minutes} onChange={(e) => setMinutes(e.target.value)} />
              </label>
            </div>
            {error && <div className="error">{error}</div>}
            <button className="btn-primary" disabled={busy}>
              {busy ? 'Creating…' : 'Create queue'}
            </button>
          </form>
        </section>
      </div>
    </OwnerLayout>
  );
}
