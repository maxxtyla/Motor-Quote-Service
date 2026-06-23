package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a CIC insurance policyholder (customer).
 * Maps to PostgreSQL table: policyholders
 *
 * INTERN NOTE: @ManyToOne / @OneToMany are LAZY by default in JPA for collections.
 * We explicitly set fetch = FetchType.LAZY on @OneToMany too (it already defaults
 * that way) to be intentional and avoid N+1 queries.
 */
@Entity
@Table(name = "policyholders",
        indexes = {
                @Index(name = "idx_policyholders_customer", columnList = "customer_number"),
                @Index(name = "idx_policyholders_id", columnList = "id_number")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PolicyHolder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_number", nullable = false, unique = true, length = 20)
    private String customerNumber;           // e.g., "CIC-2026-00001"

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    /**
     * Kenyan National ID number. Unique per person.
     * Stored as VARCHAR — never Integer (leading zeros, future formats).
     */
    @Column(name = "id_number", nullable = false, unique = true, length = 20)
    private String idNumber;

    @Column(name = "email", length = 100)
    private String email;

    @Column(name = "phone_number", nullable = false, length = 15)
    private String phoneNumber;              // Format: 2547XXXXXXXX

    @Column(name = "date_of_birth", nullable = false)
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
     * A policyholder can have many quotes.
     * LAZY — we never want to pull all quotes just because we loaded a customer.
     * Use quoteRepository.findByPolicyholderId() when you need the quotes.
     */
    @OneToMany(mappedBy = "policyholder", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @Builder.Default
    private List<MotorQuote> quotes = new ArrayList<>();

    // ── Audit ──────────────────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Convenience ───────────────────────────────────────────────────────────

    /** Full name helper — never stored in DB, derived at runtime. */
    @Transient
    public String getFullName() {
        return firstName + " " + lastName;
    }
}
