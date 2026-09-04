/**
 * Shared chrome for all owner pages. Uses the SAME AppHeader as the customer
 * side (variant="owner") rather than its own topbar — see AppHeader's file
 * comment for why: the old owner-only topbar pointed its logo at /owner and
 * offered no route back to the public site, which made an owner feel dropped
 * into a different, unrelated application.
 */
import type { ReactNode } from 'react';
import { AppHeader } from './AppHeader';

export function OwnerLayout({ children }: { children: ReactNode }) {
  return (
    <>
      <AppHeader variant="owner" />
      <div className="shell">{children}</div>
    </>
  );
}
