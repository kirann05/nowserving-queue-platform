package com.nowserving.travel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The free, offline, always-available estimate: straight-line distance
 * divided by an assumed speed.
 *
 * It has two jobs, and the second is the important one:
 *
 *  1. DEFAULT PROVIDER — the whole Leave-Now feature works with zero accounts,
 *     zero keys and zero cost. Less accurate, never unavailable.
 *
 *  2. THE DEGRADED FALLBACK the PRD calls for (§3.8.4): when the paid API's
 *     monthly budget is exhausted, or it times out, we must still answer.
 *     "Roughly right and free" beats "precise and broken".
 *
 * The maths: the haversine formula gives great-circle distance between two
 * points on a sphere. Real roads are longer and slower than a straight line,
 * so we apply a detour factor — a crude but honest correction. Marked
 * trafficAware=false so the UI never implies precision we don't have.
 */
@Component
public class HaversineTravelTimeProvider implements TravelTimeProvider {

    private static final double EARTH_RADIUS_KM = 6371.0;

    /** Roads wander; straight lines don't. ~1.3x is the usual rule of thumb. */
    private static final double ROAD_DETOUR_FACTOR = 1.3;

    private final double averageSpeedKmh;

    public HaversineTravelTimeProvider(@Value("${app.travel.average-speed-kmh:30}") double averageSpeedKmh) {
        this.averageSpeedKmh = averageSpeedKmh;
    }

    @Override
    public Optional<TravelEstimate> travelMinutes(double fromLat, double fromLon,
                                                  double toLat, double toLon) {
        double km = distanceKm(fromLat, fromLon, toLat, toLon) * ROAD_DETOUR_FACTOR;
        int minutes = (int) Math.ceil(km / averageSpeedKmh * 60.0);
        // Never claim zero: even next door means putting your shoes on.
        return Optional.of(new TravelEstimate(Math.max(minutes, 1), false, 0));
    }

    /** Great-circle distance in kilometres. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    @Override
    public String name() {
        return "haversine";
    }
}
