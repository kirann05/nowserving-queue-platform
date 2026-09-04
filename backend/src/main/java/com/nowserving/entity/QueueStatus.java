package com.nowserving.entity;

/**
 * Lifecycle of a queue. Stored as a string in the DB (see @Enumerated on the
 * Queue entity) so `SELECT status FROM queues` reads as 'OPEN', not a number.
 */
public enum QueueStatus {
    /** Accepting new customers via the join link. */
    OPEN,
    /** Join attempts are rejected with 400; existing entries can still be served. */
    CLOSED
}
