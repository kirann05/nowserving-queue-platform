package com.nowserving;

import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * NS-4: tenant isolation — "the #1 bug class in multi-tenant apps".
 * Owner A must never see, touch, or even CONFIRM THE EXISTENCE of owner B's
 * data. Hence 404 (not 403) on cross-tenant access.
 */
class QueueTenantIsolationTest extends AbstractIntegrationTest {

    @Test
    void createQueue_belongsToMyBusiness_andHasJoinToken() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Queue Shop");

        var queue = createQueue(token, "Walk-ins");
        org.assertj.core.api.Assertions.assertThat(queue.get("joinToken").asString()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(queue.get("joinUrl").asString())
                .contains("/j/" + queue.get("joinToken").asString());

        // It shows up in MY list...
        mockMvc.perform(get("/queues").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Walk-ins"));
    }

    @Test
    void anotherOwnersQueue_is404_neverTheData() throws Exception {
        String ownerA = signupAndLogin(uniqueEmail(), "Shop A");
        String ownerB = signupAndLogin(uniqueEmail(), "Shop B");

        long queueAId = createQueue(ownerA, "A's Queue").get("id").asLong();

        // B requests A's queue by its real id: 404 — the response must not
        // even acknowledge the queue exists (403 would confirm it does).
        mockMvc.perform(get("/queues/{id}", queueAId).header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());

        // Same wall on every other tenant-scoped route:
        mockMvc.perform(get("/queues/{id}/entries", queueAId).header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/queues/{id}/advance", queueAId).header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isNotFound());

        // And B's queue list does not contain A's queue.
        mockMvc.perform(get("/queues").header("Authorization", "Bearer " + ownerB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // Owner A, of course, still sees their own queue fine.
        mockMvc.perform(get("/queues/{id}", queueAId).header("Authorization", "Bearer " + ownerA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("A's Queue"));
    }
}
