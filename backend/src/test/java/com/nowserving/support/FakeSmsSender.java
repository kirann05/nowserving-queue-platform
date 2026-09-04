package com.nowserving.support;

import com.nowserving.notification.PushMessage;
import com.nowserving.notification.SmsSender;

import java.util.ArrayList;
import java.util.List;

/**
 * No real Twilio in tests — same reasoning as {@link FakeNotificationChannel}:
 * a paid, network-dependent provider must never be on the path of a test run.
 */
public class FakeSmsSender implements SmsSender {

    public record Sent(String phoneNumber, PushMessage message) {}

    private final List<Sent> sent = new ArrayList<>();
    private volatile Result nextResult = Result.SENT;

    public void willReturn(Result result) {
        this.nextResult = result;
    }

    public List<Sent> sent() {
        return sent;
    }

    @Override
    public Result send(String phoneNumber, PushMessage message) {
        sent.add(new Sent(phoneNumber, message));
        return nextResult;
    }

    /** Available unless a test has explicitly said SMS is unconfigured. */
    @Override
    public boolean isAvailable() {
        return nextResult != Result.NOT_CONFIGURED;
    }
}
