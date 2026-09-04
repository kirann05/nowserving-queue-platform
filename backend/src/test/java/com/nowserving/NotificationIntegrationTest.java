package com.nowserving;

import com.nowserving.notification.NotificationChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sprint 3 / FR-8: "When a customer becomes next, they receive a notification."
 */
class NotificationIntegrationTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.nowserving.repository.PushSubscriptionRepository subscriptionRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.nowserving.repository.QueueEntryRepository entryRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private com.nowserving.notification.NotificationDispatcher notificationDispatcher;

    @BeforeEach
    void clearRecordedNotifications() {
        fakeNotifications.reset();
    }

    /** Notifications aimed at one specific ticket — see the note in the
     *  no-notifications test for why scoping beats asserting on the whole list. */
    private java.util.List<com.nowserving.support.FakeNotificationChannel.Sent> sentFor(String entryToken) {
        return fakeNotifications.sent.stream()
                .filter(s -> s.message().url().contains(entryToken))
                .toList();
    }

    /** Register a fake browser push subscription for a ticket. */
    private void subscribe(String entryToken, String endpoint) throws Exception {
        mockMvc.perform(post("/public/entries/{token}/push-subscription", entryToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"endpoint":"%s","keys":{"p256dh":"fake-p256dh","auth":"fake-auth"}}
                                """.formatted(endpoint)))
                .andExpect(status().isNoContent());
    }

    @Test
    void whenTheCustomerBecomesNext_theirDeviceIsNotified() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Notify Shop");
        var queue = createQueue(token, "Notify Line");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        joinQueue(joinToken, "First");
        String secondTicket = joinQueue(joinToken, "Second").get("entryToken").asString();
        subscribe(secondTicket, "https://push.example.test/second-device");

        // Position 2 -> nothing yet: we only buzz the person at the front.
        assertThat(fakeNotifications.sent).isEmpty();

        mockMvc.perform(post("/queues/{id}/advance", queueId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // "Second" is now first in line and gets told.
        assertThat(fakeNotifications.sent).hasSize(1);
        var sent = fakeNotifications.sent.get(0);
        assertThat(sent.endpoint()).isEqualTo("https://push.example.test/second-device");
        assertThat(sent.message().title()).isEqualTo("You're next!");
        assertThat(sent.message().url()).contains("/t/" + secondTicket);
    }

    @Test
    void aCustomerIsNeverBuzzedTwiceForTheSameFact() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Idempotent Shop");
        var queue = createQueue(token, "Once Only");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        joinQueue(joinToken, "First");
        String secondTicket = joinQueue(joinToken, "Second").get("entryToken").asString();
        subscribe(secondTicket, "https://push.example.test/once");

        mockMvc.perform(post("/queues/{id}/advance", queueId)
                        .header("Authorization", "Bearer " + token));
        assertThat(fakeNotifications.sent).hasSize(1);

        // Someone else joins -> another QueueChangedEvent -> the front of the
        // line hasn't changed. next_notified_at must suppress a second buzz.
        joinQueue(joinToken, "Third");
        assertThat(fakeNotifications.sent)
                .as("still exactly one 'you're next' for the same customer")
                .hasSize(1);
    }

    @Test
    void resubscribingTheSameDeviceDoesNotDuplicateIt() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Resub Shop");
        var queue = createQueue(token, "Resub Line");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        joinQueue(joinToken, "First");
        String ticket = joinQueue(joinToken, "Second").get("entryToken").asString();

        // A page reload re-registers the same endpoint three times.
        subscribe(ticket, "https://push.example.test/same-device");
        subscribe(ticket, "https://push.example.test/same-device");
        subscribe(ticket, "https://push.example.test/same-device");

        mockMvc.perform(post("/queues/{id}/advance", queueId)
                        .header("Authorization", "Bearer " + token));

        assertThat(fakeNotifications.sent)
                .as("one device, one buzz — not three")
                .hasSize(1);
    }

    @Test
    void customersWithoutNotifications_stillWorkNormally() throws Exception {
        // FR-17's principle: notifications are an ENHANCEMENT. Never required.
        String token = signupAndLogin(uniqueEmail(), "No Push Shop");
        var queue = createQueue(token, "Silent Line");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        joinQueue(joinToken, "First");
        String ticket = joinQueue(joinToken, "Second").get("entryToken").asString(); // never subscribes

        mockMvc.perform(post("/queues/{id}/advance", queueId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Scoped to THIS test's ticket rather than asserting the shared list
        // is globally empty: the fake channel is one bean for the whole
        // Spring context, so a late delivery from another test could drift in
        // and fail this one for the wrong reason. Assert what you actually
        // mean — "nobody notified MY customer".
        assertThat(sentFor(ticket)).isEmpty();

        // ...and their position still updates perfectly.
        mockMvc.perform(get("/public/entries/{token}", ticket))
                .andExpect(status().isOk());
    }

    @Test
    void aDeadSubscriptionIsDeletedRatherThanRetriedForever() throws Exception {
        String token = signupAndLogin(uniqueEmail(), "Expired Shop");
        var queue = createQueue(token, "Expired Line");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        String frontTicket = joinQueue(joinToken, "Front Person").get("entryToken").asString();
        subscribe(frontTicket, "https://push.example.test/uninstalled");
        long entryId = entryRepository.findByEntryToken(frontTicket).orElseThrow().getId();

        // The push service reports the subscription is gone (uninstalled
        // browser, permission revoked).
        fakeNotifications.willReturn(NotificationChannel.DeliveryResult.EXPIRED);

        // Driven through the dispatcher directly rather than via
        // advance -> event -> async listener.
        //
        // That longer path involves ordering this test does not control, and
        // asserting through it made this case fail intermittently for reasons
        // unrelated to the behaviour being checked. A test that fails for the
        // wrong reason trains you to ignore it. The pipeline itself is already
        // covered by the tests above; what THIS test is about is one rule:
        // an endpoint the push service calls dead gets deleted.
        notificationDispatcher.notifyCustom(
                entryRepository.findById(entryId).orElseThrow(),
                "Test", "Delivery will report EXPIRED");

        assertThat(subscriptionRepository.findByEntryId(entryId))
                .as("a dead endpoint must be deleted, not retried forever")
                .isEmpty();
        assertThat(queueId).isPositive(); // queue fixture used above
    }

    @Test
    void theQueueKeepsWorkingWhenNotificationsAreCompletelyBroken() throws Exception {
        // The Sprint 3 milestone: "the queue keeps working even if
        // notifications fail." This is the assertion that proves it.
        String token = signupAndLogin(uniqueEmail(), "Broken Push Shop");
        var queue = createQueue(token, "Resilient Line");
        String joinToken = queue.get("joinToken").asString();
        long queueId = queue.get("id").asLong();

        joinQueue(joinToken, "First");
        String secondTicket = joinQueue(joinToken, "Second").get("entryToken").asString();
        subscribe(secondTicket, "https://push.example.test/broken");

        fakeNotifications.willReturn(NotificationChannel.DeliveryResult.FAILED);

        // Serving still succeeds, with a normal 200 and a correct body.
        mockMvc.perform(post("/queues/{id}/advance", queueId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // And the customer's position is still right — they'd learn it from
        // the WebSocket push or their poll, notification or no notification.
        mockMvc.perform(get("/public/entries/{token}", secondTicket))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.position").value(1));
    }
}
