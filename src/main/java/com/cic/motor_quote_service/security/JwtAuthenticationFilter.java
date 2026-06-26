package com.cic.motor_quote_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Intercepts every HTTP request and validates the JWT bearer token.
 *
 * FLOW:
 *   1. Extract "Authorization: Bearer <token>" header
 *   2. Validate token signature, expiry, and type (must be "access")
 *   3. Load UserDetails from DB (or cache — see UserDetailsServiceImpl)
 *   4. Set authentication in SecurityContext → request proceeds as authenticated
 *
 * If anything fails, we do NOT throw an exception — we simply leave the
 * SecurityContext empty and let Spring Security return 401 at the endpoint.
 * This is the correct pattern for stateless REST APIs.
 *
 * INTERN NOTE:
 *   - extends OncePerRequestFilter → guaranteed to run exactly once per request.
 *   - Never call chain.doFilter() twice — the response would be corrupted.
 *   - log.warn() on auth failure includes IP and timestamp (CIC audit requirement).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // ── 1. Extract token ──────────────────────────────────────────────────
        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            // No token — pass through; Spring Security will return 401 if endpoint is protected
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(BEARER_PREFIX.length());
        String username = null;

        // ── 2. Parse and validate token ───────────────────────────────────────
        try {
            // Reject refresh tokens used as access tokens
            if (!jwtService.isAccessToken(jwt)) {
                log.warn("AUTH FAILURE — Refresh token used as access token | IP={} | path={} | time={}",
                        getClientIp(request), request.getRequestURI(), LocalDateTime.now());
                filterChain.doFilter(request, response);
                return;
            }

            if (!jwtService.isTokenValid(jwt)) {
                log.warn("AUTH FAILURE — Invalid/expired token | IP={} | path={} | time={}",
                        getClientIp(request), request.getRequestURI(), LocalDateTime.now());
                filterChain.doFilter(request, response);
                return;
            }

            username = jwtService.extractUsername(jwt);

        } catch (Exception e) {
            // Malformed token — log and pass through
            log.warn("AUTH FAILURE — Malformed JWT | IP={} | path={} | error={} | time={}",
                    getClientIp(request), request.getRequestURI(), e.getMessage(), LocalDateTime.now());
            filterChain.doFilter(request, response);
            return;
        }

        // ── 3. Authenticate if not already authenticated ──────────────────────
        // Check SecurityContextHolder — avoid duplicate DB lookups if already set
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // Extra check: account still enabled (might have been locked since token was issued)
                if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
                    log.warn("AUTH FAILURE — Account disabled or locked | user={} | IP={} | time={}",
                            username, getClientIp(request), LocalDateTime.now());
                    filterChain.doFilter(request, response);
                    return;
                }

                // ── 4. Set authentication in SecurityContext ───────────────────
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,                           // credentials = null (already validated via JWT)
                                userDetails.getAuthorities()    // ROLE_ADMIN / ROLE_USER
                        );

                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                SecurityContextHolder.getContext().setAuthentication(authToken);

                log.debug("AUTH SUCCESS — user={} | role={} | path={}",
                        username, userDetails.getAuthorities(), request.getRequestURI());

            } catch (Exception e) {
                log.warn("AUTH FAILURE — User not found | user={} | IP={} | time={}",
                        username, getClientIp(request), LocalDateTime.now());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Extract the real client IP address.
     * Handles X-Forwarded-For header from Kubernetes ingress / load balancers.
     * CIC uses nginx ingress — the real IP is in the first X-Forwarded-For value.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
