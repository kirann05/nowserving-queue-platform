package com.nowserving;

import com.nowserving.security.RateLimitFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NS-5's rate limiter, tested as a plain unit test — no Spring context, no
 * database, runs in milliseconds. When a class has real logic but no real
 * dependencies, this is the level to test it at.
 */
class RateLimitFilterTest {

    private MockHttpServletResponse fire(RateLimitFilter filter, String ip, String uri) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    /** The filter also needs the CORS allow-list, so it can put an
     *  Access-Control-Allow-Origin on a 429 — without one the browser hides
     *  the response and the client reports an unreachable server. */
    private RateLimitFilter newFilter(int perMinute) {
        return new RateLimitFilter(perMinute, "http://localhost:5173");
    }

    @Test
    void requestsOverTheLimit_get429() throws Exception {
        RateLimitFilter filter = newFilter(3); // 3 per minute for the test

        for (int i = 1; i <= 3; i++) {
            assertThat(fire(filter, "10.0.0.1", "/public/queues/x/entries").getStatus())
                    .as("request %d of 3 should pass", i)
                    .isEqualTo(200);
        }
        assertThat(fire(filter, "10.0.0.1", "/public/queues/x/entries").getStatus())
                .as("request 4 exceeds the window")
                .isEqualTo(429);
    }

    @Test
    void differentIps_haveIndependentWindows() throws Exception {
        RateLimitFilter filter = newFilter(1);

        assertThat(fire(filter, "10.0.0.1", "/public/queues/x/entries").getStatus()).isEqualTo(200);
        assertThat(fire(filter, "10.0.0.1", "/public/queues/x/entries").getStatus()).isEqualTo(429);
        // A different client is unaffected by the first one's flood.
        assertThat(fire(filter, "10.0.0.2", "/public/queues/x/entries").getStatus()).isEqualTo(200);
    }

    /**
     * A 429 has to be READABLE by the browser that triggered it.
     *
     * This filter runs at HIGHEST_PRECEDENCE and short-circuits the chain, so
     * it never reaches Spring Security's CORS filter. Without the header the
     * browser blocks the response, the client sees an opaque network error,
     * and the customer is told the server is unreachable while it is up and
     * deliberately answering.
     */
    @Test
    void aRateLimitedResponseIsReadableByTheBrowser() throws Exception {
        RateLimitFilter filter = newFilter(1);

        fire(filter, "10.0.0.5", "/public/queues/x/entries");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/public/queues/x/entries");
        request.setRemoteAddr("10.0.0.5");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(response.getHeader("Retry-After")).isNotNull();
        // UTF-8, or the em-dash in the message reaches the client mangled.
        assertThat(response.getContentType()).contains("UTF-8");
    }

    /** An origin we don't trust gets the 429 without being handed CORS access. */
    @Test
    void anUnknownOriginGetsNoCorsHeaderOnA429() throws Exception {
        RateLimitFilter filter = newFilter(1);

        fire(filter, "10.0.0.6", "/public/queues/x/entries");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/public/queues/x/entries");
        request.setRemoteAddr("10.0.0.6");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    @Test
    void nonPublicRoutes_areNotLimited() throws Exception {
        RateLimitFilter filter = newFilter(1);

        // Authenticated staff endpoints can be hammered freely (they're
        // protected by auth, and staff tapping "Next" rapidly is legitimate).
        for (int i = 0; i < 5; i++) {
            assertThat(fire(filter, "10.0.0.9", "/queues/1/advance").getStatus()).isEqualTo(200);
        }
    }
}
