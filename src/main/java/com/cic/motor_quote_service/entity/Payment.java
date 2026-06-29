package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment record for a motor quote.
 * Maps to PostgreSQL table: payments
 *
 * CIC processes payments via three channels:
 *   1. M-Pesa STK Push (most common in Kenya)
 *   2. Airtel Money
 *   3. PesaLink (bank-to-bank)
 *
 * INTERN NOTE ON PAYMENT FLOW:
 *   PENDING → (M-Pesa callback received) → COMPLETED or FAILED
 *   FAILED  → (retry) → PENDING again (retry_count tracks this)
 *   A quote becomes CONVERTED only when a payment reaches COMPLETED.
 *
 * INTERN NOTE ON IDEMPOTENCY:
 *   payment_reference is unique. If M-Pesa sends the same callback twice
 *   (it does!), the second insert will fail with a unique constraint violation.
 *   Handle this in the service — catch DataIntegrityViolationException and
 *   return the existing payment record instead of saving a duplicate.
 */
@Entity
@Table(name = "payments",
        indexes = {
                @Index(name = "idx_payments_ref", columnList = "payment_reference"),
                @Index(name = "idx_payments_mpesa", columnList = "mpesa_receipt_number"),
                @Index(name = "idx_payments_status", columnList = "status")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quote_id")
    private MotorQuote quote;

    /**
     * Our internal reference — created before we call M-Pesa.
     * Used to match M-Pesa callbacks back to this record.
     */
    @Column(name = "payment_reference", nullable = false, unique = true, length = 50)
    private String paymentReference;

    /**
     * The receipt number M-Pesa returns in the callback (e.g., "QKJ12AB34C").
     * Null until the callback arrives. Use this for customer receipts.
     */
    @Column(name = "mpesa_receipt_number", length = 50)
    private String mpesaReceiptNumber;

    /**
     * Safaricom's CheckoutRequestID — returned synchronously when we send the
     * STK push. Null for non-M-Pesa payments.
     *
     * Used for:
     *   1. Reconciliation if the callback never arrives (query /stkpushquery)
     *   2. Matching duplicate callbacks when AccountReference is unavailable
     *
     * NOTE: this field is NOT in the current payments DDL — add it with a
     * migration before deploying:
     *   ALTER TABLE payments ADD COLUMN checkout_request_id VARCHAR(100);
     */
    @Column(name = "checkout_request_id", length = 100)
    private String checkoutRequestId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "KES";

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private PaymentStatus status = PaymentStatus.PENDING;

    /** The phone number that initiated the payment (Safaricom format: 2547XXXXXXXX) */
    @Column(name = "phone_number", length = 15)
    private String phoneNumber;

    /** Timestamp when M-Pesa processed the transaction on their side */
    @Column(name = "transaction_date")
    private LocalDateTime transactionDate;

    /** When we received the M-Pesa callback — important for reconciliation */
    @Column(name = "callback_received_at")
    private LocalDateTime callbackReceivedAt;

    /** How many times we've retried a failed STK Push. Max 3 per CIC policy. */
    @Column(name = "retry_count")
    @Builder.Default
    private Integer retryCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ── Enums ────────────────────────────────────────────────────────────────

    public enum PaymentMethod {
        MPESA,
        AIRTEL_MONEY,
        PESALINK
    }

    public enum PaymentStatus {
        PENDING,    // STK push sent, waiting for customer to enter PIN
        COMPLETED,  // M-Pesa confirmed — trigger policy conversion
        FAILED,     // Customer cancelled, timeout, or insufficient funds
        REFUNDED    // Edge case — handled by finance team
    }
}