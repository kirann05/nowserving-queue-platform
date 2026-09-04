package com.nowserving;

import com.nowserving.entity.Queue;
import com.nowserving.entity.ServiceSample;
import com.nowserving.repository.QueueRepository;
import com.nowserving.repository.ServiceSampleRepository;
import com.nowserving.service.WaitEstimator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 3 / PRD §3.8.2: wait estimates from measured data, using a MEDIAN.
 */
class WaitEstimateIntegrationTest extends AbstractIntegrationTest {

    @Autowired private WaitEstimator waitEstimator;
    @Autowired private ServiceSampleRepository sampleRepository;
    @Autowired private QueueRepository queueRepository;
    @Autowired private StringRedisTemplate redis;

    private Queue newQueue(String token, String name) throws Exception {
        long id = createQueue(token, name).get("id").asLong();
        return queueRepository.findById(id).orElseThrow();
    }

    /** Samples are cached for 30s; clear so each test sees its own data. */
    private void clearEstimateCache(Long queueId) {
        redis.delete("estimate:median:" + queueId);
    }

    @Test
    void withTooFewSamples_weFallBackToTheOwnersConfiguredGuess() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Cold Start Shop");
        var created = mockMvc.perform(post("/queues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cold\",\"stationCount\":2,\"defaultServiceMinutes\":10}"))
                .andExpect(status().isCreated()).andReturn();
        Queue queue = queueRepository.findById(objectMapper
                .readTree(created.getResponse().getContentAsString()).get("id").asLong()).orElseThrow();

        // Two samples is not enough to trust a median (MIN_SAMPLES = 5).
        sampleRepository.save(new ServiceSample(queue, 60, Instant.now()));
        sampleRepository.save(new ServiceSample(queue, 60, Instant.now()));
        clearEstimateCache(queue.getId());

        // Falls back to configured: 3 ahead x 10 min / 2 stations = 15.
        assertThat(waitEstimator.estimateMinutes(queue, 3)).isEqualTo(15);
    }

    @Test
    void withEnoughSamples_weUseTheMeasuredMedian() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Measured Shop");
        Queue queue = newQueue(token, "Measured");

        // Five services, each 120 seconds = 2 minutes.
        for (int i = 0; i < 5; i++) {
            sampleRepository.save(new ServiceSample(queue, 120, Instant.now()));
        }
        clearEstimateCache(queue.getId());

        // 3 people ahead x 2 min = 6 — from data, not from the 15-min default.
        assertThat(waitEstimator.estimateMinutes(queue, 3)).isEqualTo(6);
    }

    @Test
    void oneAbsurdOutlierDoesNotWreckTheEstimate() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Outlier Shop");
        Queue queue = newQueue(token, "Outlier");

        // Four normal services of 2 minutes...
        for (int i = 0; i < 4; i++) {
            sampleRepository.save(new ServiceSample(queue, 120, Instant.now()));
        }
        // ...and one customer who vanished to find an ATM for an hour.
        sampleRepository.save(new ServiceSample(queue, 3600, Instant.now()));
        clearEstimateCache(queue.getId());

        // MEAN would be (120*4 + 3600)/5 = 816s = ~14 min per person -> 14 for
        // one ahead. MEDIAN is 120s = 2 min. This single assertion is the
        // whole argument for median over mean.
        assertThat(waitEstimator.estimateMinutes(queue, 1)).isEqualTo(2);
    }

    @Test
    void noOneAhead_meansNoWait() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Empty Estimate Shop");
        Queue queue = newQueue(token, "Front");
        assertThat(waitEstimator.estimateMinutes(queue, 0)).isZero();
    }

    @Test
    void advancingTheLineRecordsAServiceSample() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Sampling Shop");
        var queueJson = createQueue(token, "Sampled");
        String joinToken = queueJson.get("joinToken").asString();
        long queueId = queueJson.get("id").asLong();

        joinQueue(joinToken, "One");
        joinQueue(joinToken, "Two");
        joinQueue(joinToken, "Three");

        // First advance: no previous advance, so nothing to measure yet.
        mockMvc.perform(post("/queues/{id}/advance", queueId)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(sampleRepository.countByQueueId(queueId)).isZero();

        Thread.sleep(1100); // let a measurable gap elapse

        // Second advance: the gap since the first becomes one observation.
        mockMvc.perform(post("/queues/{id}/advance", queueId)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        assertThat(sampleRepository.countByQueueId(queueId)).isEqualTo(1);
    }
}
