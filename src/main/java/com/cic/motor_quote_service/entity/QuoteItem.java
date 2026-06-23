package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single cover line on a motor quote.
 * Maps to PostgreSQL table: quote_items
 *
 * Examples of cover types:
 *   - COMPREHENSIVE   — own damage + third party
 *   - THIRD_PARTY     — third party only (minimum legal requirement in Kenya)
 *   - WINDSCREEN      — optional add-on
 *   - POLITICAL_VIOLENCE — optional add-on
 *
 * INTERN NOTE: This entity owns the relationship to MotorQuote
 * (the FK column "quote_id" lives in this table). That's why MotorQuote
 * uses mappedBy = "quote" — it tells JPA that QuoteItem.quote is the owner.
 */
@Entity
@Table(name = "quote_items",
        indexes = {
                @Index(name = "idx_items_quote", columnList = "quote_id")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuoteItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Back-reference to the parent quote.
     * LAZY — loading a quote item should not drag in the whole quote.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quote_id", nullable = false)
    private MotorQuote quote;

    @Column(name = "cover_type", nullable = false, length = 30)
    private String coverType;           // e.g., "COMPREHENSIVE", "WINDSCREEN"

    @Column(name = "sum_insured", nullable = false, precision = 15, scale = 2)
    private BigDecimal sumInsured;

    @Column(name = "premium", nullable = false, precision = 15, scale = 2)
    private BigDecimal premium;

    /**
     * Excess / deductible amount.
     * The amount the insured must pay before CIC pays out.
     * Default 0 for add-ons, typically 2.5% of claim for own damage.
     */
    @Column(name = "excess_amount", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal excessAmount = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
