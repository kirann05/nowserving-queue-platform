package com.nowserving.security;

import com.nowserving.exception.BadRequestException;
import com.nowserving.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * The real adapter: verifies a Google ID token the way Google documents it.
 *
 * HOW "SIGN IN WITH GOOGLE" ACTUALLY WORKS (the whole flow in five lines):
 *  1. The browser shows Google's button; the user picks their Google account.
 *  2. Google hands the BROWSER a signed ID token (a JWT) describing that user.
 *  3. The browser POSTs that token to our /auth/google.
 *  4. We verify Google's signature on it — this class.
 *  5. We then mint OUR OWN JWT, exactly like a password login would.
 * Google authenticates the human; we still issue the session. After step 5
 * the rest of the app cannot tell how someone logged in, which is the point.
 *
 * Three checks make this safe, and skipping any one of them is a real breach:
 *  - SIGNATURE: proves Google issued it. Google publishes its public keys at
 *    a "JWKS" URL; NimbusJwtDecoder fetches, caches and rotates them for us.
 *  - EXPIRY: an old token, perhaps stolen from a log, must stop working.
 *  - AUDIENCE ("aud" = our client id): proves the token was minted FOR US.
 *    Without this check, anyone could take a valid Google token issued to
 *    *their* app and use it to log in as that user here — the classic
 *    "confused deputy" bug.
 */
@Component
public class GoogleOidcTokenVerifier implements GoogleIdTokenVerifier {

    /** Where Google publishes the public keys it signs ID tokens with. */
    private static final String GOOGLE_JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /** Google legitimately uses both spellings in the "iss" claim. */
    private static final Set<String> ACCEPTED_ISSUERS =
            Set.of("https://accounts.google.com", "accounts.google.com");

    private final String clientId;

    /**
     * Built on FIRST USE, not at startup: constructing it reaches out to
     * Google, and the app must still boot on a laptop with no internet (and
     * in tests). "volatile + double-checked locking" is the standard safe
     * way to lazily build one shared instance across threads.
     */
    private volatile NimbusJwtDecoder decoder;

    public GoogleOidcTokenVerifier(@Value("${app.google.client-id:}") String clientId) {
        this.clientId = clientId;
    }

    @Override
    public GoogleAccount verify(String idToken) {
        if (clientId == null || clientId.isBlank()) {
            // Misconfiguration, not a user error — say so plainly instead of
            // returning a confusing 401 that sends you hunting in the wrong place.
            throw new BadRequestException(
                    "Google sign-in is not configured on this server (set GOOGLE_CLIENT_ID)");
        }

        Jwt jwt;
        try {
            jwt = decoder().decode(idToken); // signature + expiry + issuer + audience
        } catch (JwtException e) {
            // Forged, expired, tampered with, or meant for another app — the
            // caller learns none of the details. Precise errors here would
            // help an attacker tune their attempts.
            throw new UnauthorizedException("Invalid Google sign-in");
        }

        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String name = jwt.getClaimAsString("name");
        Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");

        if (subject == null || email == null) {
            throw new UnauthorizedException("Google sign-in did not include an account identity");
        }

        return new GoogleAccount(
                subject,
                email,
                // Some Google accounts have no display name set; fall back to
                // the local part of the email so the UI always has something.
                (name != null && !name.isBlank()) ? name : email.split("@")[0],
                Boolean.TRUE.equals(emailVerified));
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder local = this.decoder;
        if (local == null) {
            synchronized (this) {
                local = this.decoder;
                if (local == null) {
                    local = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWKS_URI).build();
                    local.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                            new JwtTimestampValidator(),
                            new JwtClaimValidator<String>("iss", ACCEPTED_ISSUERS::contains),
                            new JwtClaimValidator<List<String>>("aud",
                                    aud -> aud != null && aud.contains(clientId))));
                    this.decoder = local;
                }
            }
        }
        return local;
    }
}
