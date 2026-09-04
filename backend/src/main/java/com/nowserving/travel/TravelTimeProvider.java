package com.nowserving.travel;

import java.util.Optional;

/**
 * "How many minutes to drive from A to B, right now, in current traffic?"
 *
 * THE PORT THE PRD INSISTS ON (§4.2): *"Wrap the maps provider behind a
 * TravelTimeProvider interface from day one … it lets you swap providers, and
 * lets you inject a fake provider in tests so your test suite never makes a
 * paid API call. That last point is not optional."*
 *
 * Every routing API on earth bills per request. A test suite that called one
 * would generate an invoice every time CI ran — and would fail whenever the
 * vendor had a bad afternoon. So the domain depends on this interface, and
 * the paid thing is one swappable adapter among several.
 */
public interface TravelTimeProvider {

    /**
     * @return the estimate, or empty when this provider cannot answer (no
     *         route, quota exhausted, provider down). Empty is a normal
     *         outcome, not an exception — callers fall back.
     */
    Optional<TravelEstimate> travelMinutes(double fromLat, double fromLon,
                                           double toLat, double toLon);

    /**
     * @param minutes             door-to-door driving time
     * @param trafficAware        true if real-time conditions were used. The UI
     *                            is honest about this: "≈25 min" reads very
     *                            differently from "25 min in current traffic".
     * @param trafficDelayMinutes how much of that is congestion (0 when the
     *                            provider can't know — e.g. haversine).
     */
    record TravelEstimate(int minutes, boolean trafficAware, int trafficDelayMinutes) {}

    /** For logs and metrics — which adapter actually answered. */
    String name();
}
