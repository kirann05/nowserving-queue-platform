package com.nowserving.service;

import com.nowserving.entity.Queue;
import com.nowserving.repository.ServiceSampleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * "How long until it's my turn?" — in ONE place.
 *
 * Before Sprint 3 this formula was copy-pasted into PublicQueueService and
 * QueueEventsBroadcaster, which meant the number a customer saw on a poll
 * could drift from the number pushed over WebSocket. One estimator, one
 * answer.
 *
 * Two regimes:
 *  - COLD START: fewer than MIN_SAMPLES real observations, so fall back to
 *    the owner's configured guess. The PRD (§1.8) is explicit that a
 *    confidently wrong estimate damages trust more than no estimate; with
 *    two data points a median is noise wearing a lab coat.
 *  - WARM: median of recent measured intervals. Note we do NOT divide by
 *    stationCount here — the measured interval between advances already
 *    reflects however many stations are running.
 */
@Service
@RequiredArgsConstructor
public class WaitEstimator {

    /** Below this many samples, trust the owner's configured default instead. */
    static final int MIN_SAMPLES = 5;

    /** Only recent history matters: a lunch rush shouldn't skew the evening. */
    static final int SAMPLE_WINDOW = 20;

    /** Recomputing a median per poll per customer is wasteful; 30s is plenty fresh. */
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final ServiceSampleRepository sampleRepository;
    private final StringRedisTemplate redis;

    @Value("${app.estimates.enabled:true}")
    private boolean measuredEstimatesEnabled;

    /**
     * @param peopleAhead how many WAITING customers are in front of this one
     * @return whole minutes, rounded UP (telling someone "22" when the maths
     *         says 22.5 under-promises in the wrong direction)
     */
    public int estimateMinutes(Queue queue, int peopleAhead) {
        if (peopleAhead <= 0) {
            return 0;
        }
        Double medianSeconds = measuredEstimatesEnabled ? medianForQueue(queue.getId()) : null;

        if (medianSeconds == null) {
            // Cold start — the Sprint 1 formula, unchanged.
            return (int) Math.ceil(
                    (double) (peopleAhead * queue.getDefaultServiceMinutes()) / queue.getStationCount());
        }
        return (int) Math.ceil(peopleAhead * medianSeconds / 60.0);
    }

    /** True once we're using measured data — the UI says "based on today". */
    public boolean hasMeasuredData(Long queueId) {
        return measuredEstimatesEnabled && medianForQueue(queueId) != null;
    }

    /**
     * Median with a short-lived Redis cache in front (the PRD's "estimate
     * stored in Redis"). A cache miss is never fatal: if Redis is unhappy we
     * fall through to the database rather than failing the request.
     */
    private Double medianForQueue(Long queueId) {
        String cacheKey = "estimate:median:" + queueId;
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return cached.isEmpty() ? null : Double.valueOf(cached); // "" = known cold start
            }
        } catch (Exception ignored) {
            // Redis down — carry on and read from Postgres.
        }

        Double median = null;
        if (sampleRepository.countByQueueId(queueId) >= MIN_SAMPLES) {
            median = sampleRepository.medianRecentDurationSeconds(queueId, SAMPLE_WINDOW);
        }

        try {
            redis.opsForValue().set(cacheKey, median == null ? "" : median.toString(), CACHE_TTL);
        } catch (Exception ignored) {
            // Caching is an optimisation, never a requirement.
        }
        return median;
    }
}
