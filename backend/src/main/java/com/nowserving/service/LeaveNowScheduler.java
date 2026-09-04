package com.nowserving.service;

import com.nowserving.entity.EntryStatus;
import com.nowserving.entity.QueueEntry;
import com.nowserving.repository.QueueEntryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The clock that drives the Leave-Now engine, with TIERED POLLING (§3.8.4).
 *
 * The naive version checks every waiting customer every minute. At 500
 * customers that's 500 routing calls a minute — and routing calls cost money.
 * But most of those people are 90 minutes from their turn and nothing
 * interesting can possibly have changed.
 *
 * So we tier by urgency:
 *   more than HORIZON away  -> ignore entirely (nothing to say yet)
 *   within HORIZON          -> evaluate each run
 * Combined with the geo-grid cache and the proximity pre-filter in
 * GuardedTravelTimeService, the number of paid calls stays small and roughly
 * proportional to the number of people who are actually about to travel.
 *
 * Multi-instance: @SchedulerLock (ShedLock) means exactly ONE instance runs
 * each sweep, however many replicas are deployed. Before it, every replica
 * swept every minute — never incorrect, because alerts are idempotent, but it
 * doubled the paid routing calls for nothing. See SchedulerLockConfig.
 */
@Component
@RequiredArgsConstructor
public class LeaveNowScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaveNowScheduler.class);

    /** Only think about people whose turn is within this window. */
    private static final Duration HORIZON = Duration.ofMinutes(90);

    private final QueueEntryRepository entryRepository;
    private final LeaveNowService leaveNowService;
    private final GracePeriodService gracePeriodService;
    private final Clock clock;

    @Value("${app.travel.scheduler-enabled:true}")
    private boolean enabled;

    /**
     * Every minute. Frequent enough that "leave now" is timely, infrequent
     * enough to be cheap — the alert itself is only accurate to a minute or
     * two anyway, so a tighter loop would buy nothing.
     */
    @Scheduled(fixedDelayString = "${app.travel.scheduler-interval-ms:60000}")
    @SchedulerLock(name = "leaveNowSweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void evaluateUpcomingJourneys() {
        if (!enabled) return;
        try {
            Instant horizon = clock.instant().plus(HORIZON);
            List<QueueEntry> candidates =
                    entryRepository.findLeaveNowCandidates(EntryStatus.WAITING, horizon);

            for (QueueEntry entry : candidates) {
                try {
                    leaveNowService.evaluateAndAlert(entry.getId());
                } catch (Exception e) {
                    // One bad entry must not stop the other 499.
                    log.warn("Leave-now evaluation failed for entry {}", entry.getId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Leave-now sweep failed", e);
        }
    }

    /** FR-16: apply the hold-then-bump policy to anyone whose grace ran out. */
    @Scheduled(fixedDelayString = "${app.travel.grace-interval-ms:30000}")
    @SchedulerLock(name = "gracePeriodSweep", lockAtMostFor = "PT2M", lockAtLeastFor = "PT10S")
    public void expireGracePeriods() {
        if (!enabled) return;
        try {
            gracePeriodService.applyExpiredGracePeriods();
        } catch (Exception e) {
            log.error("Grace-period sweep failed", e);
        }
    }
}
