/**
 * P1 — the panels that turn the owner dashboard from a demo control panel
 * into something Priya can run her whole day on: live stats, venue location
 * for Leave-Now, today's bookings, and history. Each is small and
 * single-purpose; QueueDetailPage just composes them.
 */
import { useEffect, useState, type FormEvent } from 'react';
import { errorMessage } from '../api/client';
import {
  searchAddress,
  getHistory,
  getQueueStats,
  getReservationsForDay,
  getVenueConfig,
  saveVenueConfig,
} from '../api/endpoints';
import type { GeocodeResult, HistoryEntry, QueueStats, VenueConfig } from '../api/types';
import { usePolling } from '../hooks/usePolling';

const fmtTime = (iso: string) =>
  new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

/**
 * The ONE control for "can strangers find this restaurant" — deliberately
 * separate from the rest of venue config (grace periods, radius, TomTom
 * search) and from its own Save button. A newly created queue defaults
 * HIDDEN, precisely so a restaurant an owner is still testing can't reach
 * the public discovery page by accident; publishing has to be a decision
 * this button makes visible, not a checkbox that could go unnoticed among a
 * dozen other settings and get saved along with them.
 *
 * Reuses the existing venue-config read/write endpoints — no new backend
 * surface, just a one-field PATCH-shaped write that takes effect immediately
 * rather than waiting on the form below's Save.
 */
