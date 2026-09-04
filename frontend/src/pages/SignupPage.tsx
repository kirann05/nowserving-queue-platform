/**
 * NS-9: business sign-up, then auto-login. Two API calls because the backend
 * deliberately doesn't return a token from signup — logging in is the only
 * way to mint one.
 */
import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { signup } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { GoogleSignInButton } from '../components/GoogleSignInButton';

export function SignupPage() {
  const { login, loginWithGoogle } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState({ businessName: '', displayName: '', email: '', password: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const set = (key: keyof typeof form) => (e: { target: { value: string } }) =>
    setForm({ ...form, [key]: e.target.value });

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await signup(form);
      await login(form.email, form.password);
      navigate('/owner');
    } catch (err) {
      setError(errorMessage(err)); // 409 duplicate email, 400 field errors...
    } finally {
      setBusy(false);
    }
  }

  /**
   * On the SIGN-UP page we forward whatever business name they've typed, so
   * a Google sign-up lands with the right name straight away. Empty is fine
   * — the backend falls back to a placeholder.
   */
  async function onGoogleCredential(idToken: string) {
    setBusy(true);
    setError('');
    try {
      await loginWithGoogle(idToken, form.businessName);
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
          <span className="micro">Get started</span>
          <h1>Create your business</h1>
        </div>

        <form className="stack" onSubmit={onSubmit}>
          <label>
            <span className="micro">Business name</span>
            <input value={form.businessName} onChange={set('businessName')} required autoFocus placeholder="Shiva's Barbers" />
          </label>
          <label>
            <span className="micro">Your name</span>
            <input value={form.displayName} onChange={set('displayName')} required />
          </label>
          <label>
            <span className="micro">Email</span>
            <input type="email" value={form.email} onChange={set('email')} required />
          </label>
          <label>
            <span className="micro">Password (8+ characters)</span>
            <input type="password" value={form.password} onChange={set('password')} required minLength={8} />
          </label>
          {error && <div className="error">{error}</div>}
          <button className="btn-accent" disabled={busy}>
            {busy ? 'Creating…' : 'Create account'}
          </button>
        </form>

        <div className="row" style={{ gap: 10 }}>
          <hr style={{ flex: 1, border: 0, borderTop: '1px solid var(--border)' }} />
          <span className="micro">or</span>
          <hr style={{ flex: 1, border: 0, borderTop: '1px solid var(--border)' }} />
        </div>
        <GoogleSignInButton onCredential={onGoogleCredential} />
        <span className="micro" style={{ textAlign: 'center' }}>
          Continue with Google — business account. Creates your restaurant's account, no password needed.
        </span>

        <span className="micro">
          Already set up? <Link to="/owner/login">Sign in</Link>
        </span>
      </div>
    </div>
  );
}
