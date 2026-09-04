/*
 * Service worker — the piece that makes notifications work with the tab CLOSED.
 *
 * A service worker is a script the browser keeps around independently of any
 * page. It has no DOM and no UI; it just wakes up when something happens.
 * That "independent of any page" property is the entire reason Web Push can
 * reach a customer who wandered off to the coffee shop, while a WebSocket
 * cannot: the socket dies with the tab, this does not.
 *
 * Plain JS in /public on purpose — it is served as-is at /sw.js, outside the
 * bundler, because a service worker must live at the root to control the
 * whole site ("scope").
 */

// Fired when our server sends a push through the browser vendor's service.
self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    payload = {};
  }

  const title = payload.title || 'NowServing';
  const options = {
    body: payload.body || '',
    icon: '/favicon.svg',
    badge: '/favicon.svg',
    // Carry the ticket URL so a tap can open the right page.
    data: { url: payload.url || '/' },
    // "You're next" is the whole product — don't let it auto-dismiss before
    // the customer looks at their phone.
    requireInteraction: true,
    // Replaces any earlier NowServing notification instead of stacking up.
    tag: 'nowserving-position',
  };

  // waitUntil keeps the worker alive until the promise settles. Without it
  // the browser may kill the worker mid-flight and nothing is shown.
  event.waitUntil(self.registration.showNotification(title, options));
});

// Tapping the notification: focus the ticket tab if it's still open,
// otherwise open it.
self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const targetUrl = (event.notification.data && event.notification.data.url) || '/';

  event.waitUntil(
    self.clients
      .matchAll({ type: 'window', includeUncontrolled: true })
      .then((windows) => {
        for (const client of windows) {
          if (client.url === targetUrl && 'focus' in client) return client.focus();
        }
        return self.clients.openWindow(targetUrl);
      }),
  );
});
