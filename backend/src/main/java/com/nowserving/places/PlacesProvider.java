package com.nowserving.places;

import java.util.List;

/**
 * A source of "what restaurants exist near here?" — separate from
 * NowServing's own tenant data on purpose. {@link com.nowserving.repository.QueueRepository}
 * answers "who has a queue"; this answers "what's physically around here",
 * which is a different question with a different (and much less reliable)
 * data source behind it.
 *
 * Same port/adapter shape as {@link com.nowserving.travel.TravelTimeProvider}:
 * one interface, a real adapter that can fail or be unconfigured, and a fake
 * for tests. Swapping OSM for a second source later (or adding one) is a new
 * adapter, not a rewrite of the discovery service.
 *
 * Implementations MUST NOT throw for ordinary failures (timeout, outage, rate
 * limit) — an empty list is the correct answer to "I don't know", and
 * discovery must keep working with only NowServing's own venues when the
 * external source is unavailable.
 */
public interface PlacesProvider {

    List<NearbyPlace> nearbyRestaurants(double lat, double lon, int radiusMeters);
}
