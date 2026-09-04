package com.nowserving.notification;

import com.nowserving.entity.EntryStatus;
import com.nowserving.service.QueueChangedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The second consumer of {@link QueueChangedEvent} — and the payoff of the
 * event-driven design from Sprint 2.
 *
 * Adding notifications to this product changed EXACTLY ZERO lines in
 * QueueService. The queue logic still doesn't know who listens: the WebSocket
 * broadcaster and this class both react to the same fact. That property has a
 * name — LOOSE COUPLING — and it is the whole argument for events over direct
 * method calls.
 *
 *                                  ┌──> QueueEventsBroadcaster  (Sprint 2)
 *   QueueService ──> QueueChangedEvent ─┤
 *                                  └──> NotificationListener    (Sprint 3)
 *
 * Two annotations doing heavy lifting:
 *  - @TransactionalEventListener: fires only AFTER the database commit, so we
 *    never tell someone "you're next" for a change that then rolled back.
 *  - @Async on a dedicated pool: the staff member's "Next" request returns
 *    immediately; notification work happens on separate threads and cannot
 *    slow the queue down. See AsyncConfig for why that pool is separate.
 */
@Component
@RequiredArgsConstructor
public class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationDispatcher dispatcher;

    @Async("notificationExecutor")
    @TransactionalEventListener
    public void onQueueChanged(QueueChangedEvent event) {
        try {
            // Whoever just became first in line gets "you're next".
            dispatcher.notifyFrontOfQueue(event.queueId());

            // And the person who just got served gets "it's your turn".
            if (event.affectedEntryToken() != null && event.affectedStatus() == EntryStatus.SERVED) {
                dispatcher.notifyServed(event.affectedEntryToken());
            }
        } catch (Exception e) {
            // Never let a notification problem escape. We're on a background
            // thread after commit — the queue is already correct, and an
            // exception here would only pollute logs and kill the pool thread.
            log.error("Notification handling failed for queue {}", event.queueId(), e);
        }
    }
}
