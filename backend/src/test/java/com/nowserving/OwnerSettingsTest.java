package com.nowserving;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Owner settings — rename the restaurant, or delete it and everything it owns.
 *
 * The delete tests matter more than usual because this schema has NO database
 * cascades at all (every FK is ON DELETE NO ACTION) and only one JPA cascade,
 * which is not enough on its own. The order in BusinessService is therefore
 * load-bearing, and "no orphans left behind" is asserted directly against the
 * tables rather than inferred from the absence of an exception.
 */
class OwnerSettingsTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private long countWhere(String sql, Object... args) {
        Long n = jdbc.queryForObject("select count(*) from " + sql, Long.class, args);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------- rename

    @Test
    void rename_persistsAndShowsUpOnMe() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Old Name Diner");

        mockMvc.perform(patch("/business")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Spice & Table\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Spice & Table"));

        // The header reads from /me, so that is what has to change.
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.name").value("Spice & Table"));
    }

    @Test
    void rename_trimsSurroundingWhitespace() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Padded");

        mockMvc.perform(patch("/business")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   Trimmed Kitchen   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trimmed Kitchen"));
    }

    @Test
    void rename_rejectsBlankName() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Keeps Its Name");

        mockMvc.perform(patch("/business")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.business.name").value("Keeps Its Name"));
    }

    @Test
    void rename_updatesWhatCustomersSeeInPublicDiscovery() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Before Rename BBQ");
        var queue = createQueue(token, "Dinner");
        long queueId = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        mockMvc.perform(put("/queues/{id}/venue-config", queueId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listedPublicly\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/business")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"After Rename BBQ\"}"))
                .andExpect(status().isOk());

