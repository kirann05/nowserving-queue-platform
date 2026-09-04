/**
 * NS-9: owner login. On success the JWT lands in localStorage (AuthContext)
 * and every later API call carries it via the axios interceptor.
 */
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useAuth } from '../auth/AuthContext';
import { GoogleSignInButton } from '../components/GoogleSignInButton';

export function LoginPage() {
  const { login, loginWithGoogle } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault(); // stop the browser's full-page form submission
    setBusy(true);
    setError('');
    try {
      await login(email, password);
      navigate('/owner');
    } catch (err) {
      // The backend deliberately says the same thing for unknown email and
      // wrong password — we just show whatever it said.
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function onGoogleCredential(idToken: string) {
    setBusy(true);
    setError('');
    try {
      await loginWithGoogle(idToken);
      navigate('/owner');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="center-page">
      <div className="panel stack">
        <div>
          <span className="micro">Owner console</span>
          <h1 className="display">
            now<span className="glow">serving</span>
          </h1>
        </div>

        <form className="stack" onSubmit={onSubmit}>
          <label>
            <span className="micro">Email</span>
            <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
          </label>
          <label>
            <span className="micro">Password</span>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
          </label>
          {error && <div className="error">{error}</div>}
          <button className="btn-accent" disabled={busy}>
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        {/* FR-1. On the LOGIN page we send no business name: if this Google
            account is new, the backend invents a placeholder the owner can
            rename — better than blocking them behind another form. */}
        <div className="row" style={{ gap: 10 }}>
          <hr style={{ flex: 1, border: 0, borderTop: '1px solid var(--border)' }} />
          <span className="micro">or</span>
          <hr style={{ flex: 1, border: 0, borderTop: '1px solid var(--border)' }} />
        </div>
        <GoogleSignInButton onCredential={onGoogleCredential} />
        {/* Google's own widget only accepts a handful of fixed labels (none
            of them mention "business") — this caption is what actually says
            the quiet part out loud: this button creates or signs into a
            RESTAURANT account, not a customer one. */}
        <span className="micro" style={{ textAlign: 'center', opacity: 0.7 }}>
          Continue with Google — business account
        </span>

        <span className="micro">
          No account? <Link to="/owner/signup">Create your business</Link>
        </span>
      </div>
    </div>
  );
}
