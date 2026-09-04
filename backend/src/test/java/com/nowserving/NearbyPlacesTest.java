package com.nowserving;

import com.nowserving.places.NearbyPlace;
import com.nowserving.repository.QueueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /public/venues/nearby — NowServing venues merged with OpenStreetMap
 * points of interest.
 *
 * FakePlacesProvider stands in for Overpass (a real, unauthenticated public
 * API with no SLA — see OverpassPlacesProvider's class comment for why a
 * test suite must never depend on it being up). What actually needs proving
 * here is the MERGE logic: NowServing stays the only source of truth for
 * anything that implies you can join a line, an outage in the external
 * source degrades to "NowServing venues only" rather than an error, and the
 * two sources never show the same physical restaurant twice.
 *
 * ISOLATION: every other test class in this suite isolates by DATA (a unique
 * email, a fresh joinToken) because the real Postgres container persists
 * across the whole run.
 *
 * A unique origin per test is NOT enough on its own. It used to be, but only
 * by accident: nearby() applied its radius to NowServing venues too, so
 * another test's venue 690 miles away was filtered out on distance. That
 * radius bounds the OVERPASS query, and applying it to our own tenants was
 * the bug that hid published restaurants from discovery entirely. Now that
 * listedPublicly is the only thing deciding whether a venue is discoverable,
 * isolation has to use that same axis — so each test starts from a world
 * where nothing else is published. The unique origin still matters, for the
 * distance ORDERING and the same-place dedupe.
 */
class NearbyPlacesTest extends AbstractIntegrationTest {

    private static final AtomicInteger ORIGIN_COUNTER = new AtomicInteger();

    @Autowired
    private QueueRepository queueRepository;

    /** Unpublish everything a previous test left behind. Deliberately not a
     *  delete: other classes' rows must survive, they just must not be
     *  discoverable while this class asserts on list lengths. */
    @BeforeEach
    void hideEveryPreexistingVenue() {
        // saveAll, because these entities are detached the moment findListed
        // returns outside a transaction — mutating them alone would be a
        // silent no-op and the isolation would look like it worked.
        var stale = queueRepository.findListed("");
        stale.forEach(q -> q.setListedPublicly(false));
        queueRepository.saveAll(stale);
    }

    /** A patch of ocean far from every other test's — and from real Overpass
     *  traffic, though FakePlacesProvider means that's moot here. */
    private double[] uniqueOrigin() {
        int n = ORIGIN_COUNTER.getAndIncrement();
        return new double[]{n * 10.0 - 80.0, 0.0};
    }

