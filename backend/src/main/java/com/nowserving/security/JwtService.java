package com.nowserving.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * NS-3: issue and validate JSON Web Tokens.
 *
 * A JWT is three base64url parts: header.payload.signature. The payload
 * ("claims") is READABLE by anyone — paste one into jwt.io and look. It is not
 * encrypted; it is SIGNED. The HMAC-SHA256 signature means nobody without our
 * secret can forge or alter a token, which is what makes stateless auth work:
 * the server keeps no session table, the token itself is the proof.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration expiry;

    /** The committed dev fallback from application.yml — never for real use.
     *  public so ProdSecretGuard (config package) can compare against it. */
    public static final String DEV_DEFAULT_SECRET = "dev-only-secret-change-me-0123456789abcdef";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JwtService.class);

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiry-hours}") long expiryHours) {
        // Guard against the classic mistake: shipping with the committed dev
        // secret, which would let anyone who reads the repo forge tokens.
        // Loud warning in dev; hard startup failure in the 'prod' profile is
        // enforced by ProdSecretGuard (config package).
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            log.warn("Using the DEV JWT secret. Set JWT_SECRET before any real deployment — "
                    + "anyone with this repo can forge tokens for any account.");
        }
        // HS256 requires >= 256 bits of key material; jjwt enforces this and
        // will throw at startup if the configured secret is too short.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiry = Duration.ofHours(expiryHours);
    }

    /** What login hands back: the token plus when it stops working. */
    public record IssuedToken(String token, Instant expiresAt) {}

    public IssuedToken issue(AuthenticatedOwner owner) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(expiry);
        String token = Jwts.builder()
                // "sub" (subject) = whom the token is about. Standard claim.
                .subject(String.valueOf(owner.ownerId()))
                // Custom claims: our tenant id and email ride along so most
                // requests never need a DB lookup just to know who's calling.
                .claim("businessId", owner.businessId())
                .claim("email", owner.email())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Verify signature + expiry and rebuild the principal from the claims.
     * Returns null for anything invalid — the filter treats null as
     * "not authenticated", and the request dies at the security gate with 401.
     */
    public AuthenticatedOwner validate(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)   // checks signature
                    .build()
                    .parseSignedClaims(token) // also checks expiration
                    .getPayload();
            return new AuthenticatedOwner(
                    Long.valueOf(claims.getSubject()),
                    claims.get("businessId", Long.class),
                    claims.get("email", String.class));
        } catch (JwtException | IllegalArgumentException e) {
            // Forged, expired, malformed, wrong key — all the same to us.
            return null;
        }
    }
}
