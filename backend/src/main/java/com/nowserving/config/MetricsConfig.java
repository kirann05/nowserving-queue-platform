package com.nowserving.config;

import com.nowserving.travel.GuardedTravelTimeService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sprint 6 — the custom metrics that matter for THIS product.
 *
 * Spring Boot already gives you JVM, HTTP and datasource metrics for free.
 * These two are ours, and both map directly to a PRD risk:
 *
 *  - maps spend: §1.8 lists "Maps API surprise bill" as a HIGH risk, and §4.2
 *    asks for a spend dashboard + alert. A number nobody graphs is a number
 *    nobody notices until the invoice arrives.
 *  - circuit breaker state: "notifications are silently broken" is exactly
 *    the kind of failure that hides for weeks. Graph it and it can page you.
 *
 * The pattern is a GAUGE — a value that goes up and down and is sampled when
 * scraped (versus a counter, which only ever increases).
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Gauge mapsCallsThisMonthGauge(MeterRegistry registry,
                                         GuardedTravelTimeService travelTimeService) {
        return Gauge.builder("nowserving.maps.calls.month", travelTimeService::callsThisMonth)
                .description("Paid routing API calls made this calendar month")
                .register(registry);
    }

    @Bean
    public Gauge notificationCircuitStateGauge(MeterRegistry registry,
                                               CircuitBreaker notificationCircuitBreaker) {
        // 0 = CLOSED (healthy), 1 = OPEN, 2 = HALF_OPEN. Numeric because
        // Prometheus stores numbers, not strings — an alert rule then reads
        // `nowserving_notifications_circuit_state > 0`.
        return Gauge.builder("nowserving.notifications.circuit.state", () -> switch (
                        notificationCircuitBreaker.getState()) {
                    case OPEN, FORCED_OPEN -> 1;
                    case HALF_OPEN -> 2;
                    default -> 0;
                })
                .description("0=closed, 1=open, 2=half-open")
                .register(registry);
    }
}
