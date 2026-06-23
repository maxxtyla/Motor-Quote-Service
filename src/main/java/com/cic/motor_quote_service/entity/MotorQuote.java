package com.cic.motor_quote_service.entity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Motor insurance quote entity.
 * Maps to PostgreSQL table. No business logic here.
 */
@Entity
@Table(name = "motor_quotes")
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
    private String quoteNumber;  // e.g., "QTE-2026-000001"

    @Column(name = "vehicle_reg_number", nullable = false, length = 15)
    private String vehicleRegNumber;  // Kenyan plate: "KBA 123A"

    @Column(name = "vehicle_make", nullable = false, length = 50)
    private String vehicleMake;

    @Column(name = "vehicle_model", nullable = false, length = 50)
    private String vehicleModel;

    @Column(name = "vehicle_year", nullable = false)
    private Integer vehicleYear;

    @Column(name = "sum_insured", nullable = false, precision = 15, scale = 2)
    private BigDecimal sumInsured;  // Vehicle value

    @Column(name = "premium", nullable = false, precision = 15, scale = 2)
    private BigDecimal premium;  // Calculated premium

    @Column(name = "insured_name", nullable = false, length = 100)
    private String insuredName;

    @Column(name = "phone_number", nullable = false, length = 15)
    private String phoneNumber;  // e.g., "254712345678"

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private QuoteStatus status = QuoteStatus.DRAFT;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum QuoteStatus {
        DRAFT, ACTIVE, EXPIRED, CONVERTED
    }
}