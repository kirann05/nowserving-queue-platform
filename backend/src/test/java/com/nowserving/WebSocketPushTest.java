package com.nowserving;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 2: proves the real-time promise end-to-end INSIDE the JVM boundary —
 * a real STOMP client connects to a real server socket, subscribes to a
 * customer's entry topic, staff advances over HTTP, and the customer receives
 * a push without ever polling.
 *
 * webEnvironment = RANDOM_PORT boots a real Tomcat (unlike the MockMvc-only
 * default) because a WebSocket handshake needs an actual TCP port.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Re-stated because this annotation replaces the parent's, and the
        // synchronous test executor still needs to override the real one.
        properties = "spring.main.allow-bean-definition-overriding=true")
class WebSocketPushTest extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    void advancingTheLine_pushesFreshPositionToSubscribedCustomer() throws Exception {
        // --- arrange: owner, queue, two customers (over the normal API) ---
        String token = signupAndLogin(uniqueEmail(), "Realtime Shop");
        var queue = createQueue(token, "Live Line");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        joinQueue(joinToken, "Front Person");
        String watcherToken = joinQueue(joinToken, "Watcher").get("entryToken").asString();

        // --- connect a real STOMP client, as the Watcher's browser would ---
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        // SimpleMessageConverter passes raw byte[] through regardless of the
        // frame's content-type (the server stamps application/json, which a
        // StringMessageConverter would silently refuse). We parse ourselves.
        stompClient.setMessageConverter(new SimpleMessageConverter());

        WebSocketHttpHeaders handshakeHeaders = new WebSocketHttpHeaders();
        handshakeHeaders.setOrigin("http://localhost:5173"); // pass the allowed-origins check

        StompSession session = stompClient
                .connectAsync("ws://localhost:" + port + "/ws", handshakeHeaders,
                        new StompSessionHandlerAdapter() {})
                .get(5, TimeUnit.SECONDS);

        BlockingQueue<String> received = new ArrayBlockingQueue<>(10);
        session.subscribe("/topic/entries/" + watcherToken, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
            }
        });
        Thread.sleep(300); // let the SUBSCRIBE frame register with the broker

        // --- act: staff serves the front person ---
        mockMvc.perform(post("/queues/{id}/advance", queueId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // --- assert: the Watcher was PUSHED position 1 (no polling involved) ---
        String message = received.poll(5, TimeUnit.SECONDS);
        assertThat(message).as("expected a pushed position update within 5s").isNotNull();
        var payload = objectMapper.readTree(message);
        assertThat(payload.get("status").asString()).isEqualTo("WAITING");
        assertThat(payload.get("position").asInt()).isEqualTo(1);
        assertThat(payload.get("peopleAhead").asInt()).isEqualTo(0);

        session.disconnect();
        stompClient.stop();
    }
}
