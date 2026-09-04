package com.nowserving;

import com.nowserving.entity.QueueStatus;
import com.nowserving.repository.QueueRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NS-5 (join), NS-6 (check position), NS-7 (staff view) acceptance criteria.
 */
class PublicJoinPositionTest extends AbstractIntegrationTest {

    @Autowired
    private QueueRepository queueRepository;

    @Test
    void customersJoin_andReceiveSequentialPositions() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Join Shop");
        String joinToken = createQueue(token, "Walk-ins").get("joinToken").asString();

        // "Given an open queue with 3 people waiting, when a customer joins,
        //  then they receive position 4 and an entry_token"
        assertThat(joinQueue(joinToken, "Customer 1").get("position").asInt()).isEqualTo(1);
        assertThat(joinQueue(joinToken, "Customer 2").get("position").asInt()).isEqualTo(2);
        assertThat(joinQueue(joinToken, "Customer 3").get("position").asInt()).isEqualTo(3);

        var fourth = joinQueue(joinToken, "Customer 4");
        assertThat(fourth.get("position").asInt()).isEqualTo(4);
        assertThat(fourth.get("entryToken").asString()).isNotBlank();
    }

    @Test
    void positionCheck_reflectsQueueConfiguration() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Estimate Shop");
        // 10 minutes per customer, 2 stations -> 3 people ahead = ceil(30/2) = 15 min
        var created = mockMvc.perform(post("/queues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Configured\",\"stationCount\":2,\"defaultServiceMinutes\":10}"))
                .andExpect(status().isCreated())
                .andReturn();
        String joinToken = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("joinToken").asString();

        joinQueue(joinToken, "Ahead 1");
        joinQueue(joinToken, "Ahead 2");
        joinQueue(joinToken, "Ahead 3");
        String myTicket = joinQueue(joinToken, "Me").get("entryToken").asString();

        // "Given I am 4th in line, when I GET my entry, then position = 4 and
        //  estimatedMinutes reflects the queue's configuration"
        mockMvc.perform(get("/public/entries/{token}", myTicket))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(4))
                .andExpect(jsonPath("$.peopleAhead").value(3))
                .andExpect(jsonPath("$.estimatedMinutes").value(15));
    }

    @Test
    void closedQueue_rejectsJoins_with400() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Closed Shop");
        var queue = createQueue(token, "Closing Soon");

        // No "close queue" endpoint in Sprint 1 (it's not in the backlog), so
        // flip the status directly in the DB to simulate a closed queue.
        var entity = queueRepository.findById(queue.get("id").asLong()).orElseThrow();
        entity.setStatus(QueueStatus.CLOSED);
        queueRepository.save(entity);

        mockMvc.perform(post("/public/queues/{token}/entries", queue.get("joinToken").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Too Late\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "This queue is currently closed and not accepting new customers"));
    }

    @Test
    void invalidTokens_return404() throws Exception {
        mockMvc.perform(post("/public/queues/{token}/entries", "no-such-queue-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"Lost\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/public/entries/{token}", "no-such-entry-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffView_listsWaitingInJoinOrder_withPositions() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Staff View Shop");
        var queue = createQueue(token, "The Line");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        for (int i = 1; i <= 5; i++) {
            joinQueue(joinToken, "Person " + i);
        }

        // "Given 5 people are waiting ... all 5 return in join order with
        //  correct positions" (cross-tenant leakage is covered in
        //  QueueTenantIsolationTest).
        mockMvc.perform(get("/queues/{id}/entries?status=WAITING", queueId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].customerName").value("Person 1"))
                .andExpect(jsonPath("$[0].position").value(1))
                .andExpect(jsonPath("$[4].customerName").value("Person 5"))
                .andExpect(jsonPath("$[4].position").value(5));
    }
}