        // The public venue page is what a QR code opens, so it must not keep
        // quoting the old name.
        mockMvc.perform(get("/public/venues/{t}", joinToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venue.businessName").value("After Rename BBQ"));
    }

    @Test
    void rename_requiresAuthentication() throws Exception {
        mockMvc.perform(patch("/business")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Anonymous Takeover\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------- isolation

    /**
     * The real tenant-isolation proof: there is no businessId to tamper with.
     * A second owner renaming "their" business must move THEIR row and leave
     * the first owner's completely untouched.
     */
    @Test
    void anotherOwnerCannotRenameOrDeleteSomeoneElsesBusiness() throws Exception {
        String victim = signupAndLogin(uniqueEmail(), "Victim Cafe");
        String attacker = signupAndLogin(uniqueEmail(), "Attacker Cafe");

        mockMvc.perform(patch("/business")
                        .header("Authorization", "Bearer " + attacker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Pwned\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/me").header("Authorization", "Bearer " + victim))
                .andExpect(jsonPath("$.business.name").value("Victim Cafe"));

        // And a delete by the attacker takes only the attacker's business.
        mockMvc.perform(delete("/business").header("Authorization", "Bearer " + attacker))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/me").header("Authorization", "Bearer " + victim))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.name").value("Victim Cafe"));
    }

    @Test
    void delete_requiresAuthentication() throws Exception {
        mockMvc.perform(delete("/business")).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- delete

    /**
     * The one that would have caught a wrong deletion order: a business
     * carrying a row in EVERY dependent table, including the awkward one
     * where a reservation has been checked in and is therefore pointed at by
     * a queue entry.
     */
    @Test
    void delete_removesEveryDependentRow_andLeavesNoOrphans() throws Exception {
        String email = uniqueEmail();
        String token = signupAndLogin(email, "Full House Grill");
        var queue = createQueue(token, "Dinner");
        long queueId = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        long businessId = objectMapper.readTree(mockMvc.perform(
                        get("/me").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("business").get("id").asLong();

        // a walk-in, with push + location + consent hanging off it
        JsonNode entry = joinQueue(joinToken, "Walk In Wendy");
        String entryToken = entry.get("entryToken").asString();

        mockMvc.perform(post("/public/entries/{t}/push-subscription", entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpoint":"https://push.example/abc",
                                 "keys":{"p256dh":"BParnRLzTest","auth":"authTest"}}
                                """))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/public/entries/{t}/location", entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":51.5,\"longitude\":-0.12}"))
                .andExpect(status().isNoContent());

        // a booking, then check it in — this is what makes a queue_entry
        // point at a reservation, the FK that forces entries to be deleted
        // before reservations.
        mockMvc.perform(put("/queues/{id}/booking-config", queueId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationsEnabled":true,"openingTime":"00:00",
                                 "closingTime":"23:59","slotMinutes":30,"slotCapacity":4,
                                 "timeZone":"UTC"}
                                """))
                .andExpect(status().isOk());

        String slots = mockMvc.perform(get("/public/queues/{t}/availability", joinToken)
                        .param("date", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode firstFree = null;
        for (JsonNode s : objectMapper.readTree(slots).get("slots")) {
            if (s.get("available").asBoolean()) { firstFree = s; break; }
        }
        assertThat(firstFree).as("a bookable slot").isNotNull();

        String reservation = mockMvc.perform(post("/public/queues/{t}/reservations", joinToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Booked Bella\",\"partySize\":2,\"slotStart\":\"%s\"}"
                                .formatted(firstFree.get("startTime").asString())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reservationToken = objectMapper.readTree(reservation).get("reservationToken").asString();

        mockMvc.perform(post("/public/reservations/{t}/check-in", reservationToken))
                .andExpect(status().isOk());

        // Everything is really there before we delete.
        assertThat(countWhere("queues where business_id = ?", businessId)).isEqualTo(1);
        assertThat(countWhere("queue_entries where queue_id = ?", queueId)).isGreaterThan(0);
        assertThat(countWhere("reservations where queue_id = ?", queueId)).isEqualTo(1);
        assertThat(countWhere("push_subscriptions ps join queue_entries e on e.id = ps.entry_id"
                + " where e.queue_id = ?", queueId)).isEqualTo(1);
        assertThat(countWhere("queue_entries where queue_id = ? and reservation_id is not null",
                queueId)).as("a checked-in booking").isEqualTo(1);

        mockMvc.perform(delete("/business").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // ...and nothing is left anywhere.
        assertThat(countWhere("businesses where id = ?", businessId)).isZero();
        assertThat(countWhere("owners where business_id = ?", businessId)).isZero();
        assertThat(countWhere("queues where business_id = ?", businessId)).isZero();
        assertThat(countWhere("queue_entries where queue_id = ?", queueId)).isZero();
        assertThat(countWhere("reservations where queue_id = ?", queueId)).isZero();
        assertThat(countWhere("service_samples where queue_id = ?", queueId)).isZero();
        assertThat(countWhere("push_subscriptions ps join queue_entries e on e.id = ps.entry_id"
                + " where e.queue_id = ?", queueId)).isZero();
        assertThat(countWhere("tracking_consents tc join queue_entries e on e.id = tc.entry_id"
                + " where e.queue_id = ?", queueId)).isZero();
        assertThat(countWhere("leave_now_alerts a join queue_entries e on e.id = a.entry_id"
                + " where e.queue_id = ?", queueId)).isZero();
    }

    @Test
    void delete_endsTheSessionAndHidesTheRestaurantFromDiscovery() throws Exception {
        String email = uniqueEmail();
        String token = signupAndLogin(email, "Vanishing Venue");
        var queue = createQueue(token, "Dinner");
        String joinToken = queue.get("joinToken").asString();

        mockMvc.perform(put("/queues/{id}/venue-config", queue.get("id").asLong())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listedPublicly\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/venues").param("q", "Vanishing"))
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(delete("/business").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Gone from public discovery and from its own direct link.
        mockMvc.perform(get("/public/venues").param("q", "Vanishing"))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/public/venues/{t}", joinToken))
                .andExpect(status().isNotFound());

        // The old JWT is still cryptographically valid, but there is no owner
        // behind it any more — the client sees this and logs itself out.
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());

        // And the email is free to sign up again.
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Second Life","email":"%s",
                                 "password":"secret123","displayName":"Test Owner"}
                                """.formatted(email)))
                .andExpect(status().isCreated());
    }
}
