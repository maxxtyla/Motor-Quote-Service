package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * CIC system user — maps to PostgreSQL table: app_users.
 *
 * Implements UserDetails so Spring Security can load and validate
 * this entity directly from the database.
 *
 * ROLES:
 *   ROLE_ADMIN  — full access (can DELETE, view audit logs)
 *   ROLE_USER   — read-only + can create/update quotes (no deletes)
 *   ROLE_AGENT  — CIC field agent, same as ROLE_USER but scoped to own records
 *
 * INTERN NOTE: We implement UserDetails here rather than a separate adapter
 * class to keep things simple. In larger systems you'd separate the JPA entity
 * from the Spring Security principal to avoid coupling.
 */
@Entity
@Table(name = "app_users",
        indexes = {
                @Index(name = "idx_users_username", columnList = "username"),
                @Index(name = "idx_users_email",    columnList = "email")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppUser implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;                // Login identifier

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;            // BCrypt hash — NEVER store plain text

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    @Builder.Default
    private Role role = Role.ROLE_USER;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "account_non_locked", nullable = false)
    @Builder.Default
    private boolean accountNonLocked = true;

    /**
     * Tracks failed login attempts — lock after 5 failures.
     * Reset to 0 on successful login.
     */
    @Column(name = "failed_attempts")
    @Builder.Default
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;      // Null = not locked

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── UserDetails contract ──────────────────────────────────────────────────

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Spring Security expects role strings like "ROLE_ADMIN"
        return List.of(new SimpleGrantedAuthority(role.name()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;    // We don't expire accounts — use enabled flag instead
    }

    @Override
    public boolean isAccountNonLocked() {
        // Honour the timed lock if one is set
        if (lockedUntil != null && LocalDateTime.now().isBefore(lockedUntil)) {
            return false;
        }
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;    // JWT handles credential expiry
    }

    // ── Enum ──────────────────────────────────────────────────────────────────

    public enum Role {
        ROLE_ADMIN,
        ROLE_USER,
        ROLE_AGENT
    }
}
