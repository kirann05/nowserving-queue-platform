package com.nowserving.config;

import com.nowserving.security.JwtService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fail-fast guard: when the app runs with the 'prod' Spring profile
 * (SPRING_PROFILES_ACTIVE=prod), refusing to start beats silently running
 * with a forgeable dev JWT secret.
 *
 * Why a separate class instead of logic inside JwtService: JwtService should
 * not know about deployment profiles; "what is acceptable in which
 * environment" is configuration policy, which belongs in the config package.
 * @Profile("prod") means this bean simply doesn't exist outside prod.
 */
@Configuration
@Profile("prod")
public class ProdSecretGuard {

    @Value("${app.jwt.secret}")
    private String secret;

    @org.springframework.beans.factory.annotation.Value("${app.cors.allowed-origins:}")
    private String corsOrigins;

    @PostConstruct
    void verify() {
        if (JwtService.DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "Refusing to start: JWT_SECRET is still the committed dev default. "
                            + "Set a real JWT_SECRET environment variable.");
        }
        // HS256 needs >= 256 bits. A short secret is brute-forceable, and the
        // failure is silent — tokens still work, they're just forgeable.
        if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "Refusing to start: JWT_SECRET must be at least 32 bytes for HS256.");
        }
        // A production origin on plain http would break Web Push, Geolocation
        // and Google sign-in — all three are HTTPS-only in browsers. Better to
        // say so at boot than to debug three "mysteriously broken" features.
        if (corsOrigins.contains("http://") && !corsOrigins.contains("localhost")) {
            throw new IllegalStateException(
                    "Refusing to start: CORS origin uses http:// . Browsers require HTTPS for "
                            + "Web Push, Geolocation and Google sign-in. Use https:// in production.");
        }
    }
}
