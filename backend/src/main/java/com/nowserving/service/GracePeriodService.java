package com.nowserving.service;

import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.Queue;
import com.nowserving.entity.QueueEntry;
import com.nowserving.exception.BadRequestException;
import com.nowserving.exception.NotFoundException;
import com.nowserving.notification.NotificationDispatcher;
import com.nowserving.repository.QueueEntryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * FR-16 — "If a customer's turn arrives before they do, business-configured
 * policy applies: hold the spot for a grace period, then bump back N places
 * or mark no-show."
 *
 * This is the humane counterpart to the Leave-Now alert. We told someone when
 * to set off; if traffic betrayed them, punishing them instantly would make
 * the whole feature feel like a trap. The grace period is the apology built
 * into the design.
 */
@Service
@RequiredArgsConstructor
public class GracePeriodService {

    private static final Logger log = LoggerFactory.getLogger(GracePeriodService.class);

    private final QueueEntryRepository entryRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * The customer asks for their spot to be held ("I'm 5 minutes away").
     * PRD §3.4 lists this as POST /entries/{id}/hold, authorised by the entry
     * token — the same capability model as everything else customer-facing.
     */
    @Transactional
    public Instant requestHold(String entryToken) {
        QueueEntry entry = entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        if (entry.getStatus() != EntryStatus.WAITING) {
            throw new BadRequestException("This ticket is no longer waiting");
        }
        Queue queue = entry.getQueue();
        Instant expiresAt = clock.instant().plus(Duration.ofMinutes(queue.getGraceMinutes()));
        entry.setGraceExpiresAt(expiresAt);
        // Asking for a hold implies you're travelling.
        if (entry.getEnRouteAt() == null) {
            entry.setEnRouteAt(clock.instant());
        }
        eventPublisher.publishEvent(QueueChangedEvent.of(queue.getId()));
        return expiresAt;
    }

    /** FR-15: "I'm on my way" — staff see it on the dashboard. */
    @Transactional
    public void markEnRoute(String entryToken) {
        QueueEntry entry = entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        if (entry.getStatus() != EntryStatus.WAITING) {
            throw new BadRequestException("This ticket is no longer waiting");
        }
        if (entry.getEnRouteAt() == null) {
            entry.setEnRouteAt(clock.instant());
            eventPublisher.publishEvent(QueueChangedEvent.of(entry.getQueue().getId()));
        }
    }

    /**
     * Applies the configured policy to everyone whose hold has run out.
     *
     * bumpPlaces = 0 means "mark them a no-show"; anything higher means "move
     * them back that many places and let them keep waiting". Bumping is done
     * by moving their joined_at behind the Nth person after them — position is
     * derived from joined_at ordering, so changing that one field is the whole
     * operation. No positions to renumber, nothing to get out of sync; the
     * Sprint 1 decision to derive position keeps paying off.
     */
    @Transactional
    public void applyExpiredGracePeriods() {
        Instant now = clock.instant();
        List<QueueEntry> expired =
                entryRepository.findByStatusAndGraceExpiresAtBefore(EntryStatus.WAITING, now);

        for (QueueEntry entry : expired) {
            Queue queue = entry.getQueue();
            entry.setGraceExpiresAt(null); // the hold is spent either way

            if (queue.getBumpPlaces() <= 0) {
                entry.transitionTo(EntryStatus.NO_SHOW);
                notificationDispatcher.notifyCustom(entry, "We had to move on",
                        "Your held spot expired. Scan the QR again to rejoin.");
                log.info("Entry {} marked NO_SHOW after grace expiry", entry.getId());
            } else {
                bumpBack(entry, queue.getBumpPlaces());
                notificationDispatcher.notifyCustom(entry, "Moved back a few places",
                        "You weren't here in time, so we've moved you back %d places — you're still in line."
                                .formatted(queue.getBumpPlaces()));
                log.info("Entry {} bumped back {} places", entry.getId(), queue.getBumpPlaces());
            }
            eventPublisher.publishEvent(QueueChangedEvent.of(queue.getId()));
        }
    }

    private void bumpBack(QueueEntry entry, int places) {
        List<QueueEntry> behind = entryRepository
                .findByQueueIdAndStatusAndQueueOrderAtAfterOrderByQueueOrderAtAscIdAsc(
                        entry.getQueue().getId(), EntryStatus.WAITING, entry.getQueueOrderAt());

        if (behind.isEmpty()) {
            return; // nobody to fall behind — they're still first
        }
        // Land just after the Nth person behind them (or the last, if fewer).
        QueueEntry newPredecessor = behind.get(Math.min(places, behind.size()) - 1);
        // Only the SORT key moves. joined_at still says when they really
        // arrived, so "waited 40 minutes" stays true after a bump.
        entry.setQueueOrderAt(newPredecessor.getQueueOrderAt().plusMillis(1));
    }
}
