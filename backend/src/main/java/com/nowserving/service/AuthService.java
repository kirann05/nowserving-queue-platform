package com.nowserving.service;

import com.nowserving.dto.AuthDtos.GoogleLoginRequest;
import com.nowserving.dto.AuthDtos.GoogleLoginResponse;
import com.nowserving.dto.AuthDtos.LoginRequest;
import com.nowserving.dto.AuthDtos.LoginResponse;
import com.nowserving.dto.AuthDtos.MeResponse;
import com.nowserving.dto.AuthDtos.SignupRequest;
import com.nowserving.dto.AuthDtos.SignupResponse;
import com.nowserving.entity.Business;
import com.nowserving.entity.Owner;
import com.nowserving.exception.ConflictException;
import com.nowserving.exception.NotFoundException;
import com.nowserving.exception.UnauthorizedException;
import com.nowserving.repository.BusinessRepository;
import com.nowserving.repository.OwnerRepository;
import com.nowserving.security.AuthenticatedOwner;
import com.nowserving.security.GoogleIdTokenVerifier;
import com.nowserving.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * NS-2 (signup) and NS-3 (login, /me).
 *
 * Layering rule this project follows: controllers translate HTTP <-> DTOs,
 * services hold business rules and transactions, repositories touch the DB.
 * Nothing above a layer reaches below its neighbor.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final BusinessRepository businessRepository;
    private final OwnerRepository ownerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    /** Interface, not the concrete class — tests inject a fake (see the interface's comment). */
    private final GoogleIdTokenVerifier googleVerifier;

    /**
     * NS-2's learning-mode concept: the TRANSACTION BOUNDARY.
     *
     * Signup creates two rows — a Business and an Owner. @Transactional wraps
     * the method in one DB transaction: both INSERTs commit together or, if
     * anything throws, both roll back. Without it, a crash between the two
     * saves leaves an orphaned business with no owner able to log in —
     * inconsistent state that no retry can fix, because the email might then
     * exist while the business doesn't. Atomicity (the A in ACID) is exactly
     * this guarantee.
     */
    @Transactional
    public SignupResponse signup(SignupRequest request) {
        // Friendly pre-check for the common case...
        if (ownerRepository.existsByEmail(request.email())) {
            throw new ConflictException("An account with this email already exists");
        }

        Business business = businessRepository.save(new Business(request.businessName()));
        Owner owner;
        try {
            owner = ownerRepository.save(new Owner(
                    business,
                    request.email(),
                    // BCrypt happens HERE, at the boundary where plaintext
                    // enters the system. Past this line the password no longer
                    // exists anywhere.
                    passwordEncoder.encode(request.password()),
                    request.displayName()));
        } catch (DataIntegrityViolationException e) {
            // ...but the DB's UNIQUE constraint is the real guarantee. Two
            // simultaneous signups with the same email can BOTH pass the
            // pre-check (race window); exactly one INSERT wins, the loser
            // lands here. App-level checks are UX; constraints are law.
            throw new ConflictException("An account with this email already exists");
        }

        return new SignupResponse(owner.getId(), business.getId(), business.getName(),
                owner.getEmail(), owner.getDisplayName());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        // SECURITY (NS-3 acceptance criterion): unknown email and wrong
        // password produce the IDENTICAL error. If they differed, an attacker
        // could probe which emails have accounts ("user enumeration").
        Owner owner = ownerRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));

        // A Google-only owner has no password hash at all (FR-1). We answer
        // with the same generic message rather than "this account uses
        // Google" — friendlier copy would also tell an attacker which
        // accounts to phish and how.
        if (owner.getPasswordHash() == null
                || !passwordEncoder.matches(request.password(), owner.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password");
        }

        return issueTokenFor(owner);
    }

    /**
     * FR-1 (Google half). Three cases, and the middle one is where security
     * bugs live:
     *
     *  1. WE KNOW THIS GOOGLE ID       -> log them straight in.
     *  2. WE KNOW THIS EMAIL           -> "account linking": the same human
     *     already signed up with a password, and is now using the Google
     *     button. We attach their Google id to the existing account so they
     *     don't end up with two accounts and half their queues in each.
     *     GUARDED by email_verified: we only trust the email as proof of
     *     identity if GOOGLE says it verified ownership of that mailbox.
     *     Skipping that check is a real account-takeover vector — someone
     *     signs up to an identity provider claiming your email, and walks
     *     into your account.
     *  3. WE KNOW NEITHER              -> brand new owner + business, with no
     *     password at all (they'll always use the Google button).
     */
    @Transactional
    public GoogleLoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleIdTokenVerifier.GoogleAccount account = googleVerifier.verify(request.idToken());

        // Case 1 — returning Google user.
        Optional<Owner> byGoogleId = ownerRepository.findByGoogleSub(account.subject());
        if (byGoogleId.isPresent()) {
            LoginResponse token = issueTokenFor(byGoogleId.get());
            return new GoogleLoginResponse(token.token(), token.expiresAt(), false);
        }

        // Case 2 — link to the existing password account with the same email.
        Optional<Owner> byEmail = ownerRepository.findByEmail(account.email());
        if (byEmail.isPresent()) {
            if (!account.emailVerified()) {
                throw new UnauthorizedException(
                        "Google has not verified this email address, so it cannot be linked to an existing account");
            }
            Owner existing = byEmail.get();
            existing.setGoogleSub(account.subject()); // managed entity: flushed on commit
            LoginResponse token = issueTokenFor(existing);
            return new GoogleLoginResponse(token.token(), token.expiresAt(), false);
        }

        // Case 3 — first time we've seen this person: create business + owner
        // together, same one-transaction rule as password signup (NS-2).
        String businessName = (request.businessName() != null && !request.businessName().isBlank())
                ? request.businessName()
                : account.displayName() + "'s Business"; // sensible placeholder; UI offers a rename
        Business business = businessRepository.save(new Business(businessName));

        Owner owner = new Owner(business, account.email(), null, account.displayName());
        owner.setGoogleSub(account.subject());
        owner = ownerRepository.save(owner);

        LoginResponse token = issueTokenFor(owner);
        return new GoogleLoginResponse(token.token(), token.expiresAt(), true);
    }

    /** The one place a session token is minted, however the owner proved who they are. */
    private LoginResponse issueTokenFor(Owner owner) {
        var principal = new AuthenticatedOwner(owner.getId(), owner.getBusiness().getId(), owner.getEmail());
        JwtService.IssuedToken issued = jwtService.issue(principal);
        return new LoginResponse(issued.token(), issued.expiresAt());
    }

    /**
     * readOnly = true is a real optimization hint (no dirty-checking flush)
     * AND documentation: this method promises not to write.
     *
     * The lazy owner.getBusiness() proxy is safe to touch here because we are
     * inside the transaction — this is where the DTO gets built. By the time
     * the controller returns, only plain records remain.
     */
    @Transactional(readOnly = true)
    public MeResponse me(Long ownerId) {
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("Owner not found"));
        Business business = owner.getBusiness();
        return new MeResponse(owner.getId(), owner.getEmail(), owner.getDisplayName(),
                new MeResponse.BusinessSummary(business.getId(), business.getName(), business.getPublicToken()));
    }
}
