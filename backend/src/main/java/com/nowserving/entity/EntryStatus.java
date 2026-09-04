package com.nowserving.entity;

/**
 * Lifecycle of one customer's place in line — now a real STATE MACHINE.
 *
 * Statuses used to be assigned ad hoc; each terminal state is now explicit
 * about what it may become. Guarding transitions in one place prevents the
 * whole family of "a SERVED customer somehow became WAITING again" bugs
 * before they can be written.
 *
 *   WAITING ──> CALLED ──> SERVED
 *      │           └─────> NO_SHOW
 *      ├─────────────────> SERVED     (Sprint 1 has no separate CALLED step)
 *      ├─────────────────> NO_SHOW
 *      └─────────────────> LEFT       (the customer gave up and told us)
 */
public enum EntryStatus {
    WAITING,
    CALLED,
    SERVED,
    NO_SHOW,
    /** The customer chose to leave the line — distinct from NO_SHOW, which is
     *  staff's judgement. The distinction matters for the no-show analytics. */
    LEFT;

    /** The single source of truth for what is allowed to happen next. */
    public boolean canTransitionTo(EntryStatus target) {
        return switch (this) {
            case WAITING -> target == CALLED || target == SERVED
                    || target == NO_SHOW || target == LEFT;
            case CALLED -> target == SERVED || target == NO_SHOW;
            // Terminal states. SERVED -> WAITING would need an explicit,
            // deliberate "rollback" feature — not a casual setter call.
            case SERVED, NO_SHOW, LEFT -> false;
        };
    }
}
