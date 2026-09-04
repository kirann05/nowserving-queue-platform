package com.nowserving.realtime;

/**
 * The envelope we put on the Redis bus: "send THIS to THAT destination".
 *
 * Why payloadJson is a String rather than an Object: deserialising arbitrary
 * Java types from a message bus means telling Jackson to trust type names
 * inside the message ("polymorphic deserialisation"), which is a long-running
 * source of remote-code-execution bugs. Keeping the payload as opaque JSON
 * text means the bus can never be tricked into constructing a class we didn't
 * intend. The subscriber re-parses it as plain data.
 */
public record RealtimeMessage(String destination, String payloadJson) {
}
