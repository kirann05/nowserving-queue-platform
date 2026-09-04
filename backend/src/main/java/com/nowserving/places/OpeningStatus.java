package com.nowserving.places;

/**
 * Whether an EXTERNAL (OSM) place appears to be open right now — honestly
 * three-valued, because OSM's {@code opening_hours} tag is either absent,
 * present in a form we can confidently read, or present in a form we can't.
 *
 * There is no fourth state for "we think it's closed but aren't sure" —
 * {@link OpeningHoursEvaluator} treats anything it can't parse with
 * confidence as {@link #UNKNOWN}, never as {@link #CLOSED}. Telling a hungry
 * customer a restaurant is closed when we actually just failed to parse its
 * hours is a worse failure than saying nothing.
 */
public enum OpeningStatus {
    OPEN_NOW,
    CLOSED,
    UNKNOWN
}
