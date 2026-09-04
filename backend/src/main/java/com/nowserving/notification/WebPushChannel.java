package com.nowserving.notification;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import com.interaso.webpush.WebPushService;
import com.nowserving.entity.PushSubscription;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers a notification via Web Push — and, more importantly, refuses to
 * let that slow external call take the queue down with it.
 *
 * THE FAILURE THIS GUARDS AGAINST (the point of Sprint 3's resilience story):
 * Google's push endpoint doesn't usually fail fast; it hangs. Without a
 * breaker:
 *      push endpoint slows to 30s
 *   -> every notification attempt holds a thread for 30s
 *   -> the thread pool drains
 *   -> the app stops answering ANY request
 *   -> the queue is down because a notification was slow.
 * That is a CASCADING FAILURE, and it is why the PRD isolates notifications.
 *
 * The circuit breaker converts a slow failure into an instant one: after
 * enough failures it goes OPEN and subsequent calls return immediately
 * without touching the network, until a cool-off lets a few probes through
 * (HALF_OPEN). Failing fast is what protects you; waiting politely is what
 * kills you.
 */
@Component
public class WebPushChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(WebPushChannel.class);

    private final CircuitBreaker circuitBreaker;
    private final ObjectMapper objectMapper;
    /** null when no VAPID keys are configured — the feature is simply off. */
    private final WebPushService pushService;
    /** Served to the browser so there is ONE source of truth for the key. */
    private final String applicationServerKey;

    public WebPushChannel(@Qualifier("notificationCircuitBreaker") CircuitBreaker circuitBreaker,
                          ObjectMapper objectMapper,
                          @Value("${app.push.vapid.public-key:}") String publicKey,
                          @Value("${app.push.vapid.private-key:}") String privateKey,
                          @Value("${app.push.subject:mailto:dev@nowserving.local}") String subject) {
        this.circuitBreaker = circuitBreaker;
        this.objectMapper = objectMapper;

        WebPushService service = null;
        String appServerKey = "";
        if (!publicKey.isBlank() && !privateKey.isBlank()) {
            try {
                // fromUncompressedBytes, NOT create().
                //
                // The library offers both, and picking the wrong one produces
                // a genuinely confusing "InvalidKeySpecException: not enough
                // content" that looks like a corrupt key rather than a format
                // mismatch. The difference:
                //
                //   create(...)                -> X.509 / PKCS#8 DER, base64.
                //                                 Public key starts "MFkwEw…"
                //   fromUncompressedBytes(...) -> RAW base64url: a 65-byte
                //                                 uncompressed EC point (87
                //                                 chars, starts "B…") plus a
                //                                 32-byte scalar (43 chars).
                //
                // Raw is what `npx web-push generate-vapid-keys` emits and what
                // every web-push implementation across languages uses, so it is
                // the portable choice — the same key pair works here and in any
                // JS tooling. See docs/PUSH_NOTIFICATIONS_SETUP.md.
                VapidKeys keys = VapidKeys.fromUncompressedBytes(publicKey, privateKey);
                service = new WebPushService(subject, keys);
                // The browser needs this exact value as `applicationServerKey`
                // when it subscribes. Deriving it from the same key pair the
                // server signs with removes a whole class of "the frontend and
                // backend disagree about the key" bugs. (For raw keys this is
                // byte-identical to the configured public key.)
                appServerKey = java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(keys.getApplicationServerKey());
                log.info("Web Push enabled (VAPID public key {}…)",
                        appServerKey.substring(0, Math.min(12, appServerKey.length())));
            } catch (Exception e) {
                log.error("VAPID keys are present but unusable — push notifications disabled. "
                        + "Expected RAW base64url keys (public ~87 chars starting 'B', private ~43 chars), "
                        + "as produced by `npx web-push generate-vapid-keys`. "
                        + "A public key starting 'MFkwEw' is X.509 and is NOT the format used here. "
                        + "See docs/PUSH_NOTIFICATIONS_SETUP.md", e);
            }
        } else {
            log.warn("No VAPID keys configured — push notifications disabled. "
                    + "See docs/PUSH_NOTIFICATIONS_SETUP.md to enable them.");
        }
        this.pushService = service;
        this.applicationServerKey = appServerKey;
    }

    /** Empty when push is not configured; the UI then hides the opt-in. */
    public String applicationServerKey() {
        return applicationServerKey;
    }

    @Override
    public DeliveryResult send(PushSubscription subscription, PushMessage message) {
        if (pushService == null) {
            return DeliveryResult.NOT_CONFIGURED;
        }

        String payload = objectMapper.writeValueAsString(message);

        try {
            // executeSupplier runs the call THROUGH the breaker: it records
            // successes and failures, and throws CallNotPermittedException
            // instead of calling at all when the circuit is OPEN.
            WebPush.SubscriptionState state = circuitBreaker.executeSupplier(() ->
                    pushService.send(
                            payload,
                            subscription.getEndpoint(),
                            subscription.getP256dh(),
                            subscription.getAuth(),
                            /* ttl     */ 600,      // pointless to deliver "you're next" an hour late
                            /* topic   */ null,
                            /* urgency */ WebPush.Urgency.High));

            return state == WebPush.SubscriptionState.EXPIRED
                    ? DeliveryResult.EXPIRED
                    : DeliveryResult.DELIVERED;

        } catch (CallNotPermittedException e) {
            // Not an error on our side — the breaker is doing its job.
            log.debug("Circuit OPEN; skipped a push without attempting it");
            return DeliveryResult.SKIPPED_CIRCUIT_OPEN;
        } catch (Exception e) {
            log.warn("Push delivery failed: {}", e.getMessage());
            return DeliveryResult.FAILED;
        }
    }

    /** Exposed so /health-style checks and tests can see the breaker's state. */
    public String circuitState() {
        return circuitBreaker.getState().name();
    }
}
