package com.nowserving.support;

import com.nowserving.entity.PushSubscription;
import com.nowserving.notification.NotificationChannel;
import com.nowserving.notification.PushMessage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records what WOULD have been sent, and can be told to misbehave on demand.
 *
 * Being able to say "now start failing" is the whole reason this exists: you
 * cannot ask Google's real push service to break so you can watch your
 * circuit breaker trip.
 */
public class FakeNotificationChannel implements NotificationChannel {

    public record Sent(String endpoint, PushMessage message) {}

    /** Thread-safe: notifications are delivered on a background pool. */
    public final List<Sent> sent = new CopyOnWriteArrayList<>();

    private volatile DeliveryResult nextResult = DeliveryResult.DELIVERED;

    public void reset() {
        sent.clear();
        nextResult = DeliveryResult.DELIVERED;
    }

    /** Make every subsequent send return this result. */
    public void willReturn(DeliveryResult result) {
        this.nextResult = result;
    }

    @Override
    public DeliveryResult send(PushSubscription subscription, PushMessage message) {
        sent.add(new Sent(subscription.getEndpoint(), message));
        return nextResult;
    }
}
