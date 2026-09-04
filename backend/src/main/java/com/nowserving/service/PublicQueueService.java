package com.nowserving.service;

import com.nowserving.dto.PublicDtos.JoinRequest;
import com.nowserving.dto.PublicDtos.JoinResponse;
import com.nowserving.dto.PublicDtos.PositionResponse;
import com.nowserving.dto.PublicDtos.PushSubscriptionRequest;
import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.NotifyChannel;
import com.nowserving.entity.PushSubscription;
import com.nowserving.entity.Queue;
import com.nowserving.entity.QueueEntry;
import com.nowserving.entity.QueueStatus;
import com.nowserving.exception.BadRequestException;
import com.nowserving.exception.LocationRequiredException;
import com.nowserving.exception.NotFoundException;
import com.nowserving.travel.HaversineTravelTimeProvider;
import com.nowserving.repository.PushSubscriptionRepository;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.repository.QueueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer-side operations (NS-5 join, NS-6 position). No authentication —
 * a customer's "identity" is possession of a token:
 *   join_token  (public, on the poster)  -> lets you join a queue
 *   entry_token (private, yours alone)   -> lets you check YOUR ticket
 */
@Service
@RequiredArgsConstructor
public class PublicQueueService {

    /** Same constant as DiscoveryService — the venue policy is stated in
     *  miles, every distance we compute is in km. */
    private static final double KM_PER_MILE = 1.609344;

    private final QueueRepository queueRepository;
    private final QueueEntryRepository entryRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final WaitEstimator waitEstimator;
    private final PushSubscriptionRepository subscriptionRepository;

    @Transactional
    public JoinResponse join(String joinToken, JoinRequest request, String idempotencyKey) {
        Queue queue = queueRepository.findByJoinToken(joinToken)
                .orElseThrow(() -> new NotFoundException("Queue not found"));

        if (queue.getStatus() == QueueStatus.CLOSED) {
            throw new BadRequestException("This queue is currently closed and not accepting new customers");
        }

        // V9: the owner's door policy, checked BEFORE anything is written.
        enforceJoinPolicy(queue, request);

        // Same retry-safety as reservations: a double-tap or a lost response
        // returns the ORIGINAL ticket rather than minting a second one.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            var existing = entryRepository.findByQueueIdAndIdempotencyKey(queue.getId(), idempotencyKey);
            if (existing.isPresent()) {
                QueueEntry e = existing.get();
                int pos = positionOf(e);
                return new JoinResponse(e.getEntryToken(), queue.getName(),
                        pos, pos - 1, estimateMinutes(queue, pos - 1));
            }
        }

        QueueEntry entry = new QueueEntry(
                queue,
                request.customerName().trim(),
                request.partySize() != null ? request.partySize() : 1);
        entry.setIdempotencyKey(idempotencyKey != null && !idempotencyKey.isBlank() ? idempotencyKey : null);
        applyNotificationPreference(entry, request);
        // save + flush happens within this transaction; @PrePersist stamps
        // joinedAt and generates the entry_token.
        entry = entryRepository.save(entry);

        int position = positionOf(entry);

        // The shared abstraction (Sprint 4): a walk-in's target serve time is
        // "now + however long the line is". A reservation's is its booked
        // slot. Downstream code reads this one field and never asks which
        // door the customer came through.
        entry.setTargetServeTime(
                entry.getJoinedAt().plusSeconds(60L * estimateMinutes(queue, position - 1)));

        // Staff dashboards learn about the newcomer the instant this commits.
        eventPublisher.publishEvent(QueueChangedEvent.of(queue.getId()));

