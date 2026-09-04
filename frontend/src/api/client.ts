/**
 * The single Axios instance every API call goes through.
 *
 * Why one shared instance instead of calling axios directly everywhere:
 *  1. One place for the base URL (env-driven, so prod just sets VITE_API_URL).
 *  2. One place to attach the JWT to every request (the request interceptor).
 *  3. One place to react to 401s globally (the response interceptor).
 *
 * An "interceptor" is middleware for HTTP calls — a function that runs on
 * every request (or response) before your code sees it. Same idea as the
 * backend's JwtAuthenticationFilter, mirrored on the client.
 */
import axios from 'axios';

// Vite exposes env vars that start with VITE_ to browser code at build time.
export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

export const TOKEN_KEY = 'ns_token';

export const api = axios.create({ baseURL: API_URL });

// --- request interceptor: attach the JWT if we have one ---
api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    // Exactly the header JwtAuthenticationFilter reads on the backend.
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

/**
 * Set while the app is deliberately tearing down its own session (deleting
 * the business). Without this, the 401 handler below would hard-redirect to
 * /owner/login — a sign-in form for the account the owner just deleted —
 * and win the race against the app's own navigation to the homepage.
 */
let intentionalSignOut = false;
export function beginIntentionalSignOut() {
  intentionalSignOut = true;
}

// --- response interceptor: expired/invalid token -> force re-login ---
api.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status;
    const url: string = error?.config?.url ?? '';
    // A 401 from a protected endpoint means our token is dead (expired or
    // forged). Public endpoints and the login call itself are exempt — a
    // wrong password must show an inline error, not bounce the page.
    if (
      status === 401 &&
      !intentionalSignOut &&
      !url.startsWith('/auth') &&
      !url.startsWith('/public')
    ) {
      localStorage.removeItem(TOKEN_KEY);
      window.location.href = '/owner/login';
    }
    return Promise.reject(error);
  },
);

/** Pull the human-readable message out of our backend's ApiError shape. */
export function errorMessage(err: unknown): string {
  if (axios.isAxiosError(err)) {
    const data = err.response?.data as { message?: string; fieldErrors?: Record<string, string> } | undefined;
    if (data?.fieldErrors && Object.keys(data.fieldErrors).length > 0) {
      return Object.entries(data.fieldErrors)
        .map(([field, msg]) => `${field}: ${msg}`)
        .join('; ');
    }
    if (data?.message) return data.message;
    if (err.response) return `Request failed (${err.response.status})`;
    // No response object at all. That is EITHER the server being unreachable
    // or a response the browser refused to hand us — a CORS-blocked reply
    // looks identical from here. "Is the backend running?" is a confident
    // claim we cannot actually make, and it sent at least one debugging
    // session after a server that was up the whole time.
    return 'Couldn’t reach the server. Check your connection and try again.';
  }
  return 'Something went wrong';
}
