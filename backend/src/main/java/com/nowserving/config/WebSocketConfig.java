package com.nowserving.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * Sprint 2: the real-time transport.
 *
 * Concepts, in one breath:
 *  - A WebSocket is a persistent two-way pipe (starts as an HTTP request,
 *    then "upgrades"). Unlike HTTP, the SERVER can send whenever it wants.
 *  - STOMP is a tiny text protocol ON TOP of the socket that gives us
 *    channels ("topics"), SUBSCRIBE and SEND frames — so we don't invent our
 *    own message format.
 *  - The "simple broker" is an in-memory post office inside this JVM: it
 *    remembers who subscribed to which /topic/** and fans messages out.
 *
 * Topic design (who listens where):
 *    /topic/entries/{entryToken}  — one customer's ticket. The token is
 *                                   unguessable, so possession of the URL is
 *                                   the authorization — the same capability-
 *                                   token idea as the REST endpoint.
 *    /topic/queues/{queueId}      — staff dashboards for a queue. Carries
 *                                   only a "line changed" ping (no customer
 *                                   data), because simple-broker topics can't
 *                                   easily check a JWT per subscription.
 *
 * KNOWN LIMIT (accepted, documented): the simple broker lives in ONE JVM.
 * Run two instances behind a load balancer and each only reaches its own
 * sockets — that's exactly the problem Redis pub/sub solves later in the
 * sprint plan.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // ws://localhost:8080/ws — the handshake URL the frontend connects to.
        // Browsers enforce origin checks on WebSocket handshakes too, so the
        // same origins we allow for CORS are allowed here.
        registry.addEndpoint("/ws")
                .setAllowedOrigins(allowedOrigins.toArray(String[]::new));
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Server -> client destinations start with /topic (the simple broker).
        registry.enableSimpleBroker("/topic");
        // Client -> server destinations would start with /app; Sprint 2 has
        // none (clients only listen), but the convention is wired for later.
        registry.setApplicationDestinationPrefixes("/app");
    }
}
