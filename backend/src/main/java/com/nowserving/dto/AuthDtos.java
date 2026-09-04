package com.nowserving.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Request/response shapes for /auth/** and /me (NS-2, NS-3), grouped in one
 * file since each is a few lines.
 *
 * Why DTOs at all, instead of accepting/returning entities?
 *  1. The API contract and the DB schema can now evolve independently.
 *  2. No accidental exposure — an Owner entity serialized to JSON would ship
 *     the passwordHash to the client.
 *  3. No LazyInitializationException — serializing a lazy entity outside a
 *     transaction is the sprint doc's "likely problem #3". DTOs are built
 *     while the transaction is open; controllers only ever see plain data.
 *
 * Java records fit DTOs perfectly: immutable, all-args constructor, and
 * Jackson (de)serializes them with zero configuration. Validation annotations
 * on record components are checked when a controller parameter has @Valid.
 */
public final class AuthDtos {

    private AuthDtos() {}

    public record SignupRequest(
            @NotBlank String businessName,
            @NotBlank @Email String email,
            // NIST guidance: length is the one password rule that matters.
            @NotBlank @Size(min = 8, message = "password must be at least 8 characters") String password,
            @NotBlank String displayName) {}

    public record SignupResponse(
            Long ownerId,
            Long businessId,
            String businessName,
            String email,
            String displayName) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {}

    /** expiresAt lets clients refresh proactively instead of discovering a dead token via 401. */
    public record LoginResponse(String token, Instant expiresAt) {}

    /**
     * FR-1: "Sign in with Google". The browser gets an ID token from Google
     * and hands it to us; businessName is only used the first time we meet
     * this person (there is no separate Google "sign-up" screen — signing in
     * for the first time IS signing up, which is why the flow feels so short).
     */
    public record GoogleLoginRequest(
            @NotBlank String idToken,
            String businessName) {}

    /**
     * Same token a password login returns, plus newAccount so the UI can say
     * "welcome — want to rename your business?" instead of silently guessing
     * a name for them.
     */
    public record GoogleLoginResponse(String token, Instant expiresAt, boolean newAccount) {}

    public record MeResponse(
            Long ownerId,
            String email,
            String displayName,
            BusinessSummary business) {

        /**
         * publicToken is the restaurant's permanent QR identity. It rides on
         * /me so the owner console has it from the first render — there is no
         * separate "generate my QR" call to make, and no state where a
         * restaurant exists but its QR does not.
         */
        public record BusinessSummary(Long id, String name, String publicToken) {}
    }
}
