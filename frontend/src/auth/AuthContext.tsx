/**
 * Global auth state via React Context.
 *
 * Context = a value any component in the tree can read without passing props
 * down ten levels ("prop drilling"). Auth is the canonical use case: the
 * topbar, the router guard, and the dashboard all need "who is logged in".
 *
 * What we store:
 *  - the JWT in localStorage (survives refresh; the axios interceptor reads it)
 *  - the decoded "me" profile in React state (fetched from GET /me on load)
 *
 * Security note (same trade-off as the customer's entry_token): localStorage
 * is readable by any JS on this origin, so an XSS hole = stolen token. The
 * hardened alternative is an HttpOnly cookie, which brings CSRF concerns
 * back. For this product's threat model, localStorage + a 24h expiry is an
 * acceptable, documented choice — not an accident.
 */
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { TOKEN_KEY } from '../api/client';
import { fetchMe, googleSignIn, login as apiLogin } from '../api/endpoints';
import type { MeResponse } from '../api/types';

interface AuthState {
  /** null = logged out; undefined = still checking on first load */
  me: MeResponse | null | undefined;
  login: (email: string, password: string) => Promise<void>;
  /** FR-1: exchange Google's ID token for our session. */
  loginWithGoogle: (idToken: string, businessName?: string) => Promise<void>;
  /** Re-read /me. Called after renaming the business so the header updates
   *  without a page reload — the name lives in one place and everything
   *  showing it reads from here. */
  refreshMe: () => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthState>(null!);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<MeResponse | null | undefined>(undefined);

  // On first mount: if a token survives in localStorage, ask the backend who
  // we are. An expired token 401s -> treated as logged out.
  useEffect(() => {
    if (!localStorage.getItem(TOKEN_KEY)) {
      setMe(null);
      return;
    }
    fetchMe()
      .then(setMe)
      .catch(() => {
        localStorage.removeItem(TOKEN_KEY);
        setMe(null);
      });
  }, []);

  async function login(email: string, password: string) {
    const { token } = await apiLogin(email, password);
    localStorage.setItem(TOKEN_KEY, token); // interceptor picks it up from here
    setMe(await fetchMe());
  }

  /**
   * Notice how little differs from login() above: Google proved WHO the
   * person is, but the session that follows is identical — our own JWT in
   * localStorage. Past this line, nothing in the app knows or cares which
   * button they clicked.
   */
  async function loginWithGoogle(idToken: string, businessName?: string) {
    const { token } = await googleSignIn(idToken, businessName);
    localStorage.setItem(TOKEN_KEY, token);
    setMe(await fetchMe());
  }

  async function refreshMe() {
    setMe(await fetchMe());
  }

  function logout() {
    localStorage.removeItem(TOKEN_KEY);
    setMe(null);
  }

  return (
    <AuthContext.Provider value={{ me, login, loginWithGoogle, refreshMe, logout }}>{children}</AuthContext.Provider>
  );
}

export const useAuth = () => useContext(AuthContext);
