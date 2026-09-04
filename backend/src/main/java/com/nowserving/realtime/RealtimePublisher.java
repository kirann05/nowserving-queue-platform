package com.nowserving.realtime;

/**
 * "Push this to every browser subscribed to that destination — no matter
 * which server instance they happen to be connected to."
 *
 * THE PROBLEM THIS SOLVES (the heart of Sprint 2):
 * a WebSocket is a long-lived connection pinned to ONE server process. With
 * two instances behind a load balancer:
 *
 *      customer's phone ── socket ──> instance A
 *      staff tablet     ── HTTP  ──> instance B   (taps "Next")
 *
 * Instance B updates the database, then tries to push. But B holds no socket
 * to that phone — A does. Before this interface existed, the push simply
 * vanished and the customer waited for their 30-second poll. That is the
 * exact bug the PRD's §3.2 predicts when it says the WebSocket layer "must
 * scale horizontally without dropping updates."
 *
 * The fix: nobody pushes directly. Everybody publishes to a shared bus, and
 * every instance relays what it hears to its OWN sockets.
 */
public interface RealtimePublisher {

    /**
     * @param destination a STOMP topic, e.g. {@code /topic/entries/{token}}
     * @param payload     any object; it is serialised to JSON for transport
     */
    void publish(String destination, Object payload);
}