    private void configureVenue(String token, long queueId, String json) throws Exception {
        mockMvc.perform(put("/queues/{id}/venue-config", queueId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    @Test
    void nearby_mergesNowServingAndExternalPlaces_nowServingFirst() throws Exception {
        double[] origin = uniqueOrigin();
        String token = signupAndLogin(uniqueEmail(), "Trafalgar Tandoori");
        var queue = createQueue(token, "Dinner");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();
        configureVenue(token, id, ("{\"venueLatitude\":%s,\"venueLongitude\":%s,"
                + "\"listedPublicly\":true,\"allowRemoteJoin\":true}")
                .formatted(origin[0], origin[1]));

        // An OSM restaurant with no NowServing presence at all, ~5.5mi away.
        fakePlaces.willReturn(List.of(new NearbyPlace(
                "osm:1", "India Palace", origin[0] + 0.08, origin[1] + 0.03,
                "indian", "3 High Street", 5.5, "OSM", null)));

        mockMvc.perform(get("/public/venues/nearby")
                        .param("lat", String.valueOf(origin[0]))
                        .param("lon", String.valueOf(origin[1]))
                        .param("radiusMiles", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                // NowServing entry: has everything an external place must not.
                .andExpect(jsonPath("$[0].name").value("Trafalgar Tandoori"))
                .andExpect(jsonPath("$[0].onNowServing").value(true))
                .andExpect(jsonPath("$[0].joinToken").value(joinToken))
                .andExpect(jsonPath("$[0].joinableNow").value(true))
                // External entry: no joinToken, clearly marked.
                .andExpect(jsonPath("$[1].name").value("India Palace"))
                .andExpect(jsonPath("$[1].onNowServing").value(false))
                .andExpect(jsonPath("$[1].joinToken").doesNotExist())
                .andExpect(jsonPath("$[1].cuisine").value("indian"))
                // No opening_hours tag on this place -> UNKNOWN, never CLOSED
                // — end-to-end proof that DiscoveryService actually wires
                // OpeningHoursEvaluator in, not just that the evaluator works
                // in isolation.
                .andExpect(jsonPath("$[1].openingStatus").value("UNKNOWN"))
                // NowServing rows don't use this field at all — they already
                // have an authoritative open/closed signal in joinableNow.
                .andExpect(jsonPath("$[0].openingStatus").doesNotExist());
    }

    @Test
    void nearby_wiresOpeningHoursEndToEnd_whenTheTagIsPresent() throws Exception {
        double[] origin = uniqueOrigin();
        fakePlaces.willReturn(List.of(new NearbyPlace(
                "osm:always-open", "Round The Clock Diner", origin[0] + 0.01, origin[1],
                null, null, 0.6, "OSM", "24/7")));

        mockMvc.perform(get("/public/venues/nearby")
                        .param("lat", String.valueOf(origin[0]))
                        .param("lon", String.valueOf(origin[1]))
                        .param("radiusMiles", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Round The Clock Diner"))
                .andExpect(jsonPath("$[0].openingStatus").value("OPEN_NOW"));
    }

    @Test
    void nearby_neverGivesAnExternalPlaceAFakeJoinToken() throws Exception {
        // The one invariant that matters most: an OSM place must never look
        // joinable. If this regresses, a customer could tap "Join Waitlist"
        // on a restaurant that has no queue at all.
        double[] origin = uniqueOrigin();
        fakePlaces.willReturn(List.of(new NearbyPlace(
                "osm:2", "Curry Corner", origin[0] + 0.03, origin[1], null, null, 2.0, "OSM", null)));

        String body = mockMvc.perform(get("/public/venues/nearby")
                        .param("lat", String.valueOf(origin[0]))
                        .param("lon", String.valueOf(origin[1]))
                        .param("radiusMiles", "10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var node = objectMapper.readTree(body).get(0);
        org.assertj.core.api.Assertions.assertThat(node.get("joinToken").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(node.get("joinableNow").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(node.get("currentWaitMinutes").isNull()).isTrue();
    }

    @Test
    void nearby_dedupsAnExternalPlaceThatIsReallyTheSameNowServingVenue() throws Exception {
        double[] origin = uniqueOrigin();
        // Same physical spot as the NowServing venue below (~120m off) —
        // this must NOT also appear as "not on NowServing yet".
        String token = signupAndLogin(uniqueEmail(), "Duplicate Diner");
        var queue = createQueue(token, "Dinner");
        long id = queue.get("id").asLong();
        configureVenue(token, id, ("{\"venueLatitude\":%s,\"venueLongitude\":%s,"
                + "\"listedPublicly\":true}").formatted(origin[0], origin[1]));

        fakePlaces.willReturn(List.of(new NearbyPlace(
                "osm:3", "Duplicate Diner", origin[0] + 0.0002, origin[1] + 0.0002,
                null, null, 0.03, "OSM", null)));

        mockMvc.perform(get("/public/venues/nearby")
                        .param("lat", String.valueOf(origin[0]))
                        .param("lon", String.valueOf(origin[1]))
                        .param("radiusMiles", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].onNowServing").value(true));
    }

    @Test
    void nearby_degradesToNowServingOnlyWhenTheExternalSourceIsDown() throws Exception {
        double[] origin = uniqueOrigin();
        String token = signupAndLogin(uniqueEmail(), "Resilient Ramen");
        var queue = createQueue(token, "Dinner");
        long id = queue.get("id").asLong();
        configureVenue(token, id, ("{\"venueLatitude\":%s,\"venueLongitude\":%s,"
                + "\"listedPublicly\":true}").formatted(origin[0], origin[1]));

        // FakePlacesProvider's contract mirrors OverpassPlacesProvider's:
        // never throw, an outage returns an empty list.
        fakePlaces.willBeUnavailable();

        mockMvc.perform(get("/public/venues/nearby")
                        .param("lat", String.valueOf(origin[0]))
                        .param("lon", String.valueOf(origin[1]))
                        .param("radiusMiles", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Resilient Ramen"));
    }

    @Test
    void nearby_sortsByHaversineDistance_nowServingGroupFirst() throws Exception {
        // No external places in this test — reset explicitly. FakePlacesProvider
        // is a shared Spring bean across the whole test run; without this it
        // would silently inherit whatever an earlier test last configured.
        fakePlaces.willReturn(List.of());
        double[] origin = uniqueOrigin();
        String token = signupAndLogin(uniqueEmail(), "Far Kitchen");
        var queue = createQueue(token, "Dinner");
        long id = queue.get("id").asLong();
        // Deliberately the FARTHER of the two NowServing venues, to prove
        // sorting isn't just insertion order.
        configureVenue(token, id, ("{\"venueLatitude\":%s,\"venueLongitude\":%s,"
                + "\"listedPublicly\":true}").formatted(origin[0] + 0.08, origin[1] + 0.03));

        String token2 = signupAndLogin(uniqueEmail(), "Near Kitchen");
        var queue2 = createQueue(token2, "Dinner");
        long id2 = queue2.get("id").asLong();
        configureVenue(token2, id2, ("{\"venueLatitude\":%s,\"venueLongitude\":%s,"
                + "\"listedPublicly\":true}").formatted(origin[0] + 0.001, origin[1] + 0.001));

        mockMvc.perform(get("/public/venues/nearby")
                        .param("lat", String.valueOf(origin[0]))
                        .param("lon", String.valueOf(origin[1]))
                        .param("radiusMiles", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Near Kitchen"))
                .andExpect(jsonPath("$[1].name").value("Far Kitchen"));
    }

    @Test
    void nearby_excludesAnUnpublishedVenue_evenWhenPhysicallyClose() throws Exception {
        // The safety rule from V10: a hidden restaurant must never appear in
        // ANY public discovery surface, nearby included.
        fakePlaces.willReturn(List.of()); // see the shared-bean note above
        double[] origin = uniqueOrigin();
        String token = signupAndLogin(uniqueEmail(), "Testing Only Bistro");
        var queue = createQueue(token, "Dinner");
        long id = queue.get("id").asLong();
        configureVenue(token, id, ("{\"venueLatitude\":%s,\"venueLongitude\":%s}")
                .formatted(origin[0], origin[1])); // listedPublicly left at its default: false

        mockMvc.perform(get("/public/venues/nearby")
                        .param("lat", String.valueOf(origin[0]))
                        .param("lon", String.valueOf(origin[1]))
                        .param("radiusMiles", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void newlyCreatedQueue_defaultsHidden() throws Exception {
        // V10's actual requirement, proven directly against the entity/DB
        // rather than inferred from the search behaviour above.
        String token = signupAndLogin(uniqueEmail(), "Brand New Grill");
        long id = createQueue(token, "Dinner").get("id").asLong();

        mockMvc.perform(get("/queues/{id}/venue-config", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listedPublicly").value(false));
    }

    @Test
    void publishAndHide_roundTripThroughVenueConfig() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Publish Toggle Cafe");
        long id = createQueue(token, "Dinner").get("id").asLong();

        configureVenue(token, id, "{\"listedPublicly\":true}");
        mockMvc.perform(get("/queues/{id}/venue-config", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.listedPublicly").value(true));

        configureVenue(token, id, "{\"listedPublicly\":false}");
        mockMvc.perform(get("/queues/{id}/venue-config", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.listedPublicly").value(false));
    }
}
