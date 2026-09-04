package com.nowserving.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * SMS delivery via Twilio's REST API — deliberately the JDK's HttpClient
 * rather than the Twilio SDK, matching {@link com.nowserving.travel.TomTomTravelTimeProvider}.
 *
 * WHY NO SDK: the entire integration is one form POST with basic auth. Adding
 * a dependency and its transitive tree so that it can sit unused by default —
 * because SMS costs money and stays off — is a bad trade.
 *
 * OFF BY DEFAULT AND DEGRADING QUIETLY is the pattern every optional
 * integration in this project follows (Google sign-in, Web Push, TomTom, and
 * now SMS): with no credentials it returns NOT_CONFIGURED, the dispatcher
 * falls back to Web Push — which is free and needs no phone number, exactly
 * as the PRD recommends — and the queue keeps working. Nothing throws,
 * nothing is half-enabled. That is the availability NFR in practice: "a
 * single service failing must not take down queueing."
 */
@Component
public class TwilioSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(TwilioSmsSender.class);

    private static final String API_BASE = "https://api.twilio.com/2010-04-01/Accounts/";

    /** SMS is billed per 160-character segment; one segment is the budget. */
    private static final int MAX_SMS_LENGTH = 160;

    private final String accountSid;
    private final String authToken;
    private final String fromNumber;
    private final HttpClient http;

    public TwilioSmsSender(
            @Value("${app.sms.twilio.account-sid:}") String accountSid,
            @Value("${app.sms.twilio.auth-token:}") String authToken,
            @Value("${app.sms.twilio.from-number:}") String fromNumber) {
        this.accountSid = accountSid;
        this.authToken = authToken;
        this.fromNumber = fromNumber;
        // A timeout is not optional on a third-party call in a notification
        // path: without one, a hung provider ties up a thread indefinitely,
        // which is how one slow dependency becomes a whole-app outage.
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        if (!isConfigured()) {
            log.info("SMS notifications disabled (no Twilio credentials) — Web Push remains the primary channel");
        }
    }

    @Override
    public boolean isAvailable() {
        return isConfigured();
    }

    private boolean isConfigured() {
        return !accountSid.isBlank() && !authToken.isBlank() && !fromNumber.isBlank();
    }

    @Override
    public Result send(String phoneNumber, PushMessage message) {
        if (!isConfigured()) {
            return Result.NOT_CONFIGURED;
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            return Result.INVALID_NUMBER;
        }

        String form = "To=" + enc(phoneNumber)
                + "&From=" + enc(fromNumber)
                + "&Body=" + enc(smsBody(message));

        String basic = Base64.getEncoder().encodeToString(
                (accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE + accountSid + "/Messages.json"))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();

        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Result.SENT;
            }
            // 4xx means Twilio understood us and said no — usually a bad
            // number. Retrying would fail identically, so say so and let the
            // dispatcher stop trying.
            if (status >= 400 && status < 500) {
                log.warn("Twilio rejected SMS: HTTP {}", status);
                return Result.INVALID_NUMBER;
            }
            log.warn("Twilio SMS failed: HTTP {}", status);
            return Result.FAILED;
        } catch (InterruptedException e) {
            // Never swallow an interrupt: restore the flag so the thread's
            // owner can still see it was asked to stop.
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (Exception e) {
            log.warn("SMS delivery failed", e);
            return Result.FAILED;
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * One segment, with the URL last so truncation eats the least important
     * thing. A "leave now" alert that arrives as two billed messages split
     * mid-sentence is worse than a slightly shorter one.
     */
    private String smsBody(PushMessage message) {
        String full = "%s: %s %s".formatted(message.title(), message.body(), message.url());
        return full.length() <= MAX_SMS_LENGTH ? full : full.substring(0, MAX_SMS_LENGTH - 1) + "…";
    }
}
