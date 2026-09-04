package com.nowserving;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V9 — public discovery and the owner-controlled remote-joining policy.
 *
 * The interesting assertions here are the REFUSALS. Anyone can verify that a
 * search returns a restaurant; the reason this feature needed tests is that
 * it introduced the first way to be turned away at the door, and each door
 * has to fail for the right reason with the right status code.
 */
class DiscoveryAndRemoteJoinTest extends AbstractIntegrationTest {

    // Trafalgar Square, and a point ~2 miles away (Shoreditch-ish).
    private static final double VENUE_LAT = 51.5080, VENUE_LNG = -0.1281;
    private static final double NEARBY_LAT = 51.5265, NEARBY_LNG = -0.0840;
    // Reading — comfortably outside any sane radius (~36 miles).
    private static final double FAR_LAT = 51.4543, FAR_LNG = -0.9781;

    /** Put the venue on the map and set its door policy in one call. */
    private void configureVenue(String token, long queueId, String json) throws Exception {
        mockMvc.perform(put("/queues/{id}/venue-config", queueId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk());
    }

    /** @param remote null/false = the on-site default (a bare QR link). */
    private int joinFrom(String joinToken, String name, Double lat, Double lng, Boolean remote)
            throws Exception {
        StringBuilder body = new StringBuilder("{\"customerName\":\"" + name + "\"");
        if (lat != null) body.append(",\"latitude\":").append(lat).append(",\"longitude\":").append(lng);
        if (remote != null) body.append(",\"remote\":").append(remote);
        body.append("}");

        return mockMvc.perform(post("/public/queues/{t}/entries", joinToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andReturn().getResponse().getStatus();
    }

    // ---------- discovery ----------

    @Test
    void search_findsAListedRestaurantByNameAndQuotesItsWait() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Spicy Kitchen");
        var queue = createQueue(token, "Dinner service");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        // V10: every new queue starts HIDDEN — the owner must publish before
        // a customer can ever find it in search.
        configureVenue(token, id, "{\"listedPublicly\":true}");
        joinQueue(joinToken, "Already Waiting");

        mockMvc.perform(get("/public/venues").param("q", "spicy"))
                .andExpect(status().isOk())
                // Matching is case-insensitive over the BUSINESS name...
                .andExpect(jsonPath("$[?(@.joinToken=='" + joinToken + "')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.joinToken=='" + joinToken + "')].businessName")
                        .value("Spicy Kitchen"))
                .andExpect(jsonPath("$[?(@.joinToken=='" + joinToken + "')].partiesWaiting")
                        .value(1));

        // ...and over the QUEUE name, which is what "dinner" would find.
        mockMvc.perform(get("/public/venues").param("q", "dinner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.joinToken=='" + joinToken + "')]").isNotEmpty());
    }

    @Test
    void search_neverLeaksAnUnlistedRestaurant_butItsOwnLinkStillWorks() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Private Dining Club");
        var queue = createQueue(token, "Members only");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        configureVenue(token, id, "{\"listedPublicly\":false}");

        // Gone from search...
        mockMvc.perform(get("/public/venues").param("q", "Private Dining"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.joinToken=='" + joinToken + "')]").isEmpty());

        // ...but the QR code and the direct link must still resolve. Unlisted
        // means "don't advertise me", not "closed".
        mockMvc.perform(get("/public/venues/{t}", joinToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venue.businessName").value("Private Dining Club"));
    }

    @Test
    void venuePage_reportsDistanceAndPreWarnsWhenTheCustomerIsTooFar() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Corner Cafe");
        var queue = createQueue(token, "Walk-ins");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        configureVenue(token, id, """
                {"venueLatitude":%s,"venueLongitude":%s,"allowRemoteJoin":true,"maxRemoteJoinMiles":5}
                """.formatted(VENUE_LAT, VENUE_LNG));

        // Close by: allowed, and the card says how far.
        mockMvc.perform(get("/public/venues/{t}", joinToken)
                        .param("lat", String.valueOf(NEARBY_LAT))
                        .param("lng", String.valueOf(NEARBY_LNG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venue.tooFarToJoinRemotely").value(false))
                .andExpect(jsonPath("$.venue.distanceMiles").isNumber());

        // Far away: the card says so BEFORE they fill in a form and get
        // rejected — the whole reason this flag is precomputed.
        mockMvc.perform(get("/public/venues/{t}", joinToken)
                        .param("lat", String.valueOf(FAR_LAT))
                        .param("lng", String.valueOf(FAR_LNG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venue.tooFarToJoinRemotely").value(true));

        // No location shared at all: distance is unknown, and unknown must
        // never read as "too far" (FR-17 — location is an enhancement).
        mockMvc.perform(get("/public/venues/{t}", joinToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venue.distanceMiles").doesNotExist())
                .andExpect(jsonPath("$.venue.tooFarToJoinRemotely").value(false));
    }

    // ---------- the door policy ----------

    @Test
    void remoteJoin_withinTheRadiusIsAllowed_beyondItIsRefusedWithTheNumbers() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Radius Grill");
        var queue = createQueue(token, "Line");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        configureVenue(token, id, """
                {"venueLatitude":%s,"venueLongitude":%s,"allowRemoteJoin":true,"maxRemoteJoinMiles":10}
                """.formatted(VENUE_LAT, VENUE_LNG));

        assertThat(joinFrom(joinToken, "Nearby Nina", NEARBY_LAT, NEARBY_LNG, true)).isEqualTo(201);
        assertThat(joinFrom(joinToken, "Faraway Fred", FAR_LAT, FAR_LNG, true)).isEqualTo(400);
    }

    @Test
    void remoteJoin_withoutALocation_asks428_notA400() throws Exception {
        // The distinction the frontend depends on: 428 means "I can't decide
        // yet, go and ask the browser", 400 means "you're refused". If this
        // ever regresses to 400 the client stops retrying with coordinates
        // and remote joining silently dies.
        String token = signupAndLogin(uniqueEmail(), "Precondition Pizza");
        var queue = createQueue(token, "Line");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        configureVenue(token, id, """
                {"venueLatitude":%s,"venueLongitude":%s,"allowRemoteJoin":true,"maxRemoteJoinMiles":10}
                """.formatted(VENUE_LAT, VENUE_LNG));

        mockMvc.perform(post("/public/queues/{t}/entries", joinToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"No Location Nora\",\"remote\":true}"))
                .andExpect(status().isPreconditionRequired()) // 428
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("10 miles")));
    }

    @Test
    void anOnSiteJoin_isTheDefault_andSkipsTheDistanceCheckEntirely() throws Exception {
        // THE REGRESSION GUARD. A bare /j/{token} — which is exactly what
        // every printed QR code contains — must never be treated as remote.
        // The first version of this feature got the default backwards and
        // made every existing poster demand a location; eight Leave-Now
        // tests caught it. This test is here so it can't happen twice.
        //
        // Someone holding the code is standing at the counter. Their GPS
        // saying otherwise is not a reason to refuse them service.
        String token = signupAndLogin(uniqueEmail(), "QR Diner");
        var queue = createQueue(token, "Line");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        configureVenue(token, id, """
                {"venueLatitude":%s,"venueLongitude":%s,"allowRemoteJoin":true,"maxRemoteJoinMiles":1}
                """.formatted(VENUE_LAT, VENUE_LNG));

        // No coordinates at all, and a 1-mile radius: as REMOTE this is 428.
        assertThat(joinFrom(joinToken, "Scanner Sam", null, null, null)).isEqualTo(201);
        assertThat(joinFrom(joinToken, "Would Be Remote", null, null, true)).isEqualTo(428);
        // Even coordinates 36 miles away don't matter on the on-site path.
        assertThat(joinFrom(joinToken, "Scanner Sue", FAR_LAT, FAR_LNG, null)).isEqualTo(201);
    }

    @Test
    void walkInsOnly_refusesRemote_butStillAcceptsAnOnSiteJoin() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Walk In Only");
        var queue = createQueue(token, "Line");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        configureVenue(token, id, """
                {"venueLatitude":%s,"venueLongitude":%s,"allowRemoteJoin":false}
                """.formatted(VENUE_LAT, VENUE_LNG));

        // Refused even though they're standing two miles away — the owner
        // said no remote joins, and that's policy, not distance.
        assertThat(joinFrom(joinToken, "Remote Rita", NEARBY_LAT, NEARBY_LNG, true)).isEqualTo(400);
        assertThat(joinFrom(joinToken, "Scanner Sam", null, null, null)).isEqualTo(201);
    }

    @Test
    void aVenueWithNoCoordinates_letsEvenRemoteCustomersIn() throws Exception {
        // Degrade toward WORKING. A brand-new restaurant that hasn't set its
        // location yet must not silently reject every remote customer.
        String token = signupAndLogin(uniqueEmail(), "Unmapped Bistro");
        String joinToken = createQueue(token, "Line").get("joinToken").asString();

        assertThat(joinFrom(joinToken, "Anywhere Annie", null, null, true)).isEqualTo(201);
    }

    @Test
    void theRadiusIsCappedAt50_howeverLargeAValueIsSent() throws Exception {
        // 50 is the product's ceiling, not a suggestion. The DTO's @Max is
        // one guard; this proves the service clamps too, for callers that
        // never pass through a controller.
        String token = signupAndLogin(uniqueEmail(), "Ambitious Eats");
        long id = createQueue(token, "Line").get("id").asLong();

        mockMvc.perform(put("/queues/{id}/venue-config", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxRemoteJoinMiles\":500}"))
                .andExpect(status().isBadRequest()); // @Max(50) rejects it outright

        configureVenue(token, id, "{\"maxRemoteJoinMiles\":50}");
        mockMvc.perform(get("/queues/{id}/venue-config", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.maxRemoteJoinMiles").value(50))
                .andExpect(jsonPath("$.maxRemoteJoinMilesCeiling").value(50));
    }

    // ---------- notification preference ----------

    @Test
    void notificationChoice_defaultsToPush_andCanBeChangedFromTheTicketPage() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Notify Noodles");
        String joinToken = createQueue(token, "Line").get("joinToken").asString();
        String entryToken = joinQueue(joinToken, "Preference Pat").get("entryToken").asString();

        // Switching to SMS after joining — the sequencing the redesign chose.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/public/entries/{t}/notify-preference", entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"SMS\",\"phoneNumber\":\"+15550001234\"}"))
                .andExpect(status().isNoContent());

        // Asking for SMS with no number falls back to push rather than
        // failing — a half-answered preference shouldn't break a ticket.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/public/entries/{t}/notify-preference", entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"channel\":\"SMS\"}"))
                .andExpect(status().isNoContent());
    }
}
