/**
 * The customer's own index of tickets they hold, kept in localStorage.
 *
 * Extracted out of JoinPage so the header's "My Ticket" shortcut and the
 * homepage's "View my active ticket" card can read the same data without
 * duplicating the key format or the multi-ticket-per-queue shape — that
 * shape exists because a family sharing one phone needs more than one
 * ticket per queue (see JoinPage's comment on the bug this fixed).
 *
 * The entry token in each stored ticket's URL remains the actual
 * authorisation; everything here is a convenience index, never a source of
 * truth the backend trusts.
 */

export interface StoredTicket {
  entryToken: string;
  customerName: string;
  createdAt: string;
  /** businessName isn't always known at join time in every call site, so
   *  it's optional — "My Ticket" falls back to a generic label without it. */
  businessName?: string;
}

const PREFIX = 'ns_tickets_';
const keyFor = (joinToken: string) => `${PREFIX}${joinToken}`;

export function loadTickets(joinToken: string): StoredTicket[] {
  try {
    const raw = localStorage.getItem(keyFor(joinToken));
    return raw ? (JSON.parse(raw) as StoredTicket[]) : [];
  } catch {
    return [];
  }
}

export function saveTicket(joinToken: string, ticket: StoredTicket) {
  const tickets = loadTickets(joinToken);
  if (!tickets.some((t) => t.entryToken === ticket.entryToken)) {
    tickets.push(ticket);
    try {
      localStorage.setItem(keyFor(joinToken), JSON.stringify(tickets));
    } catch {
      /* localStorage full or blocked — the entry token in the URL still
         works, this is only the "remember it for me" convenience */
    }
  }
}

/**
 * Forget one ticket, wherever it lives.
 *
 * Called when the SERVER says a ticket reached a terminal state (SERVED /
 * NO_SHOW / LEFT). Storing a ticket is how this browser remembers it; only
 * the backend can say whether it's still live, so this is the other half of
 * that contract — see hooks/useActiveTicket.ts.
 *
 * Scans every queue's array because the caller generally holds an entry
 * token without knowing which join token it was filed under.
 */
export function removeTicket(entryToken: string) {
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key?.startsWith(PREFIX)) continue;
      const raw = localStorage.getItem(key);
      if (!raw) continue;
      const remaining = (JSON.parse(raw) as StoredTicket[]).filter(
        (t) => t.entryToken !== entryToken,
      );
      // Drop the key entirely once its last ticket is gone, so findMostRecent
      // doesn't keep walking empty arrays forever.
      if (remaining.length === 0) localStorage.removeItem(key);
      else localStorage.setItem(key, JSON.stringify(remaining));
    }
  } catch {
    /* storage blocked — the stale entry is a cosmetic problem at worst */
  }
}

/**
 * The single most recently created ticket across EVERY queue this browser
 * has ever joined — what "My Ticket" / "View my active ticket" link to.
 *
 * Scans every `ns_tickets_*` key rather than tracking one "last joined"
 * pointer separately, so it can never drift out of sync with the per-queue
 * arrays that are the actual source of truth for this browser.
 *
 * NOTE: "most recent" is not "active". This function only knows what this
 * browser wrote down; a ticket may since have been served. Anything showing
 * the customer an "active ticket" affordance must confirm with the server —
 * use useActiveTicket(), not this directly.
 */
export function findMostRecentTicket(): StoredTicket | null {
  let best: StoredTicket | null = null;
  try {
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key?.startsWith(PREFIX)) continue;
      const raw = localStorage.getItem(key);
      if (!raw) continue;
      const tickets = JSON.parse(raw) as StoredTicket[];
      for (const t of tickets) {
        if (!best || t.createdAt > best.createdAt) best = t;
      }
    }
  } catch {
    return null;
  }
  return best;
}
