package com.nowserving;

import com.nowserving.places.NearbyPlace;
import com.nowserving.places.OverpassPlacesProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Root-cause regression tests for the nearby-discovery outage.
 *
 * These are HTTP-MOCKED — a local {@link HttpServer} (built into the JDK,
 * no new test dependency) stands in for the real Overpass API, so parsing
 * third-party JSON is verified deterministically and the suite never makes a
 * real network call. Extends AbstractIntegrationTest purely to reuse its
 * real Redis container and Spring-managed ObjectMapper: this codebase's
 * convention is real infrastructure over hand-rolled mocks (see every other
 * Fake* class in `support/`), and the cache bug this test suite exists to
 * pin down IS a Redis round-trip bug — a mocked RedisTemplate would not have
 * caught it.
 */
class OverpassPlacesProviderTest extends AbstractIntegrationTest {

    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private tools.jackson.databind.ObjectMapper objectMapper;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    /** Serves a fixed response body for every request, and reports how many
     *  requests it received. */
    private String startServer(int statusCode, String body) throws IOException {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/";
    }

    private OverpassPlacesProvider providerFor(String url) {
        return new OverpassPlacesProvider(url, objectMapper, redisTemplate);
    }

    // ---------- Phase 3: node / way / relation parsing ----------

    // NOTE ON RADII: every test below uses lat=51.5, lon=-0.1 (round numbers
    // are convenient to reason about) but a DISTINCT radiusMeters value.
    // radiusMeters is part of the cache key, so this is this suite's
    // equivalent of NearbyPlacesTest's unique-origin-per-test convention —
    // reusing one (lat, lon, radius) triple across tests sharing this
    // class's real Redis container means test #2 would silently read test
    // #1's cached answer instead of exercising its own mock server.

    @Test
    void nodeRestaurant_isParsedFromDirectLatLon() throws Exception {
        String url = startServer(200, """
                {"elements":[{"type":"node","id":1,"lat":51.5,"lon":-0.1,
                  "tags":{"name":"Node Diner","amenity":"restaurant"}}]}
                """);

        List<NearbyPlace> places = providerFor(url).nearbyRestaurants(51.5, -0.1, 101);

        assertThat(places).hasSize(1);
        assertThat(places.get(0).name()).isEqualTo("Node Diner");
    }

    @Test
    void wayRestaurant_isParsedFromCenterCoordinates() throws Exception {
        // THE ROOT-CAUSE-ADJACENT BUG (Phase 3): the query used to ask for
        // nodes only, so a restaurant mapped as a building outline (a `way`
        // — common for big chains, food courts, malls) was never even
        // requested from Overpass, let alone parsed. A way carries no top
        // level lat/lon; only `out center` puts one under element.center.
        String url = startServer(200, """
                {"elements":[{"type":"way","id":2,"center":{"lat":51.51,"lon":-0.12},
                  "tags":{"name":"Way Bistro","amenity":"restaurant"}}]}
                """);

        List<NearbyPlace> places = providerFor(url).nearbyRestaurants(51.5, -0.1, 102);

        assertThat(places).hasSize(1);
        assertThat(places.get(0).name()).isEqualTo("Way Bistro");
        assertThat(places.get(0).latitude()).isEqualTo(51.51);
        assertThat(places.get(0).longitude()).isEqualTo(-0.12);
    }

    @Test
    void relationRestaurant_isParsedFromCenterCoordinatesToo() throws Exception {
        String url = startServer(200, """
                {"elements":[{"type":"relation","id":3,"center":{"lat":51.52,"lon":-0.13},
                  "tags":{"name":"Relation Grill","amenity":"restaurant"}}]}
                """);

        List<NearbyPlace> places = providerFor(url).nearbyRestaurants(51.5, -0.1, 103);

        assertThat(places).hasSize(1);
        assertThat(places.get(0).name()).isEqualTo("Relation Grill");
    }

    @Test
    void anElementWithNeitherDirectNorCenterCoordinates_isSkippedNotCrashed() throws Exception {
        String url = startServer(200, """
                {"elements":[{"type":"way","id":4,"tags":{"name":"Coordless Cafe","amenity":"restaurant"}}]}
                """);

        List<NearbyPlace> places = providerFor(url).nearbyRestaurants(51.5, -0.1, 104);

        assertThat(places).isEmpty();
    }

    @Test
    void openingHoursTag_isCarriedThroughUnparsed() throws Exception {
        String url = startServer(200, """
                {"elements":[{"type":"node","id":5,"lat":51.5,"lon":-0.1,
                  "tags":{"name":"Tagged Diner","amenity":"restaurant","opening_hours":"Mo-Fr 09:00-17:00"}}]}
                """);

        List<NearbyPlace> places = providerFor(url).nearbyRestaurants(51.5, -0.1, 105);

        assertThat(places.get(0).openingHours()).isEqualTo("Mo-Fr 09:00-17:00");
    }

    // ---------- THE ROOT CAUSE: cache read must match cache write ----------

