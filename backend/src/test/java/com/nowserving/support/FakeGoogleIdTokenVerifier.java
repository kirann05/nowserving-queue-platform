package com.nowserving.support;

import com.nowserving.exception.UnauthorizedException;
import com.nowserving.security.GoogleIdTokenVerifier;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The test adapter for {@link GoogleIdTokenVerifier}.
 *
 * Why this exists (worth internalising — it generalises to every external
 * dependency you will ever have):
 *  - SPEED: no network call, so the suite stays in milliseconds.
 *  - DETERMINISM: the same input always gives the same result. Tests that
 *    depend on Google being up are tests that fail for reasons that aren't
 *    your fault, and a suite that cries wolf gets ignored.
 *  - CONTROL: we can conjure an "unverified email" account on demand. Try
 *    doing that against real Google.
 *  - COST: matters enormously in Sprint 5, where the real provider (Google
 *    Maps) bills per call. A test suite that hits a paid API is a bill.
 *
 * Real teams call this a "fake" — a working in-memory implementation, as
 * opposed to a "mock" (records calls) or a "stub" (returns canned values).
 */
public class FakeGoogleIdTokenVerifier implements GoogleIdTokenVerifier {

    private final Map<String, GoogleAccount> issued = new ConcurrentHashMap<>();

    /**
     * Pretend Google has signed a token for this person, and hand back the
     * token string — exactly what a browser would have received.
     */
    public String givenGoogleAccount(String subject, String email, String displayName, boolean emailVerified) {
        String token = "fake-google-id-token-" + UUID.randomUUID();
        issued.put(token, new GoogleAccount(subject, email, displayName, emailVerified));
        return token;
    }

    /** Anything we didn't issue behaves like a forged/expired token: 401. */
    @Override
    public GoogleAccount verify(String idToken) {
        GoogleAccount account = issued.get(idToken);
        if (account == null) {
            throw new UnauthorizedException("Invalid Google sign-in");
        }
        return account;
    }
}
