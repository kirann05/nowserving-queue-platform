package com.nowserving.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The circuit breaker that protects us from the notification provider.
 *
 * Built by hand rather than with @CircuitBreaker annotations on purpose: the
 * three states and the thresholds stay visible in code you can read, instead
 * of hiding behind an annotation and a properties file. It also avoids
 * pinning us to a Spring Boot starter version.
 *
 * The state machine, in plain terms:
 *
 *   CLOSED ──(too many recent calls failed)──> OPEN
 *     ▲                                          │
 *     │                                   (wait 30 seconds)
 *     │                                          ▼
 *     └──(probe calls succeeded)──────────── HALF_OPEN
 *                                                │
 *                                     (probes failed) └──> OPEN
 */
@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    public CircuitBreaker notificationCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                // Trip once half of recent calls are failing.
                .failureRateThreshold(50f)
                // ...but only judge after a meaningful sample. Without this, the
                // very first failed push would open the circuit for everyone.
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
                // A hung call is the real danger, so count slowness as failure.
                .slowCallDurationThreshold(Duration.ofSeconds(3))
                .slowCallRateThreshold(50f)
                // How long to stay OPEN before testing the water again.
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .build();

        CircuitBreaker breaker = CircuitBreaker.of("notifications", config);

        // Log every transition. In Sprint 6 these become Prometheus metrics
        // and an alert — "the breaker is OPEN" is exactly the kind of thing
        // you want to find out from a dashboard, not from a customer.
        breaker.getEventPublisher().onStateTransition(event ->
                log.warn("Notification circuit breaker: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));

        return breaker;
    }
}
