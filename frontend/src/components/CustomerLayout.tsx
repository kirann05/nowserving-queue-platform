/**
 * Wraps every customer-facing route in the shared AppHeader, via React
 * Router's layout-route pattern (a parent <Route> with no `path`, rendering
 * <Outlet/> for whichever child route matched). One header definition, every
 * page gets it automatically — a new page added under this layout in App.tsx
 * cannot forget it.
 */
import { Outlet } from 'react-router-dom';
import { AppHeader } from './AppHeader';

export function CustomerLayout() {
  return (
    <>
      <AppHeader variant="customer" />
      <Outlet />
    </>
  );
}