        return new JoinResponse(
                entry.getEntryToken(),
                queue.getName(),
                position,
                position - 1,
                estimateMinutes(queue, position - 1));
    }

    /**
     * V9 — "may this person join, from where they are?"
     *
     * Three doors, and the owner controls each independently:
     *
     *  ON-SITE      -> allowed unless the owner closed that door. This is the
     *                  DEFAULT (see JoinRequest.remote): a bare /j/{token},
     *                  which is what every printed QR code contains. Standing
     *                  at the counter with the code in front of you needs no
     *                  distance check; that's what the code IS.
     *  REMOTE, OFF  -> refused, with copy that tells them the useful thing
     *                  (come and scan it) rather than just "no".
     *  REMOTE, ON   -> allowed within the owner's radius. We need a location
     *                  to check, so a missing one is 428 ("ask and retry"),
     *                  not 400 ("you did something wrong").
     *
     * Note the deliberate hole: a venue with no coordinates configured cannot
     * be measured from, so it lets everyone in. Refusing instead would mean a
     * brand-new restaurant silently rejects every remote customer until it
     * notices an unrelated map setting — a far worse failure than the one
     * we're preventing. Degrade toward working, and make the fix discoverable
     * in the owner panel.
     */
    private void enforceJoinPolicy(Queue queue, JoinRequest request) {
        if (!request.isRemoteJoin()) {
            if (!queue.isAllowQrJoin()) {
                throw new BadRequestException(
                        "This venue isn't taking QR sign-ups right now — please ask a member of staff");
            }
            return;
        }

        if (!queue.isAllowRemoteJoin()) {
            throw new BadRequestException(
                    "%s only takes walk-ins — scan the QR code at the door to join the line"
                            .formatted(queue.getBusiness().getName()));
        }

        if (!queue.hasVenueLocation()) {
            return; // nothing to measure against; see the note above
        }

        if (request.latitude() == null || request.longitude() == null) {
            throw new LocationRequiredException(
                    "%s allows remote joining within %d miles. Share your approximate location to confirm eligibility."
                            .formatted(queue.getBusiness().getName(), queue.getMaxRemoteJoinMiles()));
        }

        double miles = HaversineTravelTimeProvider.distanceKm(
                request.latitude(), request.longitude(),
                queue.getVenueLatitude(), queue.getVenueLongitude()) / KM_PER_MILE;

        if (miles > queue.getMaxRemoteJoinMiles()) {
            // Tell them the actual numbers. "Too far" invites a retry; "you're
            // 62 miles away, the limit is 25" ends the question honestly.
            throw new BadRequestException(
                    "You're about %.0f miles away and %s accepts remote joins within %d miles. You can still join by scanning the QR code when you arrive."
                            .formatted(miles, queue.getBusiness().getName(), queue.getMaxRemoteJoinMiles()));
        }
        // Location used, decision made, value discarded. It is NOT stored:
        // eligibility is a one-off question, whereas Leave-Now tracking is an
        // ongoing relationship the customer opts into separately, later.
    }

    /**
     * V9 — revise the notification choice from the ticket page.
     *
     * Authorised, like everything else customer-facing, by possession of the
     * entry token. Reuses the exact same normalisation as the join path so
     * the two entry points cannot drift into disagreeing about what "SMS
     * without a number" means.
     */
    @Transactional
    public void updateNotifyPreference(String entryToken, NotifyChannel channel, String phoneNumber) {
        QueueEntry entry = entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        applyNotificationPreference(entry,
                new JoinRequest(entry.getCustomerName(), entry.getPartySize(),
                        null, null, null, phoneNumber, channel));
    }

    /**
     * V9 — the customer's notification choice, captured at join time.
     *
     * A phone number is only kept when SMS is actually the chosen channel.
     * Storing one "just in case" would mean holding identifying data we have
     * no use for, which is the kind of thing that is free to collect and
     * expensive to have collected.
     */
    private void applyNotificationPreference(QueueEntry entry, JoinRequest request) {
        NotifyChannel channel = request.notifyChannel() != null
                ? request.notifyChannel()
                : NotifyChannel.PUSH;

        boolean hasPhone = request.phoneNumber() != null && !request.phoneNumber().isBlank();
        // Asking for SMS without leaving a number isn't an error worth
        // failing the join over — it just means Web Push, which is the
        // default anyway.
        if (channel == NotifyChannel.SMS && !hasPhone) {
            channel = NotifyChannel.PUSH;
        }

        entry.setNotifyChannel(channel);
        entry.setPhoneNumber(channel == NotifyChannel.SMS ? request.phoneNumber().trim() : null);
    }

    /**
     * FR-8: the customer opts in to notifications for THIS ticket.
     *
     * Authorisation is possession of the entry token — the same capability
     * model as reading your position. Upsert rather than insert, so a page
     * reload doesn't register the same device four times and buzz it four
     * times.
     */
    @Transactional
    public void savePushSubscription(String entryToken, PushSubscriptionRequest request) {
        QueueEntry entry = entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        subscriptionRepository.findByEntryIdAndEndpoint(entry.getId(), request.endpoint())
                .ifPresentOrElse(existing -> {
                    // Browsers rotate these keys; keep the row, refresh the keys.
                    existing.setP256dh(request.keys().p256dh());
                    existing.setAuth(request.keys().auth());
                }, () -> subscriptionRepository.save(new PushSubscription(
                        entry, request.endpoint(), request.keys().p256dh(), request.keys().auth())));
    }

    @Transactional(readOnly = true)
    public PositionResponse position(String entryToken) {
        QueueEntry entry = entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        // Once served, position is meaningless — but the STORY isn't over:
        // servedAt + waitedMinutes let the ticket page close the loop
        // ("served at 2:42, waited 18 min") instead of stopping abruptly.
        String businessName = entry.getQueue().getBusiness().getName();

        if (entry.getStatus() != EntryStatus.WAITING) {
            Long waited = entry.getServedAt() == null ? null
                    : java.time.Duration.between(entry.getJoinedAt(), entry.getServedAt()).toMinutes();
            return new PositionResponse(entry.getStatus(), null, null, null,
                    entry.getServedAt(), waited, businessName);
        }

        int position = positionOf(entry);
        return new PositionResponse(
                EntryStatus.WAITING,
                position,
                position - 1,
                estimateMinutes(entry.getQueue(), position - 1),
                null, null, businessName);
    }

    /** P1: the customer gives up and leaves the line — their choice, so it is
     *  LEFT, not NO_SHOW (that word is staff's judgement, and the analytics
     *  treat them differently). */
    @Transactional
    public void leave(String entryToken) {
        QueueEntry entry = entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        entry.transitionTo(EntryStatus.LEFT); // 400 unless currently WAITING
        eventPublisher.publishEvent(QueueChangedEvent.of(entry.getQueue().getId()));
    }

    /** V8: 1–5 stars + optional comment, after service. Latest submission wins
     *  (a customer who changes their mind overwrites — that's the truthful
     *  reading, not an audit problem). Saved WITH the customer's name already
     *  on the row, so the owner's history is the feedback dataset. */
    @Transactional
    public void feedback(String entryToken, int stars, String comment) {
        QueueEntry entry = entryRepository.findByEntryToken(entryToken)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        if (entry.getStatus() == EntryStatus.WAITING) {
            throw new BadRequestException("You can leave feedback once you have been served");
        }
        entry.setRatingStars(stars);
        entry.setFeedbackComment(comment != null && !comment.isBlank() ? comment.trim() : null);
        entry.setFeedbackAt(java.time.Instant.now());
    }

    /**
     * Position is DERIVED on every read (see QueueEntry's class comment):
     * "people who joined before me and are still WAITING" + 1. When someone
     * ahead is served, my next read simply counts one fewer — nothing to
     * update, nothing to get out of sync.
     */
    private int positionOf(QueueEntry entry) {
        long ahead = entryRepository.countWaitingAhead(
                entry.getQueue().getId(), entry.getQueueOrderAt(), entry.getId());
        return (int) ahead + 1;
    }

    /**
     * Sprint 3: the formula moved to {@link WaitEstimator}, which uses the
     * measured rolling median once enough real services have been observed
     * and falls back to the owner's configured guess before that. It used to
     * be duplicated here and in QueueEventsBroadcaster — two copies that
     * could disagree about the number shown to the same customer.
     */
    private int estimateMinutes(Queue queue, int peopleAhead) {
        return waitEstimator.estimateMinutes(queue, peopleAhead);
    }
}