export function PublishToggle({ queueId }: { queueId: number }) {
  const [listed, setListed] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    getVenueConfig(queueId).then((c) => setListed(c.listedPublicly)).catch(() => setListed(null));
  }, [queueId]);

  async function toggle() {
    if (listed === null) return;
    setBusy(true);
    setError('');
    try {
      const next = !listed;
      await saveVenueConfig(queueId, { listedPublicly: next });
      setListed(next);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (listed === null) return null;

  return (
    <div className="row" style={{ gap: 8, alignItems: 'center' }}>
      <span className={listed ? 'badge open' : 'badge closed'}>
        {listed ? 'Published' : 'Hidden'}
      </span>
      <button
        className={listed ? 'btn-ghost btn-sm' : 'btn-primary btn-sm'}
        disabled={busy}
        onClick={toggle}
        title={
          listed
            ? 'Customers can currently find this restaurant in public search'
            : 'Only people with the direct link or QR code can find this restaurant'
        }
      >
        {busy ? '…' : listed ? 'Hide restaurant' : 'Publish restaurant'}
      </button>
      {error && <span className="error micro">{error}</span>}
    </div>
  );
}

/** The at-a-glance numbers that make the page feel operational. */
export function StatsBar({ queueId, refreshKey }: { queueId: number; refreshKey: number }) {
  const { data } = usePolling<QueueStats>(() => getQueueStats(queueId), 15_000, [queueId, refreshKey]);
  if (!data) return null;

  const stat = (label: string, value: string | number) => (
    <div style={{ textAlign: 'center', minWidth: 90 }}>
      <div className="glow" style={{ fontSize: '1.6rem', fontWeight: 800 }}>{value}</div>
      <span className="micro">{label}</span>
    </div>
  );

  return (
    <section className="panel row" style={{ justifyContent: 'space-around', flexWrap: 'wrap', gap: 12, marginBottom: 16 }}>
      {stat('waiting', data.waitingCount)}
      {stat('served today', data.servedToday)}
      {stat('median wait', data.medianWaitMinutesToday != null ? `${data.medianWaitMinutesToday}m` : '—')}
      {stat('pace', data.paceMinutesPerCustomer != null ? `${data.paceMinutesPerCustomer}m/cust` : '—')}
      {stat('no-shows', data.noShowsToday)}
      {stat('longest wait', data.longestCurrentWaitMinutes != null ? `${data.longestCurrentWaitMinutes}m` : '—')}
      {stat('bookings today', data.reservationsToday)}
    </section>
  );
}

/** Sprint 5's missing half: owners could never set coordinates without curl. */
export function VenueConfigPanel({ queueId }: { queueId: number }) {
  const [config, setConfig] = useState<VenueConfig | null>(null);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);
  const [locating, setLocating] = useState(false);
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<GeocodeResult[] | null>(null);
  const [searching, setSearching] = useState(false);

  /** TomTom fuzzy search — the "never type coordinates" path. Empty results
   *  when TomTom isn't configured, so the manual fields stay the fallback. */
  async function onSearch() {
    if (!query.trim()) return;
    setSearching(true);
    setError('');
    try {
      const found = await searchAddress(query);
      setResults(found);
      if (found.length === 0) {
        setError('No matches (or address search is not configured) — use the fields below.');
      }
    } catch {
      setError('Search failed — use the fields below.');
    } finally {
      setSearching(false);
    }
  }

  useEffect(() => {
    getVenueConfig(queueId).then(setConfig).catch(() => setConfig(null));
  }, [queueId]);

  if (!config) return null;

  function update<K extends keyof VenueConfig>(key: K, value: VenueConfig[K]) {
    setConfig((c) => (c ? { ...c, [key]: value } : c));
    setSaved(false);
  }

  /** The easiest correct input: the owner stands in the shop and taps once. */
  function useMyLocation() {
    if (!('geolocation' in navigator)) return;
    setLocating(true);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        update('venueLatitude', Number(pos.coords.latitude.toFixed(6)));
        update('venueLongitude', Number(pos.coords.longitude.toFixed(6)));
        setLocating(false);
      },
      () => {
        setError('Could not read your location — enter coordinates manually.');
        setLocating(false);
      },
      { timeout: 10_000 },
    );
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    if (!config) return;
    // Client-side sanity before the server's own validation.
    if (config.venueLatitude != null && Math.abs(config.venueLatitude) > 90) {
      setError('Latitude must be between -90 and 90.');
      return;
    }
    if (config.venueLongitude != null && Math.abs(config.venueLongitude) > 180) {
      setError('Longitude must be between -180 and 180.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      setConfig(await saveVenueConfig(queueId, config));
      setSaved(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  const enabled = config.venueLatitude != null && config.venueLongitude != null;

  return (
    <section className="panel stack" style={{ marginTop: 16 }}>
      <div className="row spread">
        <span className="micro">Location & "leave now" alerts</span>
        <span className="badge" style={{ color: enabled ? 'var(--ok)' : 'var(--muted)' }}>
          {enabled ? 'active' : 'not set'}
        </span>
      </div>
      <span className="micro" style={{ opacity: 0.7 }}>
        With your shop's location set, customers who share theirs get told exactly when to head over.
      </span>

      <form className="stack" onSubmit={onSave}>
        <label>
          <span className="micro">Search your address</span>
          <div className="row">
            <input value={query} onChange={(e) => setQuery(e.target.value)}
              placeholder="e.g. 221B Baker Street, London"
              onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); onSearch(); } }} />
            <button type="button" className="btn-ghost btn-sm" onClick={onSearch} disabled={searching}>
              {searching ? '…' : 'Search'}
            </button>
          </div>
        </label>
        {results && results.length > 0 && (
          <div className="stack">
            {results.map((r, i) => (
              <button key={i} type="button" className="btn-ghost btn-sm"
                style={{ justifyContent: 'flex-start', textAlign: 'left' }}
                onClick={() => {
                  update('venueLatitude', r.latitude);
                  update('venueLongitude', r.longitude);
                  setResults(null);
                  setQuery(r.label);
                }}>
                📍 {r.label}
              </button>
            ))}
          </div>
        )}

        <div className="row">
          <label style={{ flex: 1 }}>
            <span className="micro">Latitude</span>
            <input type="number" step="any" value={config.venueLatitude ?? ''}
              onChange={(e) => update('venueLatitude', e.target.value === '' ? null : Number(e.target.value))} />
          </label>
          <label style={{ flex: 1 }}>
            <span className="micro">Longitude</span>
            <input type="number" step="any" value={config.venueLongitude ?? ''}
              onChange={(e) => update('venueLongitude', e.target.value === '' ? null : Number(e.target.value))} />
          </label>
        </div>
        <button type="button" className="btn-ghost btn-sm" onClick={useMyLocation} disabled={locating}>
          {locating ? 'Locating…' : "📍 Use this device's location"}
        </button>

        <div className="row">
          <label style={{ flex: 1 }}>
            <span className="micro">Grace (min)</span>
            <input type="number" min={0} value={config.graceMinutes}
              onChange={(e) => update('graceMinutes', Number(e.target.value))} />
          </label>
          <label style={{ flex: 1 }}>
            <span className="micro">Bump back (0 = no-show)</span>
            <input type="number" min={0} value={config.bumpPlaces}
              onChange={(e) => update('bumpPlaces', Number(e.target.value))} />
          </label>
          <label style={{ flex: 1 }}>
            <span className="micro">Safety buffer (min)</span>
            <input type="number" min={0} value={config.safetyBufferMinutes}
              onChange={(e) => update('safetyBufferMinutes', Number(e.target.value))} />
          </label>
        </div>

        {/* ── V9: who is allowed through which door ──────────────────── */}
        <hr style={{ width: '100%', border: 0, borderTop: '1px solid var(--border)', margin: '4px 0' }} />
        <span className="micro">Remote joining</span>
        <span className="micro" style={{ opacity: 0.7 }}>
          Whether strangers can FIND this restaurant is the "Publish restaurant" button
          above. These settings control what happens once they do.
        </span>

        <label className="row toggle-row">
          <input type="checkbox" checked={config.allowRemoteJoin}
            onChange={(e) => update('allowRemoteJoin', e.target.checked)} />
          <span>Allow customers to join remotely</span>
        </label>

        {/* The point of the setting: a 20-minute line shouldn't be held by
            someone 45 minutes away. The ceiling comes from the server so the
            UI never hard-codes it. */}
        {config.allowRemoteJoin && (
          <label>
            <span className="micro">
              Maximum distance — up to {config.maxRemoteJoinMilesCeiling} miles
            </span>
            <div className="row" style={{ alignItems: 'center', gap: 12 }}>
              <input type="range" min={1} max={config.maxRemoteJoinMilesCeiling} step={1}
                value={config.maxRemoteJoinMiles}
                onChange={(e) => update('maxRemoteJoinMiles', Number(e.target.value))}
                style={{ flex: 1 }} />
              <strong style={{ minWidth: 70, textAlign: 'right' }}>
                {config.maxRemoteJoinMiles} miles
              </strong>
            </div>
          </label>
        )}

        <label className="row toggle-row">
          <input type="checkbox" checked={config.allowQrJoin}
            onChange={(e) => update('allowQrJoin', e.target.checked)} />
          <span>Allow QR joining</span>
        </label>
        <span className="micro" style={{ opacity: 0.7 }}>
          Scanning the code at your door always skips the distance check — the person is already here.
        </span>

        {error && <div className="error">{error}</div>}
        <div className="row">
          <button className="btn-primary btn-sm" disabled={busy}>{busy ? 'Saving…' : 'Save'}</button>
          {saved && <span className="micro" style={{ color: 'var(--ok)' }}>✓ Saved</span>}
        </div>
      </form>
    </section>
  );
}

