package com.nowserving;

import com.nowserving.dto.QueueDtos.AdvanceResponse;
import com.nowserving.entity.EntryStatus;
import com.nowserving.repository.QueueEntryRepository;
import com.nowserving.service.QueueService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NS-8 ⭐ — the most important test in the sprint.
 *
 * The bug this guards against: "advance" is read-then-write (find the front
 * entry, mark it served). Two staff tablets tapping "Next" in the same instant
 * both READ the same front entry before either WRITES — and the same customer
 * gets served twice while someone else is skipped. This is a race condition:
 * code that is perfectly correct alone, wrong in company.
 *
 * The sprint doc's advice is to write the naive version first and WATCH this
 * test fail — try it: replace lockNextWaiting() in QueueService.advance with
 * findFirstByQueueIdAndStatusOrderByJoinedAtAscIdAsc() and run this test. The
 * FOR UPDATE SKIP LOCKED query is what makes it pass.
 */
class AdvanceConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private QueueService queueService;

    @Autowired
    private QueueEntryRepository entryRepository;

    @Test
    void advance_servesFront_andSecondPersonMovesUp() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Advance Shop");
        var queue = createQueue(token, "Single File");
        String joinToken = queue.get("joinToken").asString();

        joinQueue(joinToken, "First");
        joinQueue(joinToken, "Second");
        String thirdTicket = joinQueue(joinToken, "Third").get("entryToken").asString();

        // "When staff advances the queue, then person 1 is SERVED and persons
        //  2 and 3 become positions 1 and 2"
        mockMvc.perform(post("/queues/{id}/advance", queue.get("id").asLong())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.served.customerName").value("First"))
                .andExpect(jsonPath("$.nextUp.customerName").value("Second"))
                .andExpect(jsonPath("$.nextUp.position").value(1));

        mockMvc.perform(get("/public/entries/{token}", thirdTicket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(2));
    }

    @Test
    void advance_onEmptyQueue_is400_notA500() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Empty Shop");
        long queueId = createQueue(token, "Nobody Here").get("id").asLong();

        mockMvc.perform(post("/queues/{id}/advance", queueId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No one is waiting in this queue"));
    }

    @Test
    void noShow_removesEntryFromLine_andOnlyWaitingCanBeNoShowed() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "NoShow Shop");
        var queue = createQueue(token, "Ghost Line");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        joinQueue(joinToken, "Ghost");
        String stayerTicket = joinQueue(joinToken, "Stayer").get("entryToken").asString();

        // Find Ghost's entry id from the staff view (position 1).
        var line = objectMapper.readTree(mockMvc.perform(get("/queues/{id}/entries", queueId)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        long ghostId = line.get(0).get("entryId").asLong();

        // DELETE -> 204, Ghost leaves the line, Stayer moves to position 1.
        mockMvc.perform(delete("/entries/{id}", ghostId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/public/entries/{token}", stayerTicket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(1));

        // A NO_SHOW entry can't be no-showed again -> 400 (only WAITING can).
        mockMvc.perform(delete("/entries/{id}", ghostId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        // And another tenant can't no-show my entries -> 404, never 403.
        String otherOwner = signupAndLogin(uniqueEmail(), "Other Shop");
        long stayerId = objectMapper.readTree(mockMvc.perform(get("/queues/{id}/entries", queueId)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get(0).get("entryId").asLong();
        mockMvc.perform(delete("/entries/{id}", stayerId)
                        .header("Authorization", "Bearer " + otherOwner))
                .andExpect(status().isNotFound());
    }

    @Test
    void twoSimultaneousAdvances_serveTwoDifferentPeople() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Race Shop");
        var queue = createQueue(token, "Race Queue");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        joinQueue(joinToken, "Racer 1");
        joinQueue(joinToken, "Racer 2");
        joinQueue(joinToken, "Racer 3");

        // Look up the businessId the same way the controller would (from /me),
        // then call the SERVICE directly so we control the threads precisely.
        var me = objectMapper.readTree(mockMvc.perform(get("/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        long businessId = me.get("business").get("id").asLong();

        // The latch releases both threads at the same moment — without it the
        // first task often COMMITS before the second even starts, and the test
        // would "pass accidentally" without ever exercising the race (the
        // sprint doc's "likely problem #5").
        int simultaneousTaps = 2;
        CountDownLatch startGun = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(simultaneousTaps);
        try {
            Callable<AdvanceResponse> tapNext = () -> {
                startGun.await();
                return queueService.advance(businessId, queueId);
            };
            List<Future<AdvanceResponse>> results =
                    List.of(pool.submit(tapNext), pool.submit(tapNext));
            startGun.countDown(); // GO — both threads hit the DB together

            Set<Long> servedIds = results.stream()
                    .map(f -> {
                        try {
                            return f.get().served().entryId();
                        } catch (Exception e) {
                            throw new AssertionError("advance threw under concurrency", e);
                        }
                    })
                    .collect(Collectors.toSet());

            // "exactly two DIFFERENT people are served — never the same person
            //  twice". A Set collapses duplicates: size 1 = the race happened.
            assertThat(servedIds).hasSize(2);

            // Cross-check in the DB: exactly 2 SERVED, exactly 1 still WAITING.
            assertThat(entryRepository.countByQueueIdAndStatus(queueId, EntryStatus.SERVED)).isEqualTo(2);
            assertThat(entryRepository.countByQueueIdAndStatus(queueId, EntryStatus.WAITING)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
