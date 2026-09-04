package com.nowserving;

import com.nowserving.repository.OwnerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FR-1, Google half: "A business owner can register with email+password
 * (BCrypt-hashed) OR Google OAuth."
 *
 * Every test here runs against the FAKE verifier (see AbstractIntegrationTest)
 * — no network, no Google, fully deterministic.
 */
class GoogleAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OwnerRepository ownerRepository;

    /** Helper: POST /auth/google and return the parsed body. */
    private String googleSignIn(String idToken, String businessName, int expectedStatus) throws Exception {
        String body = businessName == null
                ? "{\"idToken\":\"%s\"}".formatted(idToken)
                : "{\"idToken\":\"%s\",\"businessName\":\"%s\"}".formatted(idToken, businessName);
        return mockMvc.perform(post("/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void firstGoogleSignIn_createsBusinessAndOwner_withNoPassword() throws Exception {
        String email = uniqueEmail();
        String idToken = fakeGoogle.givenGoogleAccount("google-sub-" + email, email, "Shiva G", true);

        var response = objectMapper.readTree(googleSignIn(idToken, "Shiva's Barbers", 200));

        // Signing in for the first time IS signing up — no separate screen.
        assertThat(response.get("newAccount").asBoolean()).isTrue();
        assertThat(response.get("token").asString().split("\\.")).hasSize(3); // a real JWT

        var owner = ownerRepository.findByEmail(email).orElseThrow();
        assertThat(owner.getGoogleSub()).isEqualTo("google-sub-" + email);
        // The whole point of the nullable column in V2: no password exists.
        assertThat(owner.getPasswordHash()).isNull();

        // And the token works on a protected route, like any other login.
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + response.get("token").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.name").value("Shiva's Barbers"));
    }

    @Test
    void withoutABusinessName_weInventASensiblePlaceholder() throws Exception {
        String email = uniqueEmail();
        String idToken = fakeGoogle.givenGoogleAccount("google-sub-" + email, email, "Priya", true);

        var response = objectMapper.readTree(googleSignIn(idToken, null, 200));

        mockMvc.perform(get("/me").header("Authorization", "Bearer " + response.get("token").asString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.business.name").value("Priya's Business"));
    }

    @Test
    void secondGoogleSignIn_reusesTheSameAccount_ratherThanCreatingAnother() throws Exception {
        String email = uniqueEmail();
        String sub = "google-sub-" + email;

        objectMapper.readTree(googleSignIn(
                fakeGoogle.givenGoogleAccount(sub, email, "Repeat User", true), "First Shop", 200));

        // Same person, a brand-new token (as Google would issue each time).
        var second = objectMapper.readTree(googleSignIn(
                fakeGoogle.givenGoogleAccount(sub, email, "Repeat User", true), "Ignored Name", 200));

        assertThat(second.get("newAccount").asBoolean()).isFalse();
        // Still exactly one owner, still the original business name.
        mockMvc.perform(get("/me").header("Authorization", "Bearer " + second.get("token").asString()))
                .andExpect(jsonPath("$.business.name").value("First Shop"));
    }

    @Test
    void googleSignIn_linksToAnExistingPasswordAccount_withTheSameEmail() throws Exception {
        // Someone signed up with a password first...
        String email = uniqueEmail();
        signupAndLogin(email, "Password First Shop");
        Long originalOwnerId = ownerRepository.findByEmail(email).orElseThrow().getId();

        // ...and later clicks "Sign in with Google" with the same address.
        String idToken = fakeGoogle.givenGoogleAccount("google-sub-linked", email, "Linked User", true);
        var response = objectMapper.readTree(googleSignIn(idToken, null, 200));

        assertThat(response.get("newAccount").asBoolean()).isFalse();

        // ACCOUNT LINKING: same row, now with a google_sub — NOT a second
        // account holding none of their queues.
        var owner = ownerRepository.findByEmail(email).orElseThrow();
        assertThat(owner.getId()).isEqualTo(originalOwnerId);
        assertThat(owner.getGoogleSub()).isEqualTo("google-sub-linked");
        assertThat(owner.getPasswordHash()).isNotNull(); // their password still works too
    }

    @Test
    void googleSignIn_refusesToLink_whenGoogleHasNotVerifiedTheEmail() throws Exception {
        String email = uniqueEmail();
        signupAndLogin(email, "Protected Shop");
        Long originalOwnerId = ownerRepository.findByEmail(email).orElseThrow().getId();

        // emailVerified = false: Google is NOT vouching that this person owns
        // the mailbox. Linking here would be account takeover.
        String idToken = fakeGoogle.givenGoogleAccount("attacker-sub", email, "Not Really You", false);
        googleSignIn(idToken, null, 401);

        // Nothing was attached to the victim's account.
        assertThat(ownerRepository.findByEmail(email).orElseThrow().getGoogleSub()).isNull();
        assertThat(ownerRepository.findById(originalOwnerId)).isPresent();
    }

    @Test
    void aForgedOrUnknownGoogleToken_is401() throws Exception {
        googleSignIn("not-a-token-we-ever-issued", null, 401);
    }

    @Test
    void googleOnlyOwner_cannotSignInWithAPassword() throws Exception {
        String email = uniqueEmail();
        googleSignIn(fakeGoogle.givenGoogleAccount("google-only-sub", email, "No Password", true), null, 200);

        // password_hash is NULL for this owner. The check must fail cleanly
        // (401, same generic message) — never crash on the null.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"anything123\"}".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }
}
