/**
 * /owner/settings — rename the restaurant, or delete it entirely.
 *
 * Two sections with deliberately different temperatures. Renaming is a normal
 * edit and behaves like one. Deleting is irreversible and destroys other
 * people's bookings as well as the owner's own data, so it is walled off in a
 * danger zone that cannot be triggered by one stray click: the destructive
 * button doesn't exist until the owner has opened the section AND typed their
 * restaurant's name from memory. Typing the name (rather than "DELETE") is
 * the check that fails when someone is deleting the wrong account.
 */
import { useEffect, useRef, useState, type FormEvent } from 'react';
import QRCode from 'react-qr-code';
import { beginIntentionalSignOut, errorMessage } from '../api/client';
import { deleteBusiness, renameBusiness } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { OwnerLayout } from '../components/OwnerLayout';

export function SettingsPage() {
  const { me, refreshMe, logout } = useAuth();

  const [name, setName] = useState('');
  const [savingName, setSavingName] = useState(false);
  const [saved, setSaved] = useState(false);
  const [nameError, setNameError] = useState('');

  const [copied, setCopied] = useState(false);
  const qrRef = useRef<HTMLDivElement>(null);

  const [dangerOpen, setDangerOpen] = useState(false);
  const [confirmText, setConfirmText] = useState('');
  const [deleting, setDeleting] = useState(false);
  const [deleteError, setDeleteError] = useState('');

  useEffect(() => {
    document.title = 'Settings — NowServing';
  }, []);

  // Seed the field once `me` arrives. A Google signup lands here with a
  // placeholder name like "Priya's Business", which is exactly the case this
  // page exists to fix, so the field is editable from the first render.
  useEffect(() => {
    if (me) setName(me.business.name);
  }, [me?.business.name]);

  const currentName = me?.business.name ?? '';
  const trimmed = name.trim();
  const nameChanged = trimmed !== currentName;

  // The permanent restaurant link. Built from the browser's own origin so it
  // is correct in dev, on a LAN IP a phone can actually reach, and in prod —
  // without a hard-coded base URL to get wrong.
  const qrUrl = `${window.location.origin}/qr/${me?.business.publicToken ?? ''}`;

  async function copyLink() {
    try {
      await navigator.clipboard.writeText(qrUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard blocked (insecure origin / permissions) — the link is
         visible and selectable above, so this is a convenience, not the
         only way to get it */
    }
  }

  /**
   * Rasterise the inline <svg> to a PNG. Printers and design tools handle a
   * PNG far more predictably than an SVG copied out of the DOM, and this is
   * a file destined for a laminator or a table card.
   */
  function downloadQr() {
    const svg = qrRef.current?.querySelector('svg');
    if (!svg) return;
    const scale = 4; // ~672px — big enough to print without artefacts
    const size = 168 * scale;
    const blob = new Blob([new XMLSerializer().serializeToString(svg)], {
      type: 'image/svg+xml;charset=utf-8',
    });
    const url = URL.createObjectURL(blob);
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement('canvas');
      canvas.width = size;
      canvas.height = size;
      const ctx = canvas.getContext('2d');
      if (ctx) {
        // White behind the code: a transparent PNG printed on dark card is
        // an unscannable code.
        ctx.fillStyle = '#ffffff';
        ctx.fillRect(0, 0, size, size);
        ctx.drawImage(img, 0, 0, size, size);
      }
      URL.revokeObjectURL(url);
      const a = document.createElement('a');
      a.href = canvas.toDataURL('image/png');
      a.download = `${(me?.business.name ?? 'restaurant').replace(/[^a-z0-9]+/gi, '-').toLowerCase()}-nowserving-qr.png`;
      a.click();
    };
    img.src = url;
  }

  async function onSaveName(e: FormEvent) {
    e.preventDefault();
    if (!trimmed) {
      setNameError('Your restaurant needs a name.');
      return;
    }
    setSavingName(true);
    setNameError('');
    setSaved(false);
    try {
      await renameBusiness(trimmed);
      // Re-read /me so the header — which renders from auth state, not from
      // this page — shows the new name immediately, with no reload.
      await refreshMe();
      setSaved(true);
    } catch (err) {
      setNameError(errorMessage(err));
    } finally {
      setSavingName(false);
    }
  }

  async function onDelete() {
    setDeleting(true);
    setDeleteError('');
    try {
      // Stand the 401 handler down before the account stops existing: any
      // request still in flight is about to fail, and its default response is
      // a hard redirect to /owner/login — a sign-in form for the account
      // being deleted.
      beginIntentionalSignOut();
      await deleteBusiness();

      // Clear the session, then leave via a FULL page load rather than a
      // client-side navigate.
      //
      // An SPA navigation loses a race it cannot win: this page lives inside
      // ProtectedRoute, so the moment auth state clears the guard renders
      // <Navigate to="/owner/login"> — verified, and with no 401 involved.
      // Reloading also throws away every timer, poll and WebSocket
      // subscription still pointed at a business that no longer exists,
      // which is exactly what should happen when an account is deleted.
      logout();
      window.location.replace('/');
    } catch (err) {
      setDeleteError(errorMessage(err));
      setDeleting(false);
    }
  }

  if (!me) {
    return (
      <OwnerLayout>
        <div className="center-page">
          <span className="spin" />
        </div>
      </OwnerLayout>
    );
  }

  return (
    <OwnerLayout>
    <div className="settings-page">
      <h1 style={{ marginBottom: 4 }}>Settings</h1>
      <p className="micro" style={{ marginTop: 0, marginBottom: 24 }}>
        {me.email}
      </p>

      {/* ── Restaurant details ─────────────────────────────────────────── */}
      <section className="panel stack">
        <div>
          <h2 style={{ margin: 0, fontSize: '1.1rem' }}>Restaurant details</h2>
          <p className="venue-meta" style={{ marginTop: 4 }}>
            This is the name customers see in search, on your venue page and on
            every ticket.
          </p>
        </div>

        <form className="stack" onSubmit={onSaveName}>
          <label>
            <span className="micro">Restaurant name</span>
            <input
              value={name}
              onChange={(e) => {
                setName(e.target.value);
                setSaved(false);
                setNameError('');
              }}
              maxLength={255}
              aria-label="Restaurant name"
            />
          </label>

          {nameError && <div className="error">{nameError}</div>}

          <div className="row" style={{ gap: 12 }}>
            <button
              className="btn-accent btn-sm"
              disabled={savingName || !nameChanged || !trimmed}
            >
              {savingName ? 'Saving…' : 'Save changes'}
            </button>
            {saved && !nameChanged && (
              <span className="micro" style={{ color: 'var(--ok)' }}>✓ Saved</span>
            )}
          </div>
        </form>
      </section>

      {/* ── Restaurant QR ──────────────────────────────────────────────── */}
      <section className="panel stack">
        <div>
          <h2 style={{ margin: 0, fontSize: '1.1rem' }}>Your restaurant QR code</h2>
          <p className="venue-meta" style={{ marginTop: 4 }}>
            Print this once. It points at your restaurant, not at a single
            queue, so it keeps working when you close a line for the night or
            replace it entirely.
          </p>
        </div>

        <div className="qr-block">
          <div className="qr-wrap" ref={qrRef}>
            <QRCode value={qrUrl} size={168} />
          </div>
          <div className="stack" style={{ gap: 10, minWidth: 0 }}>
            <a className="micro qr-link" href={qrUrl} target="_blank" rel="noreferrer">
              {qrUrl}
            </a>
            <div className="row" style={{ gap: 10, flexWrap: 'wrap' }}>
              <button className="btn-ghost btn-sm" onClick={downloadQr}>
                Download QR
              </button>
              <button className="btn-ghost btn-sm" onClick={copyLink}>
                {copied ? '✓ Copied' : 'Copy link'}
              </button>
            </div>
          </div>
        </div>
      </section>

      {/* ── Danger zone ────────────────────────────────────────────────── */}
      <section className="panel stack danger-zone">
        <div>
          <h2 style={{ margin: 0, fontSize: '1.1rem', color: 'var(--danger)' }}>Danger zone</h2>
          <p className="venue-meta" style={{ marginTop: 4 }}>
            Deleting your restaurant is permanent and cannot be undone.
          </p>
        </div>

        {!dangerOpen ? (
          <button
            className="btn-danger btn-sm"
            style={{ alignSelf: 'flex-start' }}
            onClick={() => setDangerOpen(true)}
          >
            Delete restaurant…
          </button>
        ) : (
          <div className="stack">
            {/* Name what will actually be destroyed. "Are you sure?" is not a
                warning; a list of what disappears is. */}
            <div className="danger-warning stack" style={{ gap: 6 }}>
              <span style={{ fontWeight: 600 }}>This will permanently delete:</span>
              <ul style={{ margin: 0, paddingLeft: 20 }}>
                <li>every queue and its QR code</li>
                <li>everyone currently waiting, and your whole served history</li>
                <li>all reservations, including bookings customers are still expecting</li>
                <li>your venue location, remote-join and booking settings</li>
                <li>your owner login</li>
              </ul>
            </div>

            <label>
              <span className="micro">
                Type <strong>{currentName}</strong> to confirm
              </span>
              <input
                value={confirmText}
                onChange={(e) => setConfirmText(e.target.value)}
                placeholder={currentName}
                aria-label="Type the restaurant name to confirm deletion"
                autoComplete="off"
              />
            </label>

            {deleteError && <div className="error">{deleteError}</div>}

            <div className="row" style={{ gap: 12, flexWrap: 'wrap' }}>
              <button
                className="btn-danger btn-sm"
                disabled={confirmText.trim() !== currentName || deleting}
                onClick={onDelete}
              >
                {deleting ? 'Deleting…' : 'Delete permanently'}
              </button>
              <button
                className="btn-ghost btn-sm"
                onClick={() => {
                  setDangerOpen(false);
                  setConfirmText('');
                  setDeleteError('');
                }}
              >
                Cancel
              </button>
            </div>
          </div>
        )}
      </section>
    </div>
    </OwnerLayout>
  );
}
