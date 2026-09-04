/**
 * Opting a customer in to "you're next" notifications (FR-8).
 *
 * The four steps, in order — each one can fail independently, which is why
 * this hook reports a state rather than a boolean:
 *   1. register the service worker (/sw.js)
 *   2. fetch the server's VAPID public key
 *   3. ask the user for permission  <- a browser prompt; only ever on a click
 *   4. subscribe, and send the subscription to our backend
 *
 * Deliberately never asked automatically on page load: browsers penalise
 * sites that do, and users reflexively hit "Block" — which is permanent and
 * cannot be undone from JavaScript.
 */
import { useCallback, useEffect, useState } from 'react';
import { getVapidKey, savePushSubscription } from '../api/endpoints';

export type PushState =
  | 'unknown' // still asking the server whether push is even configured
  | 'unsupported' // this browser has no push (older Safari, some in-app browsers)
  | 'disabled' // the server has no VAPID keys configured
  | 'available' // ready — show the opt-in button
  | 'subscribing'
  | 'enabled'
  | 'denied' // the user said no; only they can undo it, in site settings
  | 'error';

/**
 * Web Push wants the key as raw bytes, but it travels as base64url text.
 *
 * Returns ArrayBuffer rather than Uint8Array: modern TS types Uint8Array as
 * possibly backed by a SharedArrayBuffer, which the DOM's BufferSource won't
 * accept. Handing over the plain buffer sidesteps that without a cast.
 */
function base64UrlToBytes(base64Url: string): ArrayBuffer {
  const padding = '='.repeat((4 - (base64Url.length % 4)) % 4);
  const base64 = (base64Url + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(base64);
  const bytes = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes.buffer;
}

export function usePushNotifications(entryToken: string | undefined) {
  // Starts 'unknown', NOT 'available': until we've asked the server whether
  // it holds VAPID keys we genuinely don't know if push can work, and
  // showing "🔔 Notify me when I'm next" in the meantime promises delivery
  // we may be unable to make. The UI treats 'unknown' as "show nothing yet".
  const [state, setState] = useState<PushState>('unknown');

  useEffect(() => {
    let cancelled = false;

    if (!('serviceWorker' in navigator) || !('PushManager' in window)) {
      setState('unsupported');
      return;
    }
    if (Notification.permission === 'denied') {
      setState('denied');
      return;
    }

    (async () => {
      // Already subscribed from a previous visit? Don't nag.
      const registration = await navigator.serviceWorker.getRegistration();
      const existing = await registration?.pushManager.getSubscription();
      if (cancelled) return;
      if (existing) {
        setState('enabled');
        return;
      }
      // ASK FIRST, OFFER SECOND. The server tells us whether it has VAPID
      // keys at all; with none configured, push is genuinely unavailable and
      // the customer should be pointed at SMS/the live page instead of a
      // button that can only fail after they tap it.
      try {
        const { publicKey } = await getVapidKey();
        if (!cancelled) setState(publicKey ? 'available' : 'disabled');
      } catch {
        if (!cancelled) setState('disabled');
      }
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  const enable = useCallback(async () => {
    if (!entryToken) return;
    setState('subscribing');
    try {
      const { publicKey } = await getVapidKey();
      if (!publicKey) {
        setState('disabled'); // server has no VAPID keys — nothing to do
        return;
      }

      const registration = await navigator.serviceWorker.register('/sw.js');
      await navigator.serviceWorker.ready;

      const permission = await Notification.requestPermission();
      if (permission !== 'granted') {
        setState(permission === 'denied' ? 'denied' : 'available');
        return;
      }

      const subscription = await registration.pushManager.subscribe({
        // Required by spec: every push must be user-visible. Sending silent
        // pushes is how sites get their permission revoked by the browser.
        userVisibleOnly: true,
        applicationServerKey: base64UrlToBytes(publicKey),
      });

      // subscription.toJSON() is exactly the shape the backend expects, so
      // we forward it whole rather than hand-copying fields.
      await savePushSubscription(entryToken, subscription.toJSON());
      setState('enabled');
    } catch (err) {
      console.error('Could not enable notifications', err);
      setState('error');
    }
  }, [entryToken]);

  return { state, enable };
}
