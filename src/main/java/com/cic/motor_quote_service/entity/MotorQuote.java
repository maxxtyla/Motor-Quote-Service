package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Motor insurance quote entity.
 * Maps to PostgreSQL table: motor_quotes
 *
 * INTERN NOTE ON RELATIONSHIPS:
 *   - @ManyToOne(fetch = FetchType.LAZY)  ← ALWAYS add this. Without it,
 *     loading one quote would also load the entire AppUser and Vehicle rows.
 *   - @OneToMany(mappedBy = ..., fetch = FetchType.LAZY)  ← default, but explicit.
 *   - Never call quote.getPolicyholder().getQuotes() inside a loop — that's an N+1.
 *
 * PHASE 2 CHANGE: Added policyholder and vehicle FK relationships.
 * The denormalised columns (vehicle_make, vehicle_model, etc.) are kept
 * intentionally — they snapshot the vehicle at quote time so historical
 * quotes are not affected if the Vehicle record changes later.
 *
 * MERGE NOTE: "policyholder" used to point at a separate PolicyHolder
 * entity/table. It now points at AppUser, since the customer and the
 * portal login are the same person. The column name (policyholder_id)
 * is unchanged to avoid a DB migration.
 */
@Entity
@Table(name = "motor_quotes",
        indexes = {
                @Index(name = "idx_quotes_number", columnList = "quote_number"),
                @Index(name = "idx_quotes_reg", columnList = "vehicle_reg_number"),
                @Index(name = "idx_quotes_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MotorQuote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "quote_number", nullable = false, unique = true, length = 30)
    private String quoteNumber;             // e.g., "QTE-20260101-ABC123"

    // ── FK Relationships (LAZY — critical for performance) ────────────────────

    /**
     * The customer (AppUser) this quote belongs to.
     * AppUser now holds both login credentials AND policyholder/KYC data,
     * so this FK points at app_users.id — column name kept as
     * policyholder_id since that's already the column in the existing DB.
     *
     * FetchType.LAZY: JPA will NOT load the AppUser when you load MotorQuote.
     * Access it only when you explicitly need it, inside a transaction.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "policyholder_id")
    private AppUser policyholder;

    /**
     * The specific vehicle being quoted.
     * Also LAZY — same reasoning as above.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    // ── Denormalised vehicle snapshot (kept for historical accuracy) ──────────

    @Column(name = "vehicle_reg_number", nullable = false, length = 15)
    private String vehicleRegNumber;

    @Column(name = "vehicle_make", nullable = false, length = 50)
    private String vehicleMake;

    @Column(name = "vehicle_model", nullable = false, length = 50)
    private String vehicleModel;

    @Column(name = "vehicle_year", nullable = false)
    private Integer vehicleYear;

    // ── Financial ─────────────────────────────────────────────────────────────

    @Column(name = "sum_insured", nullable = false, precision = 15, scale = 2)
    private BigDecimal sumInsured;

    @Column(name = "premium", nullable = false, precision = 15, scale = 2)
    private BigDecimal premium;

    // ── Insured party ─────────────────────────────────────────────────────────

    @Column(name = "insured_name", nullable = false, length = 100)
    private String insuredName;

    @Column(name = "phone_number", nullable = false, length = 15)
    private String phoneNumber;

    // ── Quote cover items (one-to-many) ───────────────────────────────────────

    /**
     * The individual cover lines on this quote (e.g., Own Damage, Third Party).
     * CascadeType.ALL: saving/deleting a quote cascades to its items.
     * orphanRemoval: if you remove an item from the list, it's deleted from DB.
     */
    @OneToMany(mappedBy = "quote", fetch = FetchType.LAZY,
            cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<QuoteItem> items = new ArrayList<>();

    // ── Status ────────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private QuoteStatus status = QuoteStatus.DRAFT;

    // ── Audit timestamps ──────────────────────────────────────────────────────

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Enum ──────────────────────────────────────────────────────────────────

    public enum QuoteStatus {
        DRAFT,      // Just created, not yet reviewed
        ACTIVE,     // Approved and valid for 30 days
        EXPIRED,    // 30-day window passed
        CONVERTED   // Paid for — became a policy
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Adds a cover item and sets the back-reference. Required for bidirectional sync. */
    public void addItem(QuoteItem item) {
        items.add(item);
        item.setQuote(this);
    }
}
