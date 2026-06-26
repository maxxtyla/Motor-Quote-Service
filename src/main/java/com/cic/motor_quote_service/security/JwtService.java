package com.cic.motor_quote_service.security;

import com.cic.motor_quote_service.entity.AppUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * JWT token generation, parsing, and validation for CIC Motor Quote Service.
 *
 * TOKEN STRATEGY:
 *   Access token  — 15 minutes. Sent in Authorization: Bearer header every request.
 *   Refresh token — 7 days. Sent to POST /auth/refresh to get a new access token.
 *   The refresh token is also persisted in the DB (see RefreshToken entity) so
 *   we can revoke it server-side (password change, suspicious activity, logout).
 *
 * SECURITY NOTES:
 *   - Secret is loaded from env var JWT_SECRET — NEVER hardcoded.
 *   - We use HMAC-SHA256 (HS256) — symmetric, fast, sufficient for single-service.
 *   - Claims include: sub (username), role, userId, jti (unique token ID for revocation).
 *   - Tokens are NEVER stored — only the refresh token hash is persisted.
 *
 * INTERN: If you add a new claim here, add it to extractClaims() too.
 */
@Service
@Slf4j
public class JwtService {

    // Loaded from env: JWT_SECRET (must be >= 32 chars for HS256)
    @Value("${app.jwt.secret}")
    private String jwtSecret;

    // 15 minutes in milliseconds
    @Value("${app.jwt.access-token-expiry-ms:900000}")
    private long accessTokenExpiryMs;

    // 7 days in milliseconds
    @Value("${app.jwt.refresh-token-expiry-ms:604800000}")
    private long refreshTokenExpiryMs;

    // ── Token Generation ──────────────────────────────────────────────────────

    /**
     * Generates a 15-minute access token for the given user.
     *
     * Claims included:
     *   sub     = username
     *   userId  = DB primary key
     *   role    = ROLE_ADMIN / ROLE_USER / ROLE_AGENT
     *   type    = "access"
     *   jti     = UUID (unique per token — needed for future revocation)
     */
    public String generateAccessToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("role",   user.getRole().name())
                .claim("type",   "access")
                .id(UUID.randomUUID().toString())   // jti claim
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(accessTokenExpiryMs)))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * Generates a 7-day refresh token.
     * The raw token is returned to the client once; we store only the hash.
     */
    public String generateRefreshToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("userId", user.getId())
                .claim("type",   "refresh")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(refreshTokenExpiryMs)))
                .signWith(getSigningKey())
                .compact();
    }

    // ── Token Validation ──────────────────────────────────────────────────────

    /**
     * Returns true if the token is structurally valid, correctly signed,
     * and not yet expired. Called on every request in JwtAuthenticationFilter.
     */
    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.debug("JWT expired: {}", e.getMessage());
            return false;
        } catch (JwtException e) {
            log.warn("Invalid JWT: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validates that this is specifically an access token (not a refresh token
     * being replayed as auth). Prevents clients from using a refresh token
     * to call protected endpoints.
     */
    public boolean isAccessToken(String token) {
        try {
            String type = (String) parseClaims(token).get("type");
            return "access".equals(type);
        } catch (JwtException e) {
            return false;
        }
    }

    // ── Claims Extraction ─────────────────────────────────────────────────────

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public Long extractUserId(String token) {
        return parseClaims(token).get("userId", Long.class);
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public Date extractExpiration(String token) {
        return parseClaims(token).getExpiration();
    }

    public Map<String, Object> extractAllClaims(String token) {
        return parseClaims(token);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Derives the HMAC-SHA256 signing key from the secret string.
     * Called on every parse/sign — Keys.hmacShaKeyFor is cheap.
     *
     * IMPORTANT: The secret must be >= 256 bits (32 chars) for HS256.
     * Use: openssl rand -hex 32   to generate a suitable secret.
     */
    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public long getRefreshTokenExpiryMs() {
        return refreshTokenExpiryMs;
    }
}
