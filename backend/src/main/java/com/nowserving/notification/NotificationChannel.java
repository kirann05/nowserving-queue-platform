package com.nowserving.notification;

import com.nowserving.entity.PushSubscription;

/**
 * A way to reach a customer. Today there is one implementation (Web Push);
 * the PRD's SMS via Twilio/SNS is a second adapter behind this same port,
 * requiring no change to the code that decides WHEN to notify.
 *
 * Separating "who to tell and when" (NotificationService) from "how to
 * deliver it" (this) is what makes adding SMS a new file instead of a
 * refactor.
 */
public interface NotificationChannel {

    /**
     * Attempt delivery. Implementations must NOT throw for ordinary failures
     * — a dead phone is a normal Tuesday, not an exception. Callers decide
     * what to do from the result.
     */
    DeliveryResult send(PushSubscription subscription, PushMessage message);

    enum DeliveryResult {
        /** Handed to the push service successfully. */
        DELIVERED,
        /** The subscription is dead (uninstalled / permission revoked) — delete it. */
        EXPIRED,
        /** Transient failure; the subscription is still worth keeping. */
        FAILED,
        /** The circuit breaker is OPEN — we deliberately didn't even try. */
        SKIPPED_CIRCUIT_OPEN,
        /** No VAPID keys configured on this server. */
        NOT_CONFIGURED
    }
}
