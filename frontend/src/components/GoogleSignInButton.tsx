/**
 * FR-1: the "Sign in with Google" button.
 *
 * WHAT ACTUALLY HAPPENS WHEN THE USER CLICKS IT:
 *  1. Google's own script (loaded below) draws the button — we don't style
 *     it ourselves, because Google requires their exact branding.
 *  2. The user picks an account in Google's popup. Their password is typed
 *     into GOOGLE's page, never ours. We never see it. That's the entire
 *     point of OAuth/OIDC: delegate the "prove who you are" step.
 *  3. Google hands our page a "credential" — a signed ID token (a JWT).
 *  4. We pass it up to the caller, which POSTs it to our /auth/google.
 *
 * If VITE_GOOGLE_CLIENT_ID isn't set, we render a quiet hint instead of a
 * broken button — a half-working sign-in button is worse than none.
 */
import { useEffect, useRef, useState } from 'react';

/** Google's script attaches itself to window.google — tell TypeScript it may exist. */
declare global {
  interface Window {
    google?: {
      accounts: {
        id: {
          initialize: (config: { client_id: string; callback: (r: { credential: string }) => void }) => void;
          renderButton: (el: HTMLElement, options: Record<string, unknown>) => void;
        };
      };
    };
  }
}

const GSI_SRC = 'https://accounts.google.com/gsi/client';

/**
 * Load Google's script exactly once per page, and let every caller await the
 * SAME promise.
 *
 * The obvious version of this function checks "is the <script> tag already in
 * the DOM?" and resolves if so — and it is subtly WRONG. A tag being present
 * only means loading STARTED; the script may still be in flight, so
 * window.google is undefined and the caller silently does nothing.
 *
 * That is not a theoretical bug: React's StrictMode mounts every component
 * twice in development precisely to shake out effects like this. The first
 * mount appends the tag; the second mount sees it and charges ahead too early.
 * Caching the promise at module scope makes "started" and "finished" different
 * things, which is what they actually are.
 */
let gsiLoader: Promise<void> | null = null;

function loadGoogleScript(): Promise<void> {
  if (gsiLoader) return gsiLoader;

  gsiLoader = new Promise<void>((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(`script[src="${GSI_SRC}"]`);
    const script = existing ?? document.createElement('script');

    if (window.google) {
      resolve(); // already loaded (e.g. a hot reload)
      return;
    }
    script.addEventListener('load', () => resolve());
    script.addEventListener('error', () => reject(new Error('Could not load Google sign-in')));

    if (!existing) {
      script.src = GSI_SRC;
      script.async = true;
      document.head.appendChild(script);
    }
  });

  // A failed load must not be cached forever — let a later mount retry.
  gsiLoader.catch(() => {
    gsiLoader = null;
  });

  return gsiLoader;
}

interface Props {
  /** Receives the Google ID token; the page decides what to do with it. */
  onCredential: (idToken: string) => void;
}

export function GoogleSignInButton({ onCredential }: Props) {
  const clientId = import.meta.env.VITE_GOOGLE_CLIENT_ID as string | undefined;
  const containerRef = useRef<HTMLDivElement>(null);
  // Ref so re-renders don't re-initialise Google's widget.
  const callbackRef = useRef(onCredential);
  callbackRef.current = onCredential;
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!clientId || !containerRef.current) return;

    let cancelled = false;
    loadGoogleScript()
      .then(() => {
        if (cancelled || !containerRef.current || !window.google) return;
        window.google.accounts.id.initialize({
          client_id: clientId,
          callback: (response) => callbackRef.current(response.credential),
        });
        window.google.accounts.id.renderButton(containerRef.current, {
          theme: 'filled_black',
          size: 'large',
          shape: 'pill',
          text: 'continue_with',
          width: 300,
        });
      })
      .catch(() => !cancelled && setFailed(true));

    return () => {
      cancelled = true;
    };
  }, [clientId]);

  if (!clientId) {
    return (
      <span className="micro" style={{ opacity: 0.6 }}>
        Google sign-in not configured — see docs/GOOGLE_OAUTH_SETUP.md
      </span>
    );
  }
  if (failed) {
    return <span className="micro">Google sign-in unavailable — use email and password</span>;
  }

  return <div ref={containerRef} style={{ display: 'flex', justifyContent: 'center' }} />;
}
