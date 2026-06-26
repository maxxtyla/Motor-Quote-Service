package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * CIC system user — maps to PostgreSQL table: app_users.
 *
 * MERGED ENTITY: this used to be two tables (app_users for login,
 * policyholders for customer data). Since every policyholder logs in to
 * the portal with the same credentials they buy policies with, the two
 * are now ONE row in ONE table. Login fields and customer/KYC fields
 * live side by side here.
 *
 * Implements UserDetails so Spring Security can load and validate
 * this entity directly from the database.
 *
 * ROLES:
 *   ROLE_ADMIN  — full access (can DELETE, view audit logs)
 *   ROLE_USER   — a customer/policyholder — can create/update their own quotes
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

    // ── Login / auth fields ──────────────────────────────────────────────────

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

    // ── Customer / policyholder (KYC) fields ─────────────────────────────────
    // These used to live on a separate `policyholders` table — merged here
    // because the customer and the portal login are the same person.

    @Column(name = "customer_number", unique = true, length = 20)
    private String customerNumber;          // e.g., "CIC-2026-00001"

    @Column(name = "first_name", length = 50)
    private String firstName;

    @Column(name = "last_name", length = 50)
    private String lastName;

    /**
     * Kenyan National ID number. Unique per person.
     * Stored as VARCHAR — never Integer (leading zeros, future formats).
     */
    @Column(name = "id_number", unique = true, length = 20)
    private String idNumber;

    @Column(name = "phone_number", length = 15)
    private String phoneNumber;             // Format: 2547XXXXXXXX

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "address", length = 200)
    private String address;

    @Column(name = "city", length = 50)
    private String city;

    /**
     * Kenya Revenue Authority PIN — required for premiums above KES 100,000.
     * Optional at quote stage, mandatory at policy issuance.
     */
    @Column(name = "kra_pin", length = 20)
    private String kraPin;

    // ── Relationships ──────────────────────────────────────────────────────────

    /**
     * Quotes belonging to this user (as the policyholder).
     * LAZY — we never want to pull all quotes just because we loaded a user.
     * Use motorQuoteRepository.findByPolicyholderId() when you need the quotes.
     */
    @OneToMany(mappedBy = "policyholder", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @Builder.Default
    private List<MotorQuote> quotes = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Convenience ───────────────────────────────────────────────────────────

    /** Full name helper for the policyholder fields — derived at runtime, never stored. */
    @Transient
    public String getPolicyholderFullName() {
        if (firstName == null && lastName == null) {
            return fullName;
        }
        return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
    }

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
