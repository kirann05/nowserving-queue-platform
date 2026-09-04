package com.nowserving.controller;

import com.nowserving.dto.AuthDtos.GoogleLoginRequest;
import com.nowserving.dto.AuthDtos.GoogleLoginResponse;
import com.nowserving.dto.AuthDtos.LoginRequest;
import com.nowserving.dto.AuthDtos.LoginResponse;
import com.nowserving.dto.AuthDtos.MeResponse;
import com.nowserving.dto.AuthDtos.SignupRequest;
import com.nowserving.dto.AuthDtos.SignupResponse;
import com.nowserving.security.AuthenticatedOwner;
import com.nowserving.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * NS-2/NS-3 endpoints. Controllers in this project are deliberately thin:
 * bind + validate the request, delegate to the service, shape the response.
 * If a controller method grows an `if`, the logic probably belongs one layer
 * down.
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * @Valid triggers Bean Validation against SignupRequest's annotations;
     * failures never reach the service — they become a 400 via the
     * GlobalExceptionHandler. 201 Created is the correct verb-result for
     * "a new resource now exists".
     */
    @PostMapping("/auth/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public SignupResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * FR-1: "Sign in with Google". The browser has already talked to Google
     * and holds a signed ID token; it posts that here and gets one of OUR
     * JWTs back, identical to what a password login returns.
     *
     * Public route (it's how you log in), and the token in the body is the
     * credential — so it must be POST, never GET: query strings end up in
     * browser history, proxy logs and Referer headers.
     */
    @PostMapping("/auth/google")
    public GoogleLoginResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return authService.loginWithGoogle(request);
    }

    /**
     * @AuthenticationPrincipal hands us the AuthenticatedOwner that the
     * JwtAuthenticationFilter stored — by this point Security has already
     * rejected unauthenticated callers with 401, so it is never null here.
     */
    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedOwner principal) {
        return authService.me(principal.ownerId());
    }
}
