package com.nowserving;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The permanent, restaurant-level QR code.
 *
 * A queue's join_token has always been the only public identity we could
 * hand out, which made every printed QR die with its queue. This token
 * belongs to the BUSINESS, and the tests that matter here are the ones about
 * outliving things: closing a queue, deleting it, replacing it.
 */
class RestaurantQrTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String publicTokenOf(String jwt) throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/me").header("Authorization", "Bearer " + jwt))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("business").get("publicToken").asString();
    }

    @Test
    void everyNewRestaurantGetsAQrTokenWithoutAskingForOne() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Fresh Signup Grill");
        String publicToken = publicTokenOf(token);

        org.assertj.core.api.Assertions.assertThat(publicToken).isNotBlank();

        // ...and it resolves immediately, before any queue exists at all.
        mockMvc.perform(get("/public/restaurants/{t}", publicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Fresh Signup Grill"))
                .andExpect(jsonPath("$.queues.length()").value(0));
    }

    @Test
    void theQrResolvesToTheRestaurantAndItsQueues() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Two Line Bistro");
        createQueue(token, "Dinner");
        createQueue(token, "Bar");

        mockMvc.perform(get("/public/restaurants/{t}", publicTokenOf(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessName").value("Two Line Bistro"))
                .andExpect(jsonPath("$.queues.length()").value(2))
                // Each entry carries everything the venue page needs, so the
                // client never has to make a second call to decide what to
                // offer — including the joinToken the existing flows use.
                .andExpect(jsonPath("$.queues[0].joinToken").isNotEmpty())
                .andExpect(jsonPath("$.queues[0].businessName").value("Two Line Bistro"));
    }

    /** The whole point: the poster on the door outlives the queue behind it. */
    @Test
    void theQrKeepsWorkingAcrossClose_delete_andRecreate() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Seasonal Kitchen");
        String publicToken = publicTokenOf(token);
        var queue = createQueue(token, "Summer Service");
        long queueId = queue.get("id").asLong();
        String oldJoinToken = queue.get("joinToken").asString();

        // 1. closed for the night — still resolves, queue still listed
        mockMvc.perform(patch("/queues/{id}/status", queueId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/restaurants/{t}", publicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queues.length()").value(1))
                .andExpect(jsonPath("$.queues[0].open").value(false));

        // 2. queue gone entirely — the QR must NOT 404, because the poster is
        //    still on the door. It simply has nothing to offer yet.
        //
        //    Deleted straight from the table on purpose: there is no
        //    DELETE /queues/{id} endpoint today (audited — queues are closed,
        //    never deleted, and only a whole-business teardown removes them).
        //    The guarantee under test is structural — the token lives on
        //    businesses, so it cannot depend on a queue row existing — and
        //    that is exactly what removing the row proves.
        jdbc.update("delete from queue_entries where queue_id = ?", queueId);
        jdbc.update("delete from queues where id = ?", queueId);

        mockMvc.perform(get("/public/restaurants/{t}", publicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queues.length()").value(0));

        // 3. new season, new queue — the SAME printed code now leads to it.
        var replacement = createQueue(token, "Winter Service");
        String newJoinToken = replacement.get("joinToken").asString();
        org.assertj.core.api.Assertions.assertThat(newJoinToken).isNotEqualTo(oldJoinToken);

        mockMvc.perform(get("/public/restaurants/{t}", publicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queues.length()").value(1))
                .andExpect(jsonPath("$.queues[0].joinToken").value(newJoinToken));
    }

    @Test
    void theQrTokenSurvivesARename() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Before QR Rename");
        String before = publicTokenOf(token);

        mockMvc.perform(patch("/business")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"After QR Rename\"}"))
                .andExpect(status().isOk());

        // Same token (the sign on the wall is unchanged), new name on it.
        org.assertj.core.api.Assertions.assertThat(publicTokenOf(token)).isEqualTo(before);
        mockMvc.perform(get("/public/restaurants/{t}", before))
                .andExpect(jsonPath("$.businessName").value("After QR Rename"));
    }

    /** Unlisted means "don't advertise me", not "break my own QR" — the same
     *  rule /public/venues/{joinToken} already follows. */
    @Test
    void anUnlistedRestaurantsOwnQrStillWorks() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Members Only");
        var queue = createQueue(token, "Dinner");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/queues/{id}/venue-config", queue.get("id").asLong())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listedPublicly\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/public/venues").param("q", "Members Only"))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/public/restaurants/{t}", publicTokenOf(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queues.length()").value(1));
    }

    @Test
    void anUnknownQrTokenIs404() throws Exception {
        mockMvc.perform(get("/public/restaurants/{t}", "not-a-real-token"))
                .andExpect(status().isNotFound());
    }

    /** The QR is public by design — no login, because it is scanned by
     *  strangers standing in a doorway. */
    @Test
    void theQrNeedsNoAuthentication() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Open Door Cafe");
        createQueue(token, "Dinner");
        mockMvc.perform(get("/public/restaurants/{t}", publicTokenOf(token)))
                .andExpect(status().isOk());
    }

    /** Existing printed codes point at /j/{joinToken}; that path must not
     *  change meaning just because a restaurant-level one now exists. */
    @Test
    void theOldQueueLevelJoinTokenStillWorks() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Legacy Poster Diner");
        var queue = createQueue(token, "Dinner");
        String joinToken = queue.get("joinToken").asString();

        mockMvc.perform(get("/public/venues/{t}", joinToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venue.businessName").value("Legacy Poster Diner"));

        mockMvc.perform(post("/public/queues/{t}/entries", joinToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Poster Pete\",\"partySize\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryToken").isNotEmpty());
    }
}
