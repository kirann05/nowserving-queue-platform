package com.nowserving.controller;

import com.nowserving.dto.PublicDtos.FeedbackRequest;
import com.nowserving.dto.PublicDtos.JoinRequest;
import com.nowserving.dto.PublicDtos.JoinResponse;
import com.nowserving.dto.PublicDtos.NotifyPreferenceRequest;
import com.nowserving.dto.PublicDtos.PositionResponse;
import com.nowserving.dto.PublicDtos.PushSubscriptionRequest;
import com.nowserving.notification.SmsSender;
import com.nowserving.dto.PublicDtos.NotificationCapabilities;
import com.nowserving.dto.PublicDtos.VapidKeyResponse;
import com.nowserving.notification.WebPushChannel;
import com.nowserving.service.PublicQueueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The unauthenticated customer surface (NS-5, NS-6). Everything under
 * /public/** is permitAll in SecurityConfig and rate-limited by
 * RateLimitFilter — public write endpoints get abused, so the defense is
 * wired in from day one.
 */
@RestController
@RequiredArgsConstructor
public class PublicQueueController {

    private final PublicQueueService publicQueueService;
    private final WebPushChannel webPushChannel;
    private final SmsSender smsSender;

    /** NS-5: scan the QR, submit your name, you're in line. */
    @PostMapping("/public/queues/{joinToken}/entries")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinResponse join(@PathVariable String joinToken,
                             @RequestHeader(value = "Idempotency-Key", required = false)
                             String idempotencyKey,
                             @Valid @RequestBody JoinRequest request) {
        return publicQueueService.join(joinToken, request, idempotencyKey);
    }

    /** P1: the customer bows out. Their token is the authorisation. */
    @DeleteMapping("/public/entries/{entryToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(@PathVariable String entryToken) {
        publicQueueService.leave(entryToken);
    }

    /** V8: 1–5 stars + optional comment, after being served. */
    @PostMapping("/public/entries/{entryToken}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void feedback(@PathVariable String entryToken,
                         @Valid @RequestBody FeedbackRequest request) {
        publicQueueService.feedback(entryToken, request.stars(), request.comment());
    }

    /**
     * V9: pick a notification channel once you're already in the line.
     * PATCH, not PUT — this revises one aspect of the ticket, it doesn't
     * replace it.
     */
    @PatchMapping("/public/entries/{entryToken}/notify-preference")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notifyPreference(@PathVariable String entryToken,
                                 @Valid @RequestBody NotifyPreferenceRequest request) {
        publicQueueService.updateNotifyPreference(entryToken, request.channel(), request.phoneNumber());
    }

    /** NS-6: "where am I?" — polled by the customer's phone. */
    @GetMapping("/public/entries/{entryToken}")
    public PositionResponse position(@PathVariable String entryToken) {
        return publicQueueService.position(entryToken);
    }

    /**
     * FR-8: the browser hands us its push subscription so we can buzz it when
     * the customer is next — even with the tab closed. 204: nothing to return.
     */
    @PostMapping("/public/entries/{entryToken}/push-subscription")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribeToPush(@PathVariable String entryToken,
                                @Valid @RequestBody PushSubscriptionRequest request) {
        publicQueueService.savePushSubscription(entryToken, request);
    }

    /**
     * The public half of the VAPID key pair. The browser must pass this when
     * subscribing. Serving it (instead of duplicating it in the frontend's
     * env file) means the two can never drift out of sync.
     */
    @GetMapping("/public/push/vapid-key")
    public VapidKeyResponse vapidKey() {
        return new VapidKeyResponse(webPushChannel.applicationServerKey());
    }

    /**
     * Which alert channels this deployment can actually use. The ticket page
     * asks before it offers, so a server without VAPID keys or without Twilio
     * credentials never advertises an alert it cannot send.
     */
    @GetMapping("/public/notifications/capabilities")
    public NotificationCapabilities notificationCapabilities() {
        return new NotificationCapabilities(
                !webPushChannel.applicationServerKey().isBlank(),
                smsSender.isAvailable());
    }
}
