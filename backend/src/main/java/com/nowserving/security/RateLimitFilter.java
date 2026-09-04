package com.nowserving.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NS-5's abuse defense: a fixed-window rate limit on /public/**, keyed by
 * client IP. The join endpoint is an unauthenticated WRITE — without a limit,
 * one curl loop fills every queue with junk entries.
 *
 * Design choices, and their honest trade-offs (this is an MVP limiter):
 *  - Fixed window (a counter per IP per minute): simplest possible algorithm.
 *    A burst straddling the minute boundary can briefly double the rate —
 *    acceptable here; token-bucket (e.g. the Bucket4j library) fixes that
 *    when it matters.
 *  - In-memory map: resets on restart, and per-instance once we scale out.
 *    Sprint 2's Redis is the natural upgrade path.
 *  - Keyed by Request#getRemoteAddr(): behind a reverse proxy this becomes the
 *    proxy's IP — real deployments must read X-Forwarded-For (carefully; the
 *    client can spoof it unless a trusted proxy sets it).
 *
 * @Order(HIGHEST_PRECEDENCE): run before everything else, including Spring
 * Security — reject floods as cheaply and early as possible.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    /** requests allowed per IP per minute window. */
    private final int requestsPerMinute;

    /**
     * The same origins SecurityConfig allows.
     *
     * This filter runs at HIGHEST_PRECEDENCE — deliberately, so a flood is
     * rejected before Spring Security does any work — which means it short
     * circuits the chain BEFORE the CORS filter ever runs. A 429 therefore
     * went back with no Access-Control-Allow-Origin at all, the browser
     * refused to hand it to JavaScript, and axios saw an opaque network
     * failure instead of a status code. The customer was told "Cannot reach
     * the server — is the backend running?" while the server was up and
     * answering correctly. Cheap rejection is still the right call; it just
     * has to say who it is talking to.
     */
    private final List<String> allowedOrigins;

    private record Window(long minute, AtomicInteger count) {}

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${app.rate-limit.requests-per-minute}") int requestsPerMinute,
                           @Value("${app.cors.allowed-origins}") String allowedOrigins) {
        this.requestsPerMinute = requestsPerMinute;
        this.allowedOrigins = List.of(allowedOrigins.split(",")).stream().map(String::trim).toList();
    }

    /** Only the public surface is limited; owner endpoints already require a JWT. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/public/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long currentMinute = System.currentTimeMillis() / 60_000;
        String ip = request.getRemoteAddr();

        // compute() runs atomically per key: start a fresh window if the
        // minute rolled over, otherwise increment the existing counter.
        Window window = windows.compute(ip, (k, w) ->
                (w == null || w.minute() != currentMinute)
                        ? new Window(currentMinute, new AtomicInteger(1))
                        : new Window(w.minute(), new AtomicInteger(w.count().incrementAndGet())));

        // Crude memory guard: drop stale windows so the map can't grow forever.
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(e -> e.getValue().minute() != currentMinute);
        }

        if (window.count().get() > requestsPerMinute) {
            response.setStatus(429); // 429 Too Many Requests

            // Let the browser actually READ this response — see allowedOrigins.
            String origin = request.getHeader("Origin");
            if (origin != null && allowedOrigins.contains(origin)) {
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");
            }
            // How long to wait, in the header clients already understand.
            long secondsLeft = 60 - ((System.currentTimeMillis() / 1000) % 60);
            response.setHeader("Retry-After", String.valueOf(secondsLeft));

            // Explicit UTF-8. getWriter() otherwise defaults to ISO-8859-1 and
            // the em-dash below reached the client as a literal "?".
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(
                    "{\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Slow down — try again in a minute\"}");
            return; // do NOT continue the chain — the request ends here
        }

        filterChain.doFilter(request, response);
    }
}