    @Test
    void aCacheHit_returnsTheSameCountAsTheOriginalLiveFetch() throws Exception {
        // THE BUG: the cache used to be WRITTEN as a serialized
        // List<NearbyPlace>, but READ by feeding that same JSON through the
        // Overpass-response parser (which looks for a top-level "elements"
        // key). A JSON ARRAY has no "elements" key, so every cache HIT
        // silently produced zero results — a live fetch would find real
        // restaurants, and the very next request for the same cell, twenty
        // seconds later on the discovery page's own poll, would find none.
        String url = startServer(200, """
                {"elements":[
                  {"type":"node","id":10,"lat":40.00,"lon":-75.00,"tags":{"name":"First Visit Diner"}},
                  {"type":"node","id":11,"lat":40.001,"lon":-75.001,"tags":{"name":"Second Restaurant"}}
                ]}
                """);
        OverpassPlacesProvider provider = providerFor(url);

        List<NearbyPlace> firstCall = provider.nearbyRestaurants(40.00, -75.00, 2000); // live fetch
        List<NearbyPlace> secondCall = provider.nearbyRestaurants(40.00, -75.00, 2000); // cache hit

        assertThat(firstCall).hasSize(2);
        assertThat(secondCall)
                .as("a cache hit must return the same places the live fetch found, not zero")
                .hasSize(2)
                .extracting(NearbyPlace::name)
                .containsExactlyInAnyOrder("First Visit Diner", "Second Restaurant");
    }

    // ---------- Phase 5/6: a failed refresh must not destroy good data ----------

    @Test
    void overpassFailure_returnsEmptyWhenThereIsNoPriorData() throws Exception {
        String url = startServer(500, "server error");

        List<NearbyPlace> places = providerFor(url).nearbyRestaurants(41.00, -76.00, 2000);

        assertThat(places).isEmpty(); // the only honest answer with nothing to fall back on
    }

    @Test
    void overpassFailure_afterAPriorSuccess_servesTheLastKnownGoodPlaces() throws Exception {
        // Success first, populating both the fresh and the "last known good" cache...
        String url = startServer(200, """
                {"elements":[{"type":"node","id":20,"lat":42.00,"lon":-77.00,"tags":{"name":"Reliable Diner"}}]}
                """);
        OverpassPlacesProvider provider = providerFor(url);
        List<NearbyPlace> goodCall = provider.nearbyRestaurants(42.00, -77.00, 2000);
        assertThat(goodCall).hasSize(1);
        stopServer();

        // Force the FRESH cache entry to expire early — otherwise this test
        // would pass even without any fallback logic at all, since the still
        // valid fresh entry would answer the second call on its own. Deleting
        // it isolates exactly what's being tested: the GOOD-cache fallback.
        redisTemplate.delete("nearby-restaurants:42.00:-77.00:2000");

        // ...then Overpass starts failing. A NEW provider instance with an
        // EMPTY in-process state proves this comes from Redis, not a local
        // field — exactly what a real backend restart would look like.
        String failingUrl = startServer(503, "temporarily unavailable");
        OverpassPlacesProvider providerAfterRestart = providerFor(failingUrl);

        List<NearbyPlace> duringOutage = providerAfterRestart.nearbyRestaurants(42.00, -77.00, 2000);

        assertThat(duringOutage)
                .as("a failed refresh must serve stale-but-real data, never a false empty result")
                .hasSize(1)
                .extracting(NearbyPlace::name)
                .containsExactly("Reliable Diner");
    }

    @Test
    void aFailedFetch_neverOverwritesTheLastKnownGoodCache() throws Exception {
        String goodUrl = startServer(200, """
                {"elements":[{"type":"node","id":30,"lat":43.00,"lon":-78.00,"tags":{"name":"Established Eatery"}}]}
                """);
        OverpassPlacesProvider provider = providerFor(goodUrl);
        provider.nearbyRestaurants(43.00, -78.00, 2000);
        stopServer();

        // Expire the fresh entry so each of the two failed calls below is
        // forced to actually attempt a live fetch (and fall back), rather
        // than short-circuiting on a still-valid fresh cache hit.
        redisTemplate.delete("nearby-restaurants:43.00:-78.00:2000");

        String badUrl = startServer(429, "rate limited");
        // Two failures in a row, each forced past the (deleted) fresh cache
        // so each one genuinely attempts — and fails — a live fetch.
        providerFor(badUrl).nearbyRestaurants(43.00, -78.00, 2000);
        redisTemplate.delete("nearby-restaurants:43.00:-78.00:2000");
        providerFor(badUrl).nearbyRestaurants(43.00, -78.00, 2000);
        redisTemplate.delete("nearby-restaurants:43.00:-78.00:2000");

        // Overpass is STILL down here — no healthy server started. If either
        // 429 above had overwritten the good cache with an empty result,
        // this would now return nothing.
        List<NearbyPlace> stillGood = providerFor(badUrl).nearbyRestaurants(43.00, -78.00, 2000);
        assertThat(stillGood).extracting(NearbyPlace::name).containsExactly("Established Eatery");
    }
}
