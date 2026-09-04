package com.nowserving.config;

import com.nowserving.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * NS-3: the security rules of the whole API, in one place.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    /**
     * BCrypt for password hashing (NS-2). Three properties matter:
     *  1. One-way — you can verify a password against the hash but never
     *     recover the password from it.
     *  2. Salted — the same password hashes differently for every user, so
     *     precomputed "rainbow tables" are useless.
     *  3. Deliberately SLOW (tunable cost factor) — brute-forcing a stolen
     *     hash dump becomes computationally miserable. This is why you never
     *     use general-purpose hashes like SHA-256 for passwords: they're fast,
     *     and fast is exactly what an attacker wants.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Gotcha worth knowing: Spring Boot auto-registers every Filter @Component
     * with the servlet container. Our JWT filter is ALSO added to the security
     * chain below — without this bean it would run twice per request. This
     * disables the automatic registration and keeps only the security-chain one.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * CORS (Cross-Origin Resource Sharing). The browser blocks JavaScript on
     * origin A (our React app, http://localhost:5173) from reading responses
     * of origin B (this API, :8080) unless B explicitly allows it. That's the
     * Same-Origin Policy — the browser protecting users, not the server.
     * This bean is the API saying "the frontend origin may call me."
     * Without it, EVERY frontend request fails in the browser console with a
     * CORS error while curl/Postman (no browser, no SOP) work fine — the
     * classic "works in Postman, broken in React" mystery.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        // PATCH earned its place here the same way Idempotency-Key did below:
        // the queue open/close endpoint silently failed preflight without it.
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Every non-simple header the frontend sends must be listed here, or
        // the browser's PREFLIGHT rejects the request before it leaves.
        // Found the hard way: Idempotency-Key was missing, so retry-safe
        // joins/bookings worked in MockMvc (no CORS there) and failed in a
        // real browser. Integration tests cannot catch preflight bugs —
        // only a browser can.
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        // We use Bearer headers, not cookies — so no credentials mode needed.
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Wire the CORS rules above into the security chain (Security
                // must handle the browser's OPTIONS "preflight" probe before
                // auth rules run, or preflights would 401).
                .cors(cors -> {})

                // CSRF protection defends browser-cookie sessions. We have no
                // cookies and no sessions — auth is an explicit header a
                // malicious cross-site form can't attach — so it's off.
                .csrf(csrf -> csrf.disable())

                // STATELESS: never create or read an HTTP session. Every
                // request must prove itself with its JWT. This is what lets
                // you run N instances of this app behind a load balancer with
                // zero shared session state (relevant in Sprint 2).
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Public surface — exactly three areas, everything else locked:
                        .requestMatchers("/health").permitAll()      // monitoring
                        .requestMatchers("/auth/**").permitAll()     // you can't be logged in before signing up
                        .requestMatchers("/public/**").permitAll()   // customers have no accounts (NS-5/NS-6)
                        .requestMatchers("/ws/**").permitAll()       // WebSocket handshake (Sprint 2) — topic tokens are the auth
                        // Sprint 6: Kubernetes probes must work before anyone
                        // logs in. Only liveness/readiness are public; the
                        // metrics endpoint below is NOT (it would hand an
                        // attacker a map of your traffic and dependencies).
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        .anyRequest().authenticated())

                // What "you're not authenticated" looks like: a clean 401 JSON
                // (Spring's default would be an HTML login redirect — wrong
                // for an API).
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Missing or invalid token\"}");
                }))

                // Our JWT filter must run before the point where Spring would
                // normally look for form-login credentials, so the
                // SecurityContext is already populated when authorization runs.
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
