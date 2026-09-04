package com.nowserving.entity;

/**
 * How this customer asked to be reached (V9).
 *
 * Deliberately a customer PREFERENCE, not a delivery mechanism — the
 * mechanisms live behind {@link com.nowserving.notification.NotificationChannel}
 * and {@link com.nowserving.notification.SmsSender}. Keeping the two apart is
 * what lets us add a channel without touching the code that decides when to
 * notify, and lets the customer's choice survive a change of provider.
 *
 * Ordering reflects the PRD's recommendation for time-critical alerts:
 * Web Push first (free, instant, no phone number), SMS as the fallback for
 * people who won't grant notification permission, and email deliberately
 * absent — a "leave now" that lands in a promotions tab twenty minutes late
 * is worse than no alert, because the customer trusted it.
 */
public enum NotifyChannel {

    /** Browser Web Push. Works with the tab closed; costs nothing. */
    PUSH,

    /** SMS via the configured provider. Needs a phone number. */
    SMS,

    /** No alerts at all — a legitimate choice, not a failure state. The
     *  customer watches the live ticket page instead. */
    NONE
}
