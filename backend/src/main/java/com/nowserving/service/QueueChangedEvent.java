package com.nowserving.service;

import com.nowserving.entity.EntryStatus;

/**
 * "The line of queue X just changed" — published by services after any
 * mutation (join, advance, no-show).
 *
 * Why an event instead of calling the WebSocket broadcaster directly from
 * QueueService? Decoupling with a purpose:
 *  1. Services stay pure domain logic — they don't know WebSockets exist.
 *  2. The listener runs AFTER COMMIT (see QueueEventsBroadcaster), which a
 *     direct call inside the @Transactional method could not guarantee.
 *
 * affectedEntryToken/affectedStatus carry the one entry that LEFT the
 * waiting list (served or no-show) — it wouldn't appear in the post-commit
 * waiting-list query, but that customer deserves the news most of all.
 */
public record QueueChangedEvent(Long queueId, String affectedEntryToken, EntryStatus affectedStatus) {

    /** A change with no departed entry (a new customer joined). */
    public static QueueChangedEvent of(Long queueId) {
        return new QueueChangedEvent(queueId, null, null);
    }
}
