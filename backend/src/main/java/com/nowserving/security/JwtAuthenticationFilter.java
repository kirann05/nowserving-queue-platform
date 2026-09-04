package com.nowserving.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * NS-3: runs once per request, BEFORE the authorization rules are evaluated.
 *
 * Job: if there's a valid "Authorization: Bearer <jwt>" header, put an
 * Authentication into the SecurityContext. That's all. It never rejects a
 * request itself — deciding whether authentication is REQUIRED is the
 * authorization layer's job (SecurityConfig). That separation is why public
 * endpoints and protected endpoints can share this one filter.
 *
 * Filter order (worth being able to draw from memory):
 *   request -> [RateLimitFilter] -> [this filter] -> [authorization check]
 *          -> DispatcherServlet -> controller
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            AuthenticatedOwner owner = jwtService.validate(header.substring("Bearer ".length()));
            if (owner != null) {
                // The standard "I am authenticated" container. The 3-arg
                // constructor marks it authenticated; the empty list is the
                // authorities/roles (we don't need roles in Sprint 1).
                var authentication = new UsernamePasswordAuthenticationToken(owner, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            // Invalid token? We simply DON'T authenticate. If the route needs
            // auth, SecurityConfig's entry point will return the 401.
        }

        filterChain.doFilter(request, response);
    }
}