/** Today's bookings, live. Read-only: customers check themselves in via their link. */
export function ReservationsToday({ queueId }: { queueId: number }) {
  const today = new Date().toISOString().slice(0, 10);
  const { data, error } = usePolling(() => getReservationsForDay(queueId, today), 30_000, [queueId]);

  return (
    <section className="panel stack" style={{ marginTop: 16 }}>
      <span className="micro">Today's reservations</span>
      {error != null && <div className="error">{errorMessage(error)}</div>}
      {data?.length === 0 && (
        <p style={{ color: 'var(--muted)' }}>
          No reservations today. New bookings will appear here automatically.
        </p>
      )}
      {data && data.length > 0 && (
        <table>
          <thead><tr>
            <th className="micro">Time</th><th className="micro">Name</th>
            <th className="micro">Party</th><th className="micro">Status</th>
          </tr></thead>
          <tbody>
            {data.map((r) => (
              <tr key={r.id}>
                <td>{fmtTime(r.slotStart)}</td>
                <td>{r.customerName}</td>
                <td>{r.partySize}</td>
                <td><span className="badge">{r.status}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** Served / no-show / left, per day — the "did I serve 40 or 12 today?" answer. */
export function HistoryPanel({ queueId, refreshKey }: { queueId: number; refreshKey: number }) {
  const [status, setStatus] = useState<'SERVED' | 'NO_SHOW' | 'LEFT'>('SERVED');
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const { data, error } = usePolling(
    () => getHistory(queueId, status, date), 30_000, [queueId, status, date, refreshKey]);

  return (
    <section className="panel stack" style={{ marginTop: 16 }}>
      <div className="row spread" style={{ flexWrap: 'wrap', gap: 8 }}>
        <span className="micro">History</span>
        <div className="row" style={{ gap: 6 }}>
          {(['SERVED', 'NO_SHOW', 'LEFT'] as const).map((s) => (
            <button key={s} className={status === s ? 'btn-accent btn-sm' : 'btn-ghost btn-sm'}
              onClick={() => setStatus(s)}>
              {s.replace('_', '-').toLowerCase()}
            </button>
          ))}
          <input type="date" value={date} style={{ width: 'auto' }}
            onChange={(e) => setDate(e.target.value)} />
        </div>
      </div>

      {error != null && <div className="error">{errorMessage(error)}</div>}
      {data?.length === 0 && (
        <p style={{ color: 'var(--muted)' }}>Nothing {status.toLowerCase().replace('_', '-')} on this day.</p>
      )}
      {data && data.length > 0 && (
        <table>
          <thead><tr>
            <th className="micro">Name</th><th className="micro">Joined</th>
            <th className="micro">Completed</th><th className="micro">Waited</th>
            <th className="micro">Rating</th>
          </tr></thead>
          <tbody>
            {data.map((e: HistoryEntry) => (
              <tr key={e.entryId}>
                <td>{e.customerName}</td>
                <td className="micro">{fmtTime(e.joinedAt)}</td>
                <td className="micro">{e.servedAt ? fmtTime(e.servedAt) : '—'}</td>
                <td>{e.waitedMinutes != null ? `${e.waitedMinutes}m` : '—'}</td>
                <td className="micro" title={e.feedbackComment ?? ''}>
                  {e.ratingStars != null ? `${'★'.repeat(e.ratingStars)} ${e.ratingStars}/5` : '—'}
                  {e.feedbackComment && (
                    <div style={{ opacity: 0.8, maxWidth: 220, whiteSpace: 'normal' }}>
                      “{e.feedbackComment}”
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
