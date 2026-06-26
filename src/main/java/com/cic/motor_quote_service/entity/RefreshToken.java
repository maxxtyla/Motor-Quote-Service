package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Persisted refresh tokens — allows server-side token revocation.
 * Maps to PostgreSQL table: refresh_tokens.
 *
 * WHY PERSIST REFRESH TOKENS?
 *   JWTs are stateless by design, but we need to be able to revoke sessions
 *   (e.g., user changes password, account is locked, suspicious activity).
 *   Storing the refresh token hash lets us check validity server-side at
 *   /auth/refresh time without touching every request.
 *
 *   Access tokens (15 min) are NOT stored — short expiry limits damage.
 *   Refresh tokens (7 days) ARE stored — so we can invalidate them.
 *
 * INTERN NOTE: Never store raw tokens. Store SHA-256 hashes only.
 * The raw token is only ever sent to the client over HTTPS.
 */
@Entity
@Table(name = "refresh_tokens",
        indexes = {
                @Index(name = "idx_rt_token_hash", columnList = "token_hash"),
                @Index(name = "idx_rt_user",       columnList = "user_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    /**
     * SHA-256 hash of the raw refresh token.
     * We hash before storing so that even a DB breach can't replay tokens.
     */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;        // now + 7 days

    @Column(name = "revoked", nullable = false)
    @Builder.Default
    private boolean revoked = false;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "device_info", length = 200)
    private String deviceInfo;              // User-Agent from request — for audit

    @Column(name = "ip_address", length = 45)
    private String ipAddress;              // IPv4 or IPv6 — for audit

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── Helpers ───────────────────────────────────────────────────────────────

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    public boolean isValid() {
        return !revoked && !isExpired();
    }
}
