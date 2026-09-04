/**
 * Route table — the entire information architecture of the app in one glance.
 *
 * V9 REORGANISED THIS, and the reshuffle is the fix for a real role-design
 * bug. "/" used to be the owner dashboard, so an unauthenticated visitor was
 * bounced to a page headed "Owner console" — and one tap on its Google button
 * silently created a business for a customer who only wanted a table.
 *
 * Now the split follows the audience, not the codebase:
 *
 *   CUSTOMER (no account, ever)   /  /v/:t  /j/:t  /t/:t  /b/:t  /r/:t
 *   OWNER    (JWT)                /owner/**
 *
 * which also mirrors the backend's own split between /public/** and the
 * JWT-walled routes.
 */
import { Navigate, Route, Routes } from 'react-router-dom';
import { ProtectedRoute } from './auth/ProtectedRoute';
import { CustomerLayout } from './components/CustomerLayout';
import { OwnerAuthLayout } from './components/OwnerAuthLayout';
import { BookPage } from './pages/BookPage';
import { ReservationPage } from './pages/ReservationPage';
import { DashboardPage } from './pages/DashboardPage';
import { DiscoverPage } from './pages/DiscoverPage';
import { NearbyPage } from './pages/NearbyPage';
import { JoinPage } from './pages/JoinPage';
import { LoginPage } from './pages/LoginPage';
import { QueueDetailPage } from './pages/QueueDetailPage';
import { RestaurantQrPage } from './pages/RestaurantQrPage';
import { SettingsPage } from './pages/SettingsPage';
import { SignupPage } from './pages/SignupPage';
import { TicketPage } from './pages/TicketPage';
import { VenuePage } from './pages/VenuePage';

export default function App() {
  return (
    <Routes>
      {/* ---- customer: the default audience ---- */}
      {/* V9: discovery is the homepage. No login, no account, no owner words.
          A layout route (no path of its own) so every page below gets the
          persistent header for free — see CustomerLayout. */}
      <Route element={<CustomerLayout />}>
        <Route path="/" element={<DiscoverPage />} />
        {/* The full "every nearby restaurant" list — the homepage only
            teases a few and links here for the rest. */}
        <Route path="/restaurants" element={<NearbyPage />} />
        {/* Earlier name for the same page; kept so any link already shared
            or bookmarked keeps working. */}
        <Route path="/nearby" element={<Navigate to="/restaurants" replace />} />
        {/* Where a scanned RESTAURANT QR lands. Permanent per business, so
            it keeps working when queues close or are replaced — unlike
            /v/:joinToken, which is tied to one queue. */}
        <Route path="/qr/:publicToken" element={<RestaurantQrPage />} />
        <Route path="/v/:joinToken" element={<VenuePage />} />
        <Route path="/j/:joinToken" element={<JoinPage />} />
        <Route path="/t/:entryToken" element={<TicketPage />} />
        {/* FR-10: book ahead, then your confirmation */}
        <Route path="/b/:joinToken" element={<BookPage />} />
        <Route path="/r/:reservationToken" element={<ReservationPage />} />
      </Route>

      {/* ---- owner console ----
          login/signup sit under an OwnerAuthLayout so they carry the same
          header as everything else. Previously they rendered bare, with no
          header and no link anywhere: an owner who landed on the login page
          had no route back to the public site at all. */}
      <Route element={<OwnerAuthLayout />}>
        <Route path="/owner/login" element={<LoginPage />} />
        <Route path="/owner/signup" element={<SignupPage />} />
      </Route>
      <Route
        path="/owner"
        element={
          <ProtectedRoute>
            <DashboardPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/owner/queues/:id"
        element={
          <ProtectedRoute>
            <QueueDetailPage />
          </ProtectedRoute>
        }
      />
      {/* Rename or delete the business. Owner-only, same guard as the rest. */}
      <Route
        path="/owner/settings"
        element={
          <ProtectedRoute>
            <SettingsPage />
          </ProtectedRoute>
        }
      />

      {/* Old owner URLs stay alive — existing bookmarks and any QR/poster
          printed against them must not break just because we reorganised. */}
      <Route path="/login" element={<Navigate to="/owner/login" replace />} />
      <Route path="/signup" element={<Navigate to="/owner/signup" replace />} />
      <Route path="/queues/:id" element={<LegacyQueueRedirect />} />

      {/* anything else → the customer homepage, not a login form */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

/** Keeps /queues/:id working by forwarding it to /owner/queues/:id. */
function LegacyQueueRedirect() {
  const id = window.location.pathname.split('/').pop();
  return <Navigate to={`/owner/queues/${id}`} replace />;
}
