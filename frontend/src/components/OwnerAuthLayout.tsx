/**
 * Layout for the owner login/signup pages.
 *
 * These are the one place someone can arrive already knowing they want the
 * owner side but WITHOUT being authenticated, so the header runs in customer
 * variant: its links point back out to the public product (Restaurants,
 * home) rather than to a Dashboard they can't reach yet. That is the fix for
 * these pages previously being a dead end with no header at all.
 */
import { Outlet } from 'react-router-dom';
import { AppHeader } from './AppHeader';

export function OwnerAuthLayout() {
  return (
    <>
      <AppHeader variant="customer" />
      <Outlet />
    </>
  );
}
