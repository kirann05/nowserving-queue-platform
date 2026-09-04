/**
 * FR-10 — the owner switches on reservations and sets their hours.
 *
 * Kept as its own component rather than more lines inside QueueDetailPage:
 * the page was already doing three jobs (line, QR, Next button), and a
 * component that only knows about booking config is far easier to reason
 * about — the frontend version of "one class, one responsibility".
 */
import { useEffect, useState, type FormEvent } from 'react';
import { errorMessage } from '../api/client';
import { getBookingConfig, saveBookingConfig } from '../api/endpoints';
import type { BookingConfig } from '../api/types';

interface Props {
  queueId: number;
  joinToken: string | undefined;
}

export function BookingConfigPanel({ queueId, joinToken }: Props) {
  const [config, setConfig] = useState<BookingConfig | null>(null);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getBookingConfig(queueId).then(setConfig).catch(() => setConfig(null));
  }, [queueId]);

  function update<K extends keyof BookingConfig>(key: K, value: BookingConfig[K]) {
    if (config) setConfig({ ...config, [key]: value });
    setSaved(false);
  }

  async function onSave(e: FormEvent) {
    e.preventDefault();
    if (!config) return;
    setBusy(true);
    setError('');
    try {
      setConfig(await saveBookingConfig(queueId, config));
      setSaved(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  if (!config) return null;

  const bookingUrl = joinToken ? `${window.location.origin}/b/${joinToken}` : '';

  return (
    <section className="panel stack" style={{ marginTop: 16 }}>
      <span className="micro">Reservations</span>

      <form className="stack" onSubmit={onSave}>
        <label className="row" style={{ gap: 8 }}>
          <input
            type="checkbox"
            style={{ width: 'auto' }}
            checked={config.reservationsEnabled}
            onChange={(e) => update('reservationsEnabled', e.target.checked)}
          />
          <span>Let customers book ahead</span>
        </label>

        {config.reservationsEnabled && (
          <>
            <div className="row">
              <label style={{ flex: 1 }}>
                <span className="micro">Opens</span>
                <input
                  type="time"
                  value={config.openingTime ?? '09:00'}
                  onChange={(e) => update('openingTime', e.target.value)}
                />
              </label>
              <label style={{ flex: 1 }}>
                <span className="micro">Closes</span>
                <input
                  type="time"
                  value={config.closingTime ?? '17:00'}
                  onChange={(e) => update('closingTime', e.target.value)}
                />
              </label>
            </div>
            <div className="row">
              <label style={{ flex: 1 }}>
                <span className="micro">Slot length (min)</span>
                <input
                  type="number"
                  min={5}
                  value={config.slotMinutes}
                  onChange={(e) => update('slotMinutes', Number(e.target.value))}
                />
              </label>
              <label style={{ flex: 1 }}>
                <span className="micro">People per slot</span>
                <input
                  type="number"
                  min={1}
                  value={config.slotCapacity}
                  onChange={(e) => update('slotCapacity', Number(e.target.value))}
                />
              </label>
            </div>
            <label>
              {/* An IANA zone id, not an offset: offsets don't know about
                  daylight saving, so "we open at 9" would drift twice a year. */}
              <span className="micro">Time zone (IANA, e.g. America/Chicago)</span>
              <input
                value={config.timeZone}
                onChange={(e) => update('timeZone', e.target.value)}
                placeholder={Intl.DateTimeFormat().resolvedOptions().timeZone}
              />
            </label>
          </>
        )}

        {error && <div className="error">{error}</div>}
        <div className="row">
          <button className="btn-primary btn-sm" disabled={busy}>
            {busy ? 'Saving…' : 'Save'}
          </button>
          {saved && <span className="micro" style={{ color: 'var(--ok)' }}>✓ Saved</span>}
        </div>
      </form>

      {config.reservationsEnabled && bookingUrl && (
        <div className="stack">
          <span className="micro">Share this booking link</span>
          <a href={bookingUrl} target="_blank" rel="noreferrer" className="micro">
            {bookingUrl}
          </a>
        </div>
      )}
    </section>
  );
}
