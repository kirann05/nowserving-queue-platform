/**
 * "Do I have a ticket worth showing a shortcut to?" — asked by the header's
 * My Ticket link and the homepage's active-ticket card.
 *
 * THE BUG THIS EXISTS TO FIX: both callers used to read localStorage alone
 * and treat "a token is written down" as "the ticket is live". So after a
 * customer was served, the homepage kept announcing "You have an active
 * ticket", the header kept offering My Ticket, and a refresh resurrected
 * both — localStorage has no idea the restaurant moved on.
 *
 * localStorage is this browser's notepad; the server is the source of truth.
 * So we read the notepad, then confirm with GET /public/entries/{token}:
 *
 *   WAITING | CALLED           -> active, show the shortcut
 *   SERVED | NO_SHOW | LEFT    -> terminal, forget it permanently
 *   404 (token no longer valid) -> forget it permanently
 *   network error               -> keep it, show nothing this render
 *
 * That last line matters: a flaky connection must not delete a ticket
 * somebody is standing in line holding. Only a definite answer prunes.
 */
import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { getPosition } from '../api/endpoints';
import {
  findMostRecentTicket,
  loadTickets,
  removeTicket,
  type StoredTicket,
} from '../api/ticketStorage';
import type { EntryStatus } from '../api/types';

/** EntryStatus values a customer can still act on. Everything else is done. */
const ACTIVE_STATUSES: EntryStatus[] = ['WAITING', 'CALLED'];

export function isActiveStatus(status: EntryStatus): boolean {
  return ACTIVE_STATUSES.includes(status);
}

/** Past this, even a genuinely-WAITING ticket is more likely forgotten than
 *  real, and a stale "My Ticket" link reads as the app being broken. */
const MAX_AGE_MS = 12 * 60 * 60 * 1000;

export interface ActiveTicket extends StoredTicket {
  /** Server-confirmed name, which beats whatever we wrote down at join time. */
  businessName?: string;
}

/**
 * Returns the live ticket, or null. Null while still checking — callers
 * render nothing rather than flashing a shortcut that may vanish.
 */
export function useActiveTicket(): ActiveTicket | null {
  const [ticket, setTicket] = useState<ActiveTicket | null>(null);
  const location = useLocation();

  useEffect(() => {
    let cancelled = false;

    const stored = findMostRecentTicket();
    if (!stored) {
      setTicket(null);
      return;
    }
    // Cheap local disqualification first — no point asking the server about
    // a ticket we'd refuse to show anyway.
    if (Date.now() - new Date(stored.createdAt).getTime() >= MAX_AGE_MS) {
      setTicket(null);
      return;
    }

    getPosition(stored.entryToken)
      .then((position) => {
        if (cancelled) return;
        if (isActiveStatus(position.status)) {
          setTicket({ ...stored, businessName: position.businessName ?? stored.businessName });
        } else {
          // Definitive: the restaurant is done with this ticket.
          removeTicket(stored.entryToken);
          setTicket(null);
        }
      })
      .catch((err) => {
        if (cancelled) return;
        // 404 = the server has never heard of this token (purged, or from a
        // reset database). Anything else — offline, 5xx — is our problem,
        // not proof the ticket is gone, so leave storage alone.
        if (err?.response?.status === 404) removeTicket(stored.entryToken);
        setTicket(null);
      });

    return () => {
      cancelled = true;
    };
    // Re-check on every navigation: joining a queue should make the shortcut
    // appear, and being served should make it disappear, without a reload.
  }, [location.pathname]);

  return ticket;
}

/**
 * The still-live tickets this browser holds for ONE queue — what the join
 * page offers as "Continue as …".
 *
 * Same correctness problem as above, different screen: an already-served
 * customer was being invited to "Continue as" a ticket that no longer
 * existed, landing them on a completed screen instead of the join form.
 *
 * Returns `undefined` while checking (render nothing) and an array once
 * known, so the caller can tell "no tickets" from "don't know yet" — the
 * join form defaults open, and must not flicker shut a moment later.
 */
export function useLiveTicketsForQueue(joinToken: string | undefined): StoredTicket[] | undefined {
  const [live, setLive] = useState<StoredTicket[] | undefined>(undefined);

  useEffect(() => {
    if (!joinToken) {
      setLive([]);
      return;
    }
    let cancelled = false;
    const stored = loadTickets(joinToken);
    if (stored.length === 0) {
      setLive([]);
      return;
    }

    Promise.all(
      stored.map((t) =>
        getPosition(t.entryToken)
          .then((p) => (isActiveStatus(p.status) ? t : { prune: t }))
          // Network trouble keeps the ticket (we simply don't offer it now);
          // only a definite 404 prunes. Mirrors useActiveTicket's rule.
          .catch((err) => (err?.response?.status === 404 ? { prune: t } : null)),
      ),
    ).then((settled) => {
      if (cancelled) return;
      const keep: StoredTicket[] = [];
      for (const item of settled) {
        if (item && 'prune' in item) removeTicket(item.prune.entryToken);
        else if (item) keep.push(item);
      }
      setLive(keep);
    });

    return () => {
      cancelled = true;
    };
  }, [joinToken]);

  return live;
}
