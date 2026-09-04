package com.nowserving.service;

import com.nowserving.entity.*;
import com.nowserving.notification.NotificationDispatcher;
import com.nowserving.repository.LeaveNowAlertRepository;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.travel.GuardedTravelTimeService;
import com.nowserving.travel.TravelTimeProvider;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * ⭐ The Leave-Now engine (FR-12 … FR-14) — the PRD's "money demo".
 *
 * THE IDEA IN ONE LINE:
 *     leave-by = when it's your turn − how long it takes to get here − a buffer
 * and when "now" reaches leave-by, we tell you to head over.
 *
 * ASYMMETRIC ESTIMATION (§3.8.3) is the judgement call that makes this
 * trustworthy. Being 5 minutes early costs a customer almost nothing. Being 5
 * minutes late can cost them their turn. So every uncertain number is nudged
 * in the "leave sooner" direction: we inflate the journey, we add a safety
 * buffer, and we round up. A tool that occasionally makes you wait is
 * annoying; one that occasionally makes you miss your slot gets deleted.
 */
@Service
@RequiredArgsConstructor
public class LeaveNowService {

    private static final Logger log = LoggerFactory.getLogger(LeaveNowService.class);

    /** Inflate every journey — see "asymmetric estimation" above. */
    private static final double PESSIMISM_FACTOR = 1.15;

    /** Only correct ourselves if reality moved by more than this. */
    private static final int MATERIAL_CHANGE_MINUTES = 10;

    /** FR-14 / §1.8: at most one correction per entry per 10 minutes. */
    private static final Duration CORRECTION_COOLDOWN = Duration.ofMinutes(10);

    private final QueueEntryRepository entryRepository;
    private final LeaveNowAlertRepository alertRepository;
    private final CustomerLocationService locationService;
    private final GuardedTravelTimeService travelTimeService;
    private final NotificationDispatcher notificationDispatcher;
    private final WaitEstimator waitEstimator;
    private final Clock clock;

    /** What the customer's ticket page shows about their journey. */
    public record LeaveNowStatus(
            boolean sharingLocation,
            Integer travelMinutes,
            boolean trafficAware,
            Integer trafficDelayMinutes,
            Instant leaveBy,
            boolean shouldLeaveNow,
            boolean enRoute,
            /** When we think their turn arrives — shown to EVERY waiting
             *  customer, location shared or not. The hero number. */
            Instant expectedTurnAt,
            /** Width of the honest window: "7:40–7:47", not a fake-precise 7:41. */
            int turnWindowMinutes) {

        static LeaveNowStatus notSharing(boolean enRoute, Instant expectedTurnAt, int windowMinutes) {
            return new LeaveNowStatus(false, null, false, null, null, false, enRoute,
                    expectedTurnAt, windowMinutes);
        }
    }

    /**
     * Evaluate one waiting customer. Returns empty when there's nothing to
     * say — no location shared, no venue configured, already served.
     *
     * FR-17 in practice: everything here is an ENHANCEMENT. A customer who
     * never shares a location still gets their position and their "you're
     * next" notification; they simply skip this path entirely.
     */
    @Transactional(readOnly = true)
    public Optional<LeaveNowStatus> statusFor(QueueEntry entry) {
        if (entry.getStatus() != EntryStatus.WAITING) {
            return Optional.empty();
        }
        Queue queue = entry.getQueue();
        boolean enRoute = entry.getEnRouteAt() != null;
        // Computed for everyone: the expected-turn window needs no location.
        Instant target = targetServeTime(entry);
        int window = Math.max(queue.getSafetyBufferMinutes(), 5);

        if (!queue.hasVenueLocation()) {
            return Optional.of(LeaveNowStatus.notSharing(enRoute, target, window));
        }
        var location = locationService.currentLocation(entry.getId());
        if (location.isEmpty()) {
            return Optional.of(LeaveNowStatus.notSharing(enRoute, target, window));
        }

        TravelTimeProvider.TravelEstimate travel = travelTimeService.estimate(
                location.get().latitude(), location.get().longitude(),
                queue.getVenueLatitude(), queue.getVenueLongitude());

        int paddedTravel = padded(travel.minutes());
        Instant leaveBy = target
                .minus(Duration.ofMinutes(paddedTravel))
                .minus(Duration.ofMinutes(queue.getSafetyBufferMinutes()));

        return Optional.of(new LeaveNowStatus(
                true, paddedTravel, travel.trafficAware(), travel.trafficDelayMinutes(),
                leaveBy, !clock.instant().isBefore(leaveBy), enRoute,
                target, window));
    }

