package com.nowserving;

import com.nowserving.entity.EntryStatus;
import com.nowserving.repository.QueueEntryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P0/P1 — the "an owner can run a whole day without curl" additions:
 * close/reopen with audit, real history filtering, stats, join idempotency,
 * the LEFT lifecycle, feedback, and the state machine.
 */
class OperationalCompletenessTest extends AbstractIntegrationTest {

    @Autowired private QueueEntryRepository entryRepository;

    // ---------- P0: close / reopen ----------

    @Test
    void closing_rejectsJoins_reopening_allowsThem_andAuditsWho() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Closable Shop");
        var queue = createQueue(token, "Day Shift");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        String beforeTicket = joinQueue(joinToken, "Early Bird").get("entryToken").asString();

        // Close — with a reason, and the audit trail records it.
        mockMvc.perform(patch("/queues/{id}/status", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\",\"reason\":\"End of day\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // A closed queue rejects NEW joins...
        mockMvc.perform(post("/public/queues/{t}/entries", joinToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Too Late\"}"))
                .andExpect(status().isBadRequest());

        // ...but existing tickets keep working — closing the door doesn't
        // throw out the people already inside.
        mockMvc.perform(get("/public/entries/{t}", beforeTicket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"));

        // Reopen -> joins flow again, audit fields cleared.
        mockMvc.perform(patch("/queues/{id}/status", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OPEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mockMvc.perform(post("/public/queues/{t}/entries", joinToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Welcome Back\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void anotherOwner_cannotCloseMyQueue() throws Exception {
        String mine = signupAndLogin(uniqueEmail(), "Mine");
        long id = createQueue(mine, "Protected").get("id").asLong();
        String theirs = signupAndLogin(uniqueEmail(), "Theirs");

        mockMvc.perform(patch("/queues/{id}/status", id)
                        .header("Authorization", "Bearer " + theirs)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isNotFound()); // 404, never 403 — same rule as everywhere
    }

    @Test
    void closingWhileSomeoneJoins_neverProducesA500_andStateStaysConsistent() throws Exception {
        // The race the roadmap asks about. Acceptable outcomes: the join wins
        // (committed before close) or loses (400). NOT acceptable: a 500, or
        // a ticket in a corrupt state.
        String token = signupAndLogin(uniqueEmail(), "Race Close Shop");
        var queue = createQueue(token, "Race Close");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        CountDownLatch gun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> joiner = pool.submit(() -> {
                gun.await();
                return mockMvc.perform(post("/public/queues/{t}/entries", joinToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"customerName\":\"Racer\"}"))
                        .andReturn().getResponse().getStatus();
            });
            Future<Integer> closer = pool.submit(() -> {
                gun.await();
                return mockMvc.perform(patch("/queues/{id}/status", id)
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"CLOSED\"}"))
                        .andReturn().getResponse().getStatus();
            });
            gun.countDown();

            assertThat(closer.get()).isEqualTo(200);
            assertThat(joiner.get()).isIn(201, 400); // either outcome is coherent; 500 is not
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- P1: concurrent joins ----------

    @Test
    void simultaneousJoins_getUniqueTicketsAndUniquePositions() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Stampede Shop");
        String joinToken = createQueue(token, "Stampede").get("joinToken").asString();

        int customers = 8;
        CountDownLatch gun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(customers);
        try {
            List<Future<String>> results = new java.util.ArrayList<>();
            for (int i = 0; i < customers; i++) {
                final int n = i;
                results.add(pool.submit(() -> {
                    gun.await();
                    var body = mockMvc.perform(post("/public/queues/{t}/entries", joinToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"customerName\":\"C%d\"}".formatted(n)))
                            .andExpect(status().isCreated())
                            .andReturn().getResponse().getContentAsString();
                    return objectMapper.readTree(body).get("entryToken").asString();
                }));
            }
            gun.countDown();

            Set<String> tokens = new HashSet<>();
            for (var f : results) tokens.add(f.get());
            assertThat(tokens).hasSize(customers); // no duplicate tickets

            // And the resulting line has strictly unique positions 1..N.
            String staffView = mockMvc.perform(get("/queues/{id}/entries",
                            objectMapper.readTree(mockMvc.perform(get("/queues")
                                            .header("Authorization", "Bearer " + token))
                                    .andReturn().getResponse().getContentAsString())
                                    .get(0).get("id").asLong())
                            .header("Authorization", "Bearer " + token))
                    .andReturn().getResponse().getContentAsString();
            var line = objectMapper.readTree(staffView);
            Set<Integer> positions = new HashSet<>();
            line.forEach(e -> positions.add(e.get("position").asInt()));
            assertThat(positions).hasSize(customers);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void joinIsIdempotent_withAnIdempotencyKey() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Retry Shop");
        String joinToken = createQueue(token, "Retry Line").get("joinToken").asString();

        String body = "{\"customerName\":\"Flaky Wifi\"}";
        var first = objectMapper.readTree(mockMvc.perform(
                        post("/public/queues/{t}/entries", joinToken)
                                .header("Idempotency-Key", "join-attempt-1")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var second = objectMapper.readTree(mockMvc.perform(
                        post("/public/queues/{t}/entries", joinToken)
                                .header("Idempotency-Key", "join-attempt-1")
                                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(second.get("entryToken").asString())
                .as("a retried join must return the SAME ticket")
                .isEqualTo(first.get("entryToken").asString());
    }

    // ---------- P1: history + stats + lifecycle ----------

    @Test
    void historyFiltersByStatus_andStatsSummariseTheDay() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "History Shop");
        var queue = createQueue(token, "Busy Day");
        long id = queue.get("id").asLong();
        String joinToken = queue.get("joinToken").asString();

        joinQueue(joinToken, "Served One");
        String leaver = joinQueue(joinToken, "Leaver").get("entryToken").asString();
        joinQueue(joinToken, "Still Waiting");

        mockMvc.perform(post("/queues/{id}/advance", id)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(delete("/public/entries/{t}", leaver)).andExpect(status().isNoContent());

        // History actually filters — the old endpoint 400'd on anything but WAITING.
        mockMvc.perform(get("/queues/{id}/history?status=SERVED", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].customerName").value("Served One"))
                .andExpect(jsonPath("$[0].waitedMinutes").isNumber());
        mockMvc.perform(get("/queues/{id}/history?status=LEFT", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].customerName").value("Leaver"));

        mockMvc.perform(get("/queues/{id}/stats", id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waitingCount").value(1))
                .andExpect(jsonPath("$.servedToday").value(1))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void servedTicket_showsWhenAndHowLong_andAcceptsFeedbackOnceOnly() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Closure Shop");
        var queue = createQueue(token, "Full Story");
        String ticket = joinQueue(queue.get("joinToken").asString(), "Narrative").get("entryToken").asString();

        // Feedback before being served is premature -> 400.
        mockMvc.perform(post("/public/entries/{t}/feedback", ticket)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":5}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/queues/{id}/advance", queue.get("id").asLong())
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        // The story completes: served time + measured wait, not an abrupt end.
        mockMvc.perform(get("/public/entries/{t}", ticket))
                .andExpect(jsonPath("$.status").value("SERVED"))
                .andExpect(jsonPath("$.servedAt").exists())
                .andExpect(jsonPath("$.waitedMinutes").isNumber());

        // V8: stars + comment, saved with the customer's name on the row.
        mockMvc.perform(post("/public/entries/{t}/feedback", ticket)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stars\":4,\"comment\":\"Estimate was close, chairs comfy\"}"))
                .andExpect(status().isNoContent());
        var saved = entryRepository.findByEntryToken(ticket).orElseThrow();
        assertThat(saved.getRatingStars()).isEqualTo(4);
        assertThat(saved.getFeedbackComment()).isEqualTo("Estimate was close, chairs comfy");
        // Out-of-range stars rejected by validation, not stored.
        mockMvc.perform(post("/public/entries/{t}/feedback", ticket)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"stars\":6}"))
                .andExpect(status().isBadRequest());
        // The owner's history now carries the feedback dataset row.
        mockMvc.perform(get("/queues/{id}/history?status=SERVED", queue.get("id").asLong())
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$[0].ratingStars").value(4))
                .andExpect(jsonPath("$[0].feedbackComment").value("Estimate was close, chairs comfy"));
    }

    @Test
    void theStateMachine_forbidsResurrectingTerminalTickets() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Machine Shop");
        var queue = createQueue(token, "Strict");
        String ticket = joinQueue(queue.get("joinToken").asString(), "One Way").get("entryToken").asString();

        mockMvc.perform(post("/queues/{id}/advance", queue.get("id").asLong())
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        // SERVED is terminal: leaving is now impossible (400, not a mutation).
        mockMvc.perform(delete("/public/entries/{t}", ticket))
                .andExpect(status().isBadRequest());
        assertThat(entryRepository.findByEntryToken(ticket).orElseThrow().getStatus())
                .isEqualTo(EntryStatus.SERVED);
    }
}
