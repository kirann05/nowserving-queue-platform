package com.nowserving;

// Boot 4 ships Jackson 3 — package root is tools.jackson, not
// com.fasterxml.jackson (that's Jackson 2). Same API for our purposes.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nowserving.support.FakeGoogleIdTokenVerifier;
import com.nowserving.support.FakeNotificationChannel;
import com.nowserving.support.FakePlacesProvider;
import com.nowserving.support.FakeSmsSender;
import com.nowserving.support.TestFakes;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 moved MockMvc test support into the webmvc module's own package
// (Boot 3.x had it under ...test.autoconfigure.web.servlet).
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared plumbing for all integration tests.
 *
 * Testcontainers starts ONE real Postgres 16 in Docker for the whole test run
 * (the static-initializer singleton pattern — faster than a fresh container
 * per class, and Spring's context caching reuses one application context
 * across all subclasses). Flyway then applies V1__init.sql to it, exactly as
 * in production. Testing against "the real thing" is the point: H2 would
 * happily pass a concurrency test that real Postgres locking would fail.
 *
 * Tests isolate from each other by DATA (every signup uses a unique email,
 * every test creates its own queue) rather than by wiping tables — cheaper,
 * and closer to how a shared dev DB behaves.
 */
// TestFakes swaps the real "notificationExecutor" for a synchronous one.
// @Async resolves executors BY BEAN NAME, so the test bean must reuse that
// exact name — and Boot rejects duplicate names unless overriding is allowed.
// It has to be set here rather than via @DynamicPropertySource: bean
// definitions are registered before those dynamic properties are consulted.
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureMockMvc
@Import(TestFakes.class) // fakes for every external service — inherited by all subclasses
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    /**
     * A real Redis for the realtime bus (Sprint 2). Same reasoning as using a
     * real Postgres: a hand-written stand-in would happily "pass" behaviour
     * that the real thing does differently, and the whole value of these
     * tests is that they exercise the actual wiring.
     */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /** Point Spring at the containers' random ports instead of the local ones. */
    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // The rate limiter is tested in its own unit test; here it would just
        // make unrelated tests flaky (they all share MockMvc's fake IP).
        registry.add("app.rate-limit.requests-per-minute", () -> "1000000");
        // Sprint 5's sweeps run on a timer in production. In tests that timer
        // would fire in the middle of assertions and make them flaky, so the
        // schedule is off and each test calls the service directly — the
        // logic is what we're testing, not Spring's ability to run a timer.
        registry.add("app.travel.scheduler-enabled", () -> "false");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    /** Lets a test say "pretend Google signed a token for this person". */
    @Autowired
    protected FakeGoogleIdTokenVerifier fakeGoogle;

    /** Records notifications instead of sending them; can be told to fail. */
    @Autowired
    protected FakeNotificationChannel fakeNotifications;

    /** Records SMS sends instead of calling Twilio; can be told to fail. */
    @Autowired
    protected FakeSmsSender fakeSms;

    /** Lets a test say "Overpass would return these nearby restaurants". */
    @Autowired
    protected FakePlacesProvider fakePlaces;

    // ---------- helpers used by every test ----------

    protected String uniqueEmail() {
        return "owner-" + UUID.randomUUID() + "@test.dev";
    }

    /** Full signup -> login round trip; returns a usable Bearer token. */
    protected String signupAndLogin(String email, String businessName) throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"%s","email":"%s","password":"secret123","displayName":"Test Owner"}
                                """.formatted(businessName, email)))
                .andExpect(status().isCreated());

        var loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"secret123"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        return body.get("token").asString();
    }

    /** Create a queue as the given owner; returns the parsed response JSON. */
    protected JsonNode createQueue(String token, String name) throws Exception {
        var result = mockMvc.perform(post("/queues")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Join a queue as an anonymous customer; returns the parsed response JSON. */
    protected JsonNode joinQueue(String joinToken, String customerName) throws Exception {
        var result = mockMvc.perform(post("/public/queues/{token}/entries", joinToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerName\":\"" + customerName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