    /**
     * FR-13/FR-14. Decides whether this customer needs to hear from us, and
     * sends at most one thing.
     *
     * The three guards are the difference between a useful feature and a
     * notification firehose:
     *   - never send LEAVE_NOW twice;
     *   - only send a CORRECTION if the journey changed MATERIALLY;
     *   - never send corrections more often than the cooldown.
     */
    @Transactional
    public void evaluateAndAlert(Long entryId) {
        QueueEntry entry = entryRepository.findById(entryId).orElse(null);
        if (entry == null || entry.getStatus() != EntryStatus.WAITING) {
            return;
        }
        Optional<LeaveNowStatus> maybeStatus = statusFor(entry);
        if (maybeStatus.isEmpty() || !maybeStatus.get().sharingLocation()) {
            return;
        }
        LeaveNowStatus status = maybeStatus.get();
        List<LeaveNowAlert> history = alertRepository.findByEntryIdOrderBySentAtDesc(entry.getId());

        boolean alreadyToldToLeave = history.stream()
                .anyMatch(a -> a.getAlertType() == LeaveNowAlert.AlertType.LEAVE_NOW);

        if (!status.shouldLeaveNow()) {
            return; // not yet — the scheduler will look again
        }

        if (!alreadyToldToLeave) {
            sendLeaveNow(entry, status);
            return;
        }

        // Already told them. Is a correction warranted (FR-14)?
        LeaveNowAlert mostRecent = history.get(0);
        Instant now = clock.instant();
        boolean cooledDown = mostRecent.getSentAt().plus(CORRECTION_COOLDOWN).isBefore(now);
        boolean materiallyDifferent =
                Math.abs(status.travelMinutes() - mostRecent.getComputedTravelMinutes())
                        >= MATERIAL_CHANGE_MINUTES;

        if (cooledDown && materiallyDifferent) {
            alertRepository.save(new LeaveNowAlert(
                    entry, now, LeaveNowAlert.AlertType.CORRECTION, status.travelMinutes()));
            notificationDispatcher.notifyCustom(entry,
                    "Traffic update",
                    "Your journey is now about %d minutes. Adjust if you can."
                            .formatted(status.travelMinutes()));
        }
    }

    private void sendLeaveNow(QueueEntry entry, LeaveNowStatus status) {
        alertRepository.save(new LeaveNowAlert(entry, clock.instant(),
                LeaveNowAlert.AlertType.LEAVE_NOW, status.travelMinutes()));

        // The copy is deliberately honest about being an estimate — §1.8 warns
        // that over-confident advice which makes someone miss their turn does
        // more damage than no advice at all.
        notificationDispatcher.notifyCustom(entry,
                "Time to head over",
                "About %d minutes away in current conditions — leave now to arrive in time."
                        .formatted(status.travelMinutes()));
        log.debug("Sent leave-now for entry {}", entry.getId());
    }

    /**
     * The shared abstraction from Sprint 4. A redeemed reservation carries its
     * booked time; a walk-in gets "now + however long the line is". Either
     * way this method answers one question and the caller never asks which
     * door the customer came through.
     */
    private Instant targetServeTime(QueueEntry entry) {
        if (entry.getTargetServeTime() != null) {
            return entry.getTargetServeTime();
        }
        long ahead = entryRepository.countWaitingAhead(
                entry.getQueue().getId(), entry.getQueueOrderAt(), entry.getId());
        return clock.instant().plus(Duration.ofMinutes(
                waitEstimator.estimateMinutes(entry.getQueue(), (int) ahead)));
    }

    private int padded(int travelMinutes) {
        return (int) Math.ceil(travelMinutes * PESSIMISM_FACTOR);
    }
}
