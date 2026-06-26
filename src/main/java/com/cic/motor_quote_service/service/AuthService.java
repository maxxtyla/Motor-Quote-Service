package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.MotorQuoteServiceApplication;
import com.cic.motor_quote_service.dto.request.LoginRequest;
import com.cic.motor_quote_service.dto.request.RefreshTokenRequest;
import com.cic.motor_quote_service.dto.request.RegisterRequest;
import com.cic.motor_quote_service.dto.response.AuthResponse;
import com.cic.motor_quote_service.entity.AppUser;
import com.cic.motor_quote_service.entity.RefreshToken;
import com.cic.motor_quote_service.exception.DuplicateResourceException;
import com.cic.motor_quote_service.repository.AppUserRepository;
import com.cic.motor_quote_service.repository.RefreshTokenRepository;
import com.cic.motor_quote_service.security.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * Handles JWT login, token refresh, and logout for CIC Motor Quote Service.
 *
 * LOGIN FLOW:
 *   1. Authenticate username/password via Spring Security AuthenticationManager
 *   2. Generate access token (15 min) + refresh token (7 days)
 *   3. Hash and persist refresh token in PostgreSQL
 *   4. Return both tokens to client
 *
 * REFRESH FLOW:
 *   1. Validate raw refresh token (JWT signature + expiry)
 *   2. Look up hashed version in DB — confirm not revoked
 *   3. Issue new access token (refresh token stays the same unless near expiry)
 *
 * LOGOUT FLOW:
 *   Revoke all refresh tokens for the user in DB.
 *   Existing access tokens remain valid until expiry (15 min max).
 *   This is acceptable — for immediate invalidation, use Redis blocklist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final AppUserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    // Lock for 30 minutes after MAX_FAILED_ATTEMPTS
    private static final int LOCK_DURATION_MINUTES = 30;
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check username not already taken
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DuplicateResourceException(
                    "Username already taken: " + request.getUsername());
        }

        // Check email not already registered
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException(
                    "Email already registered: " + request.getEmail());
        }

        // Check ID number not already registered — one portal account per person
        if (userRepository.existsByIdNumber(request.getIdNumber())) {
            throw new DuplicateResourceException(
                    "An account already exists for ID number: " + request.getIdNumber());
        }

        // Build the user — password is hashed here by Spring's encoder
        // Plain text password NEVER touches the database
        AppUser newUser = AppUser.builder()
                .username(request.getUsername())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .email(request.getEmail())
                .fullName(request.getFullName())
                .role(AppUser.Role.ROLE_USER)   // New signups are always ROLE_USER
                // ── Policyholder / KYC fields — same person, same row ──────────
                .customerNumber(generateCustomerNumber())
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .idNumber(request.getIdNumber().trim())
                .phoneNumber(request.getPhoneNumber().trim())
                .dateOfBirth(request.getDateOfBirth())
                .address(request.getAddress())
                .city(request.getCity())
                .kraPin(request.getKraPin())
                .build();                        // Admin promotes role manually if needed

        AppUser saved = userRepository.save(newUser);

        // Issue tokens immediately — user is logged in after registration
        String accessToken  = jwtService.generateAccessToken(saved);
        String refreshToken = jwtService.generateRefreshToken(saved);
        persistRefreshToken(saved, refreshToken, "registration");

        log.info("REGISTRATION SUCCESS | user={} | email={} | customerNumber={}",
                saved.getUsername(), saved.getEmail(), saved.getCustomerNumber());

        return buildAuthResponse(saved, accessToken, refreshToken);
    }
    @Transactional
    public AuthResponse login(LoginRequest request, String ipAddress) {
        log.info("Login attempt | user={} | ip={}", request.getUsername(), ipAddress);

        try {
            // Spring Security validates password against BCrypt hash in DB
            Authentication auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getUsername(),
                            request.getPassword()
                    )
            );

            AppUser user = (AppUser) auth.getPrincipal();

            // Reset failed attempts on success
            userRepository.resetFailedAttempts(user.getUsername());

            String accessToken  = jwtService.generateAccessToken(user);
            String refreshToken = jwtService.generateRefreshToken(user);

            // Persist hashed refresh token for server-side revocation
            persistRefreshToken(user, refreshToken, ipAddress);

            log.info("LOGIN SUCCESS | user={} | role={} | ip={}", user.getUsername(), user.getRole(), ipAddress);

            return buildAuthResponse(user, accessToken, refreshToken);

        } catch (LockedException e) {
            log.warn("LOGIN BLOCKED — account locked | user={} | ip={} | time={}", request.getUsername(), ipAddress, LocalDateTime.now());
            throw e;

        } catch (BadCredentialsException e) {
            // Track failed attempts and potentially lock account
            handleFailedAttempt(request.getUsername(), ipAddress);
            throw e;

        } catch (AuthenticationException e) {
            log.warn("LOGIN FAILURE | user={} | ip={} | reason={} | time={}", request.getUsername(), ipAddress, e.getMessage(), LocalDateTime.now());
            throw e;
        }
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        String rawToken = request.getRefreshToken();

        // 1. Validate JWT structure and expiry
        if (!jwtService.isTokenValid(rawToken)) {
            log.warn("REFRESH FAILURE — invalid/expired token");
            throw new SecurityException("Refresh token is invalid or expired");
        }

        // 2. Ensure it's actually a refresh token
        String type = (String) jwtService.extractAllClaims(rawToken).get("type");
        if (!"refresh".equals(type)) {
            log.warn("REFRESH FAILURE — access token used as refresh token");
            throw new SecurityException("Invalid token type for refresh");
        }

        // 3. Look up hashed token in DB and verify it hasn't been revoked
        String tokenHash = sha256(rawToken);
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> {
                    log.warn("REFRESH FAILURE — token hash not found in DB (possible token reuse attack)");
                    return new SecurityException("Refresh token not recognised");
                });

        if (!stored.isValid()) {
            log.warn("REFRESH FAILURE — token revoked or expired | userId={}", stored.getUser().getId());
            throw new SecurityException("Refresh token has been revoked");
        }

        // 4. Issue new access token
        AppUser user = stored.getUser();
        String newAccessToken = jwtService.generateAccessToken(user);

        log.info("REFRESH SUCCESS | user={}", user.getUsername());

        return buildAuthResponse(user, newAccessToken, rawToken);  // Reuse same refresh token
    }

    @Transactional
    public void logout(Long userId) {
        refreshTokenRepository.revokeAllForUser(userId, LocalDateTime.now());
        log.info("LOGOUT — all refresh tokens revoked | userId={}", userId);
    }

    // ── Housekeeping ──────────────────────────────────────────────────────────

    /**
     * Cleans up expired refresh tokens daily at 02:00 Nairobi time.
     * Keeps the refresh_tokens table lean.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Africa/Nairobi")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = refreshTokenRepository.deleteExpiredBefore(LocalDateTime.now());
        log.info("SCHEDULED: Purged {} expired refresh tokens", deleted);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void handleFailedAttempt(String username, String ipAddress) {
        userRepository.findByUsername(username).ifPresent(user -> {
            int newCount = user.getFailedAttempts() + 1;
            log.warn("LOGIN FAILURE #{} | user={} | ip={} | time={}", newCount, username, ipAddress, LocalDateTime.now());

            userRepository.incrementFailedAttempts(username);

            if (newCount >= MAX_FAILED_ATTEMPTS) {
                user.setLockedUntil(LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES));
                userRepository.save(user);
                log.warn("ACCOUNT LOCKED — too many failed attempts | user={} | locked until={}",
                        username, user.getLockedUntil());
            }
        });
        // If user doesn't exist, do nothing — don't reveal whether the account exists
    }

    private void persistRefreshToken(AppUser user, String rawToken, String ipAddress) {
        RefreshToken rt = RefreshToken.builder()
                .user(user)
                .tokenHash(sha256(rawToken))
                .expiresAt(LocalDateTime.now().plusNanos(jwtService.getRefreshTokenExpiryMs() * 1_000_000L))
                .ipAddress(ipAddress)
                .build();
        refreshTokenRepository.save(rt);
    }

    private AuthResponse buildAuthResponse(AppUser user, String accessToken, String refreshToken) {
        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .username(user.getUsername())
                .role(user.getRole().name())
                .fullName(user.getFullName())
                .customerNumber(user.getCustomerNumber())
                .build();
    }

    /**
     * Generates a customer-facing reference number for a newly registered
     * policyholder, e.g. "CIC-2026-00001".
     * In production: replace the suffix with a DB sequence for guaranteed
     * uniqueness. For now: timestamp millis last 5 digits (matches the
     * scheme previously used by PolicyHolderService).
     */
    private String generateCustomerNumber() {
        String year = String.valueOf(LocalDateTime.now().getYear());
        String seq = String.format("%05d", System.currentTimeMillis() % 100000);
        return "CIC-" + year + "-" + seq;
    }

    /**
     * SHA-256 hash of a raw token string.
     * We store this in the DB — never the raw token itself.
     */
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
