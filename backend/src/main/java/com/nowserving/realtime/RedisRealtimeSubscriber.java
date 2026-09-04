package com.nowserving.realtime;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

/**
 * The other half of the bus: runs on EVERY instance, listens to the shared
 * Redis channel, and delivers each message to the WebSocket sockets that
 * THIS instance happens to be holding.
 *
 * So the full journey of "staff tapped Next" with two instances running:
 *
 *   instance B: commit to Postgres
 *             → publish envelope to Redis
 *   Redis     : broadcast to all subscribers
 *   instance A: this class receives it → pushes down its own sockets
 *             → the customer's phone updates
 *   instance B: also receives it (it subscribes too) → pushes its sockets
 *
 * Nobody had to know which instance held which connection. That decoupling
 * is the whole point, and it is the same shape as the PRD's event bus in
 * §3.1 — just with the WebSocket Gateway still living inside the monolith
 * rather than as its own deployable service.
 */
@Component
@RequiredArgsConstructor
public class RedisRealtimeSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisRealtimeSubscriber.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RealtimeMessage envelope = objectMapper.readValue(
                    new String(message.getBody(), StandardCharsets.UTF_8), RealtimeMessage.class);

            // Re-read the payload as plain maps/lists rather than as a typed
            // class (see RealtimeMessage for why). Jackson then serialises it
            // straight back out to the socket, so the JSON the browser sees is
            // byte-for-byte what the publisher produced.
            Object payload = objectMapper.readValue(envelope.payloadJson(), Object.class);

            // The (Object) cast picks the right overload — a Map payload would
            // otherwise also match convertAndSend(destination, headers).
            messagingTemplate.convertAndSend(envelope.destination(), (Object) payload);
        } catch (Exception e) {
            // One malformed message must not kill the listener thread and
            // take realtime down for everyone on this instance.
            log.error("Could not relay a realtime message from Redis", e);
        }
    }
}
