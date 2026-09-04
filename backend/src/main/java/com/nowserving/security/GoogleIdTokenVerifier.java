package com.nowserving.security;

/**
 * "Is this really a Google-issued ID token, and who does it belong to?"
 *
 * WHY THIS IS AN INTERFACE (this is the important bit, not the code):
 * the real implementation talks to Google over the network. If our tests
 * called it, the test suite would be slow, would fail whenever Google or the
 * office wifi hiccups, and could never test the "expired token" case on
 * demand. So we depend on this INTERFACE, ship one real implementation, and
 * inject a fake one in tests.
 *
 * The PRD calls for exactly this pattern for the paid Maps API in Sprint 5
 * ("wrap the provider behind an interface from day one... that last point is
 * not optional"). Practising it here, on a free API, is deliberate.
 *
 * Real-world name for it: the Ports & Adapters (a.k.a. Hexagonal) pattern —
 * your domain owns the "port" (this interface), and the outside world plugs
 * "adapters" into it.
 */
public interface GoogleIdTokenVerifier {

    /**
     * @return the verified Google identity
     * @throws com.nowserving.exception.UnauthorizedException if the token is
     *         forged, expired, or was issued for a different application
     */
    GoogleAccount verify(String idToken);

    /**
     * The only facts we take from Google.
     *
     * @param subject       Google's permanent unique id for this person ("sub").
     * @param email         their Google email address.
     * @param displayName   their name, for greeting them in the UI.
     * @param emailVerified whether GOOGLE has confirmed they own that email.
     *                      Critical for account linking — see AuthService.
     */
    record GoogleAccount(String subject, String email, String displayName, boolean emailVerified) {}
}
