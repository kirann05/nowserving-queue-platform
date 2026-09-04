/**
 * THE header for the whole product — customer side and owner side both.
 *
 * Why one component and not two: NowServing kept feeling like two unrelated
 * apps bolted together. The owner console had its own tiny topbar whose logo
 * pointed at /owner, so once an owner signed in there was no route back to
 * the public site at all except logging out; the owner LOGIN page had no
 * header whatsoever and was a genuine dead end. Sharing one header — same
 * height, same brand, same type scale, differing only in which links it
 * carries — is what makes the two halves read as one product.
 *
 * The brand always goes to "/" (the public homepage). An owner clicking the
 * logo expects the product's front door, not their own dashboard; their
 * dashboard has its own explicit "Dashboard" link.
 */
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useActiveTicket } from '../hooks/useActiveTicket';
import { useAuth } from '../auth/AuthContext';

interface Props {
  /** 'owner' swaps the customer links for console links and shows the signed
   *  in business. Everything else — height, brand, spacing — is identical. */
  variant?: 'customer' | 'owner';
}

export function AppHeader({ variant = 'customer' }: Props) {
  const location = useLocation();
  const navigate = useNavigate();
  const activeTicket = useActiveTicket();
  const { me, logout } = useAuth();

  const onHome = location.pathname === '/';
  // On the login/signup pages the visitor is already AT the owner console's
  // front door; offering "Owner Console" there is a link to the page they're
  // standing on.
  const onOwnerAuthPage =
    location.pathname === '/owner/login' || location.pathname === '/owner/signup';
  // React Router gives the very first entry in a tab the key "default" —
  // there is no real "back" to go to (e.g. a QR code opened directly in a
  // fresh tab), so that case goes home instead of leaving the app entirely.
  const canGoBack = location.key !== 'default';
  const isActive = (path: string) =>
    path === '/' ? onHome : location.pathname.startsWith(path);

  return (
    <header className="app-header">
      <div className="app-header-inner">
        <div className="app-header-left">
          {/* The Back slot is ALWAYS rendered, and merely made invisible on
              the homepage where there is nothing to go back to.

              Why not just omit it: the button is ~95px wide and sits to the
              LEFT of the brand, so conditionally rendering it slid the logo
              95px sideways between the homepage and every other page. People
              clicked where the logo had just been, hit Back instead, and
              reported that "the logo sometimes doesn't take me home". The
              slot is reserved so the brand never moves. */}
          <button
            className={`app-header-back${onHome ? ' is-invisible' : ''}`}
            onClick={() => (canGoBack ? navigate(-1) : navigate('/'))}
            aria-label="Go back"
            aria-hidden={onHome}
            tabIndex={onHome ? -1 : undefined}
          >
            ←<span className="app-header-back-label">Back</span>
          </button>
          {/* Always "/" — see the file comment on why the owner's logo must
              not point at their own dashboard. */}
          <Link to="/" className="app-header-brand" aria-label="NowServing home">
            now<em>serving</em>
          </Link>
          {variant === 'owner' && <span className="app-header-context">Owner Console</span>}
        </div>

        <nav className="app-header-nav" aria-label="Primary">
          {variant === 'customer' ? (
            <>
              <Link
                to="/restaurants"
                className={`app-header-link${isActive('/restaurants') ? ' is-active' : ''}`}
              >
                Restaurants
              </Link>
              {activeTicket && (
                <Link
                  to={`/t/${activeTicket.entryToken}`}
                  className={`app-header-link app-header-ticket${isActive('/t/') ? ' is-active' : ''}`}
                >
                  My Ticket
                </Link>
              )}
              {/* Point at the page the visitor will actually LAND on.
                  Linking unconditionally to /owner meant a logged-out
                  visitor pushed a /owner history entry that ProtectedRoute
                  immediately replaced with /owner/login — so on the login
                  page this link looked broken (clicking it changed nothing)
                  while quietly stacking duplicate history entries, which is
                  what left the Back button needing several presses to move.
                  Resolving the destination here means no redirect at all. */}
              {!onOwnerAuthPage && (
                <Link
                  to={me ? '/owner' : '/owner/login'}
                  className="app-header-link app-header-muted app-header-owner-link"
                >
                  Owner Console
                </Link>
              )}
            </>
          ) : (
            <>
              <Link
                to="/owner"
                className={`app-header-link${location.pathname === '/owner' ? ' is-active' : ''}`}
              >
                Dashboard
              </Link>
              {/* The escape hatch the owner console never had. */}
              <Link to="/" className="app-header-link">
                Public site
              </Link>
              <Link
                to="/owner/settings"
                className={`app-header-link${
                  location.pathname === '/owner/settings' ? ' is-active' : ''
                }`}
              >
                Settings
              </Link>
              {me && (
                <>
                  <span className="app-header-business" title={me.business.name}>
                    {me.business.name}
                  </span>
                  <button className="app-header-link app-header-muted app-header-logout" onClick={logout}>
                    Log out
                  </button>
                </>
              )}
            </>
          )}
        </nav>
      </div>
    </header>
  );
}
