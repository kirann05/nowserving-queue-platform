/**
 * Route guard: wraps owner-only pages. Mirrors the backend's
 * `anyRequest().authenticated()` — and that mirroring is the point:
 *
 * THE FRONTEND GUARD IS UX, NOT SECURITY. Anyone can open devtools and
 * render any page; what they can't do is get data out of the API without a
 * valid JWT. The real wall is SecurityConfig on the server. This component
 * just keeps logged-out users from staring at an empty dashboard.
 */
import { Navigate } from 'react-router-dom';
import { useAuth } from './AuthContext';
import type { ReactNode } from 'react';

export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { me } = useAuth();

  // Still resolving the stored token on first load — render a quiet spinner
  // instead of flashing the login page at a logged-in user.
  if (me === undefined) {
    return (
      <div className="center-page">
        <span className="spin" />
      </div>
    );
  }

  if (me === null) return <Navigate to="/owner/login" replace />;

  return children;
}
