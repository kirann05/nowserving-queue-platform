package com.nowserving.notification;

import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.QueueEntry;
import com.nowserving.repository.PushSubscriptionRepository;
import com.nowserving.repository.QueueEntryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Decides WHO to notify and WHAT to say (FR-8). Delivery itself belongs to a
 * {@link NotificationChannel}.
 *
 * Lives in its own bean rather than inside NotificationListener for a
 * concrete reason: its methods are @Transactional, and Spring's transactions
 * only work when the call arrives through the proxy. A listener calling
 * {@code this.notifyNext(...)} on itself would silently run with NO
 * transaction — the classic self-invocation trap.
 *
 * REQUIRES_NEW is the second half of that story, and it cost a real bug to
 * learn. These methods are invoked from a @TransactionalEventListener firing
 * AFTER_COMMIT — at that point the original transaction is finished. A plain
 * @Transactional would join that completing transaction, and any write made
 * here (deleting a dead subscription, stamping next_notified_at) would be
 * silently discarded: no error, no log, just a change that never happened.
 * REQUIRES_NEW forces a genuinely new transaction that can commit on its own.
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final QueueEntryRepository entryRepository;
    private final PushSubscriptionRepository subscriptionRepository;
    private final NotificationChannel channel;
    /** V9: the optional second wire. Always present as a bean, usually
     *  answering NOT_CONFIGURED — see TwilioSmsSender. */
    private final SmsSender smsSender;

    @Value("${app.public-base-url}")
    private String publicBaseUrl;

    /**
     * "You're next" for whoever is now at the front of this queue.
     *
     * IDEMPOTENT by design: next_notified_at is stamped the first time, so a
     * duplicate event, a retry, or two staff actions in quick succession
     * cannot buzz the same phone twice. This is what lets everything upstream
     * be at-least-once — the cheap, reliable delivery guarantee — instead of
     * chasing exactly-once, which is famously hard in distributed systems.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyFrontOfQueue(Long queueId) {
        entryRepository
                .findFirstByQueueIdAndStatusOrderByQueueOrderAtAscIdAsc(queueId, EntryStatus.WAITING)
                .filter(entry -> entry.getNextNotifiedAt() == null)
                .ifPresent(entry -> {
                    entry.setNextNotifiedAt(Instant.now()); // stamp BEFORE sending
                    deliver(entry, new PushMessage(
                            "You're next!",
                            "Head over now — you're at the front of the line.",
                            ticketUrl(entry)));
                });
    }

    /** "You're up" for the customer who was just served. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyServed(String entryToken) {
        entryRepository.findByEntryToken(entryToken).ifPresent(entry ->
                deliver(entry, new PushMessage(
                        "It's your turn",
                        "You're being served now.",
                        ticketUrl(entry))));
    }

    /**
     * Sprint 5: send an arbitrary message to one customer (leave-now alerts,
     * traffic corrections). Public so the Leave-Now engine can reuse the
     * whole delivery stack — subscriptions, circuit breaker, dead-endpoint
     * cleanup — instead of reimplementing it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyCustom(QueueEntry entry, String title, String body) {
        deliver(entry, new PushMessage(title, body, ticketUrl(entry)));
    }

    /**
     * V9: route by the customer's stated preference.
     *
     * The routing lives HERE, in the "who to tell" layer, and not inside any
     * channel — a channel's job is to deliver, not to decide whether it
     * should have been asked. That separation is what let SMS arrive as two
     * new files plus this method, with the Leave-Now engine, the circuit
     * breaker and every caller of notifyCustom() completely untouched.
     */
    private void deliver(QueueEntry entry, PushMessage message) {
        switch (entry.getNotifyChannel()) {
            case SMS -> deliverBySms(entry, message);
            case PUSH -> deliverByPush(entry, message);
            case NONE -> log.debug("Entry {} opted out of notifications", entry.getId());
        }
    }

    /**
     * SMS with a Web Push safety net: if no provider is configured, we fall
     * back rather than silently dropping a time-critical alert. The customer
     * asked to be told when to leave; which wire that arrives on matters far
     * less than that it arrives.
     */
    private void deliverBySms(QueueEntry entry, PushMessage message) {
        SmsSender.Result result = smsSender.send(entry.getPhoneNumber(), message);
        switch (result) {
            case SENT -> log.debug("SMS sent for entry {}", entry.getId());
            case NOT_CONFIGURED -> {
                log.debug("No SMS provider; falling back to Web Push for entry {}", entry.getId());
                deliverByPush(entry, message);
            }
            default -> log.info("SMS for entry {} not delivered: {}", entry.getId(), result);
        }
    }

    private void deliverByPush(QueueEntry entry, PushMessage message) {
        var subscriptions = subscriptionRepository.findByEntryId(entry.getId());
        if (subscriptions.isEmpty()) {
            return; // this customer never opted in — perfectly normal (FR-17)
        }
        for (var subscription : subscriptions) {
            NotificationChannel.DeliveryResult result = channel.send(subscription, message);
            switch (result) {
                case EXPIRED ->
                    // The browser told us this subscription is dead. Deleting
                    // it stops us paying for a request that can never succeed.
                        subscriptionRepository.delete(subscription);
                case DELIVERED -> log.debug("Notified entry {}", entry.getId());
                default -> log.info("Notification for entry {} not delivered: {}", entry.getId(), result);
            }
        }
    }

    private String ticketUrl(QueueEntry entry) {
        return publicBaseUrl + "/t/" + entry.getEntryToken();
    }
}
