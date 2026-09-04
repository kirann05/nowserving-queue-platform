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
 * NS-2 + NS-3 acceptance criteria, as executable checks.
 * Each @Test maps to a Given/When/Then block from the sprint backlog.
 */
class AuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private OwnerRepository ownerRepository;

    @Test
    void signup_createsBusinessAndOwner_andNeverStoresPlaintextPassword() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"Shiva's Barbers","email":"%s","password":"secret123","displayName":"Shiva"}
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").isNumber())
                .andExpect(jsonPath("$.businessId").isNumber())
                .andExpect(jsonPath("$.businessName").value("Shiva's Barbers"));

        // "the stored password_hash is NOT the plaintext password"
        var owner = ownerRepository.findByEmail(email).orElseThrow();
        assertThat(owner.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(owner.getPasswordHash()).startsWith("$2"); // BCrypt hashes are versioned: $2a$/$2b$...
    }

    @Test
    void signup_withExistingEmail_returns409() throws Exception {
        String email = uniqueEmail();
        String payload = """
                {"businessName":"First","email":"%s","password":"secret123","displayName":"One"}
                """.formatted(email);

        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this email already exists"));
    }

    @Test
    void signup_withInvalidPayload_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"businessName":"","email":"not-an-email","password":"short","displayName":"X"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists())
                .andExpect(jsonPath("$.fieldErrors.businessName").exists());
    }

    @Test
    void login_withValidCredentials_returnsJwt() throws Exception {
        String email = uniqueEmail();
        String token = signupAndLogin(email, "Login Test Shop");
        assertThat(token).isNotBlank();
        // A JWT is always three dot-separated base64url sections.
        assertThat(token.split("\\.")).hasSize(3);
    }

    @Test
    void login_failure_doesNotRevealWhetherEmailExists() throws Exception {
        String email = uniqueEmail();
        signupAndLogin(email, "Enumeration Test Shop");

        // Wrong password for a REAL account...
        var wrongPassword = objectMapper.readTree(mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"wrong-password\"}".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString());

        // ...and a login for an email that has NO account...
        var unknownEmail = objectMapper.readTree(mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ghost-%s\",\"password\":\"whatever1\"}".formatted(email)))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString());

        // ...must be indistinguishable: same status and the exact same message.
        // (We compare fields, not raw bodies — every ApiError carries its own
        // timestamp, which legitimately differs between the two responses.)
        assertThat(unknownEmail.get("message")).isEqualTo(wrongPassword.get("message"));
        assertThat(unknownEmail.get("message").asString()).isEqualTo("Invalid email or password");
    }

    @Test
    void me_withValidToken_returnsOwnerAndBusiness() throws Exception {
        String email = uniqueEmail();
        String token = signupAndLogin(email, "Me Test Shop");

        mockMvc.perform(get("/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.business.name").value("Me Test Shop"));
    }

    @Test
    void me_withoutToken_orGarbageToken_returns401() throws Exception {
        mockMvc.perform(get("/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/me").header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }
}
