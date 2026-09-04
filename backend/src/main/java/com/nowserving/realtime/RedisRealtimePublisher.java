package com.nowserving.realtime;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Publishes every realtime message onto one Redis channel.
 *
 * NOTE THE THING THAT LOOKS LIKE A BUG AND ISN'T: this class never touches
 * the local WebSocket sockets, not even for the instance it is running on.
 * Redis pub/sub delivers to *every* subscriber, and this instance is a
 * subscriber too — so the message comes straight back to us and
 * {@link RedisRealtimeSubscriber} does the local send.
 *
 * Doing it that way means there is exactly ONE code path for delivering a
 * message, whether you run one instance or fifty. The alternative ("send
 * locally AND publish to Redis, then filter out my own echo") needs
 * instance-id bookkeeping and gives you two paths that can drift apart.
 * One path you can test beats two paths you hope match.
 */
@Component
@RequiredArgsConstructor
public class RedisRealtimePublisher implements RealtimePublisher {

    /** Every instance publishes to and subscribes to this one channel. */
    public static final String CHANNEL = "nowserving:realtime";

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimePublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(String destination, Object payload) {
        try {
            RealtimeMessage envelope =
                    new RealtimeMessage(destination, objectMapper.writeValueAsString(payload));
            redis.convertAndSend(CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            // DELIBERATE: a realtime push is a nice-to-have, the queue is not.
            // The database transaction has already committed by the time we
            // get here, so the line is correct no matter what happens next.
            // If Redis is down, customers simply fall back to the 30-second
            // poll their page already does. Rethrowing would turn "the live
            // update was late" into "the staff request failed", which is the
            // NFR the PRD calls out: a failing side-service must not take
            // down queueing.
            log.error("Realtime publish to {} failed; clients will fall back to polling", destination, e);
        }
    }
}
