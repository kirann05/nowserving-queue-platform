package com.nowserving.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Time as an injected dependency, not a global.
 *
 * The PRD (§5.2) insists on this and it is worth understanding why:
 * {@code Instant.now()} inside business logic makes that logic untestable.
 * You cannot assert "the leave-now alert fires 22 minutes before their turn"
 * without waiting 22 real minutes — so the test either never gets written, or
 * gets written with sleeps and becomes flaky.
 *
 * With a Clock bean, a test injects {@code Clock.fixed(...)} and time becomes
 * an ordinary input. Sprint 5's whole engine is time arithmetic; it is
 * effectively untestable without this, which is why it lands FIRST.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        // UTC everywhere internally; time zones are applied only at the edges
        // where a human reads a time (slot generation, display).
        return Clock.systemUTC();
    }
}
