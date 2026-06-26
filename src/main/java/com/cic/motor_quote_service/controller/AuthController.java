package com.cic.motor_quote_service.controller;

import com.cic.motor_quote_service.dto.request.LoginRequest;
import com.cic.motor_quote_service.dto.request.RefreshTokenRequest;
import com.cic.motor_quote_service.dto.request.RegisterRequest;
import com.cic.motor_quote_service.dto.response.AuthResponse;
import com.cic.motor_quote_service.entity.AppUser;
import com.cic.motor_quote_service.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

/**
 * Public authentication endpoints — no JWT required.
 *
 * Routes:
 *   POST /auth/register
 *   POST /auth/login    — username + password → access token + refresh token
 *   POST /auth/refresh  — refresh token → new access token
 *   POST /auth/logout   — revoke refresh tokens (requires valid access token)
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    /**
     * POST /auth/register
     *
     * Public endpoint — no JWT required.
     * New users always get ROLE_USER. Admins are promoted via SQL or an
     * admin-only endpoint, never through self-registration.
     */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    /**
     * POST /auth/login
     *
     * Request:  { "username": "agent01", "password": "s3cr3t" }
     * Response: { "accessToken": "...", "refreshToken": "...", "expiresAt": "...", ... }
     */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {

        String ipAddress = getClientIp(httpRequest);
        AuthResponse response = authService.login(request, ipAddress);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /auth/refresh
     *
     * Called when the access token expires (every 15 min).
     * Client sends the 7-day refresh token and gets a new access token.
     *
     * Request:  { "refreshToken": "..." }
     * Response: { "accessToken": "...", "refreshToken": "..." }
     */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request) {

        AuthResponse response = authService.refresh(request);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /auth/logout
     *
     * Revokes all refresh tokens for the authenticated user.
     * Access tokens remain valid until their 15-min expiry.
     *
     * Requires: Authorization: Bearer <access-token>
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AppUser user) {
        authService.logout(user.getId());
        return ResponseEntity.noContent().build();
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
