package com.nowserving.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.concurrent.Executor;

/**
 * All the "outside world" replacements the test suite uses, in one place.
 *
 * Imported by AbstractIntegrationTest via @Import, which every subclass
 * inherits. (A nested @TestConfiguration would NOT work here: Spring only
 * auto-detects nested config classes declared on the concrete test class,
 * not on an abstract parent — a genuinely surprising rule that costs people
 * an afternoon.)
 *
 * As external dependencies arrive in later sprints — Web Push in Sprint 3,
 * the paid Maps API in Sprint 5 — their fakes belong here too.
 */
@TestConfiguration
public class TestFakes {

    /**
     * @Primary = "prefer this one". The real GoogleOidcTokenVerifier bean
     * still exists in the context; it simply never gets injected, so no test
     * can reach the network by accident.
     */
    @Bean
    @Primary
    public FakeGoogleIdTokenVerifier fakeGoogleIdTokenVerifier() {
        return new FakeGoogleIdTokenVerifier();
    }

    /** No real Web Push in tests — see the class comment for why. */
    @Bean
    @Primary
    public FakeNotificationChannel fakeNotificationChannel() {
        return new FakeNotificationChannel();
    }

    /** No real Twilio in tests — same reasoning, see the class comment. */
    @Bean
    @Primary
    public FakeSmsSender fakeSmsSender() {
        return new FakeSmsSender();
    }

    /** No real Overpass calls in tests — see FakePlacesProvider's comment. */
    @Bean
    @Primary
    public FakePlacesProvider fakePlacesProvider() {
        return new FakePlacesProvider();
    }

    /**
     * Runs @Async("notificationExecutor") work on the CALLING thread.
     *
     * In production notifications must be asynchronous, or a slow provider
     * would stall the staff's "Next" request. In tests that same asynchrony
     * would make assertions racy — you'd be sprinkling sleeps and hoping.
     * Swapping in a synchronous executor makes the tests deterministic while
     * leaving production behaviour untouched: the SAME bean name, a different
     * threading policy.
     */
    @Bean("notificationExecutor")
    @Primary
    public Executor notificationExecutor() {
        return new SyncTaskExecutor();
    }
}
