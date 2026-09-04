package com.nowserving.support;

import com.nowserving.places.NearbyPlace;
import com.nowserving.places.PlacesProvider;

import java.util.List;

/**
 * A places provider that costs nothing and never touches the real Overpass
 * API. Same reasoning as {@link FakeTravelTimeProvider}: a CI pipeline that
 * hits a shared, unauthenticated public API on every push is a liability,
 * not a test.
 */
public class FakePlacesProvider implements PlacesProvider {

    private volatile List<NearbyPlace> places = List.of();

    public void willReturn(List<NearbyPlace> places) {
        this.places = places;
    }

    /** Simulate Overpass being unreachable — the real adapter's contract is
     *  "never throw", so the fake honours that by returning empty too. */
    public void willBeUnavailable() {
        this.places = List.of();
    }

    @Override
    public List<NearbyPlace> nearbyRestaurants(double lat, double lon, int radiusMeters) {
        return places;
    }
}
