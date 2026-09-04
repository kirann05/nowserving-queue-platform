package com.nowserving.travel;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.YearMonth;
import java.util.Optional;

/**
 * The cost-control layer in front of any paid routing API — PRD §3.8.4's
 * "geo-grid cache + tiered polling + budget guard", and the PRD's #1 HIGH
 * risk ("Maps API surprise bill … no default cap").
 *
 * Four defences, cheapest first. Each one only runs if the previous didn't
 * already answer:
 *
 *  1. PROXIMITY PRE-FILTER — if you're 300 m away, no routing API is going to
 *     tell us anything a straight line can't. Free, and kills a surprising
 *     share of calls.
 *  2. GEO-GRID CACHE — round the origin to a ~1 km cell. Everyone waiting in
 *     the same neighbourhood for the same venue shares one cached answer.
 *     Traffic doesn't change meaningfully in 3 minutes, so that's the TTL.
 *  3. MONTHLY BUDGET COUNTER — a hard ceiling on paid calls per calendar
 *     month, tracked in Redis. Past it we simply stop calling.
 *  4. DEGRADE, NEVER FAIL — whenever the paid path is unavailable, skipped or
 *     exhausted, fall through to the free haversine estimate. The feature
 *     gets less precise; it never breaks and never overspends.
 */
@Service
@RequiredArgsConstructor
public class GuardedTravelTimeService {

    private static final Logger log = LoggerFactory.getLogger(GuardedTravelTimeService.class);

    /** Below this, a routing call cannot earn its cost. */
    private static final double NEAR_ENOUGH_KM = 1.0;

    /** ~0.01 degrees ≈ 1.1 km — the geo-grid cell size. */
    private static final double GRID = 0.01;

    /** Traffic is stable over this window; caching longer would mislead. */
    private static final Duration ETA_CACHE_TTL = Duration.ofMinutes(3);

    private final TomTomTravelTimeProvider paidProvider;
    private final HaversineTravelTimeProvider freeProvider;
    private final StringRedisTemplate redis;

    @Value("${app.travel.monthly-call-budget:1000}")
    private int monthlyCallBudget;

    public TravelTimeProvider.TravelEstimate estimate(double fromLat, double fromLon,
                                                      double toLat, double toLon) {
        double straightLineKm =
                HaversineTravelTimeProvider.distanceKm(fromLat, fromLon, toLat, toLon);

        // (1) Too close to be worth paying for.
        if (straightLineKm <= NEAR_ENOUGH_KM || !paidProvider.isConfigured()) {
            return freeEstimate(fromLat, fromLon, toLat, toLon);
        }

        String cacheKey = "eta:%s:%s".formatted(cell(fromLat, fromLon), cell(toLat, toLon));

        // (2) Someone in the same cell may have already asked.
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                String[] parts = cached.split("\\|");
                return new TravelTimeProvider.TravelEstimate(
                        Integer.parseInt(parts[0]), true,
                        parts.length > 1 ? Integer.parseInt(parts[1]) : 0);
            }
        } catch (Exception ignored) {
            // Redis down — just means we might pay for a call we could have
            // reused. Not a reason to fail.
        }

        // (3) Have we spent this month's allowance?
        if (!withinBudget()) {
            log.warn("Monthly routing budget of {} calls exhausted — using free estimates", monthlyCallBudget);
            return freeEstimate(fromLat, fromLon, toLat, toLon);
        }

        Optional<TravelTimeProvider.TravelEstimate> paid =
                paidProvider.travelMinutes(fromLat, fromLon, toLat, toLon);

        if (paid.isPresent()) {
            try {
                redis.opsForValue().set(cacheKey,
                        paid.get().minutes() + "|" + paid.get().trafficDelayMinutes(), ETA_CACHE_TTL);
            } catch (Exception ignored) {
                // Caching is an optimisation, never a requirement.
            }
            return paid.get();
        }

        // (4) Provider said nothing useful — degrade rather than fail.
        return freeEstimate(fromLat, fromLon, toLat, toLon);
    }

    private TravelTimeProvider.TravelEstimate freeEstimate(double fromLat, double fromLon,
                                                           double toLat, double toLon) {
        return freeProvider.travelMinutes(fromLat, fromLon, toLat, toLon)
                .orElse(new TravelTimeProvider.TravelEstimate(1, false, 0));
    }

    /**
     * Increment-then-check on a per-month Redis counter. INCR is atomic, so
     * several instances share one budget without stepping on each other —
     * exactly the multi-instance reasoning from Sprint 2.
     */
    private boolean withinBudget() {
        String key = "mapsbudget:" + YearMonth.now();
        try {
            Long used = redis.opsForValue().increment(key);
            if (used != null && used == 1L) {
                // First call of the month: make the counter self-expire so old
                // months don't accumulate in Redis forever.
                redis.expire(key, Duration.ofDays(62));
            }
            return used == null || used <= monthlyCallBudget;
        } catch (Exception e) {
            // Can't count spending? Then don't spend. Failing CLOSED is the
            // right default for anything that costs money.
            return false;
        }
    }

    /** Round to a grid cell so nearby origins share a cache entry. */
    private String cell(double lat, double lon) {
        return "%.2f,%.2f".formatted(Math.floor(lat / GRID) * GRID, Math.floor(lon / GRID) * GRID);
    }

    /** For the staff dashboard / metrics: how many paid calls this month. */
    public long callsThisMonth() {
        try {
            String used = redis.opsForValue().get("mapsbudget:" + YearMonth.now());
            return used == null ? 0 : Long.parseLong(used);
        } catch (Exception e) {
            return -1;
        }
    }
}
