package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;

/**
 * Vehicle details for a motor insurance quote.
 * Maps to PostgreSQL table: vehicles
 *
 * INTERN NOTE: chassis_number and registration_number are the two
 * unique identifiers used by Kenya's NTSA. Always validate reg numbers
 * against the Kenyan format (e.g. KBA 123A) before saving.
 */
@Entity
@Table(name = "vehicles",
        indexes = {
                @Index(name = "idx_vehicles_reg", columnList = "registration_number")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "registration_number", nullable = false, unique = true, length = 15)
    private String registrationNumber;   // Kenyan plate: "KBA123A" (stored without spaces)

    @Column(name = "chassis_number", nullable = false, unique = true, length = 30)
    private String chassisNumber;        // VIN / chassis — used for verification with IRA

    @Column(name = "engine_number", length = 30)
    private String engineNumber;

    @Column(name = "make", nullable = false, length = 50)
    private String make;                 // Toyota, Nissan, Isuzu etc.

    @Column(name = "model", nullable = false, length = 50)
    private String model;                // Corolla, Hardbody, etc.

    @Column(name = "year_of_manufacture", nullable = false)
    private Integer yearOfManufacture;

    @Column(name = "color", length = 30)
    private String color;

    @Column(name = "body_type", length = 30)
    private String bodyType;             // Saloon, SUV, Pick-up, etc.

    /** Engine capacity in CC — used in commercial vehicle rating */
    @Column(name = "engine_capacity")
    private Integer engineCapacity;

    @Column(name = "seating_capacity")
    private Integer seatingCapacity;

    @Column(name = "fuel_type", length = 20)
    private String fuelType;             // Petrol, Diesel, Electric, Hybrid

    /** Tonnage in metric tons — relevant for commercial vehicles */
    @Column(name = "tonnage", precision = 8, scale = 2)
    private BigDecimal tonnage;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── Derived / Transient ───────────────────────────────────────────────────

    /**
     * Vehicle age in years — computed at runtime, not stored.
     * Used by premium calculation logic in MotorQuoteService.
     */
    @Transient
    public int getAgeInYears() {
        return Year.now().getValue() - yearOfManufacture;
    }
}
