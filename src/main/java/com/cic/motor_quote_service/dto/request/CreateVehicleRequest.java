package com.cic.motor_quote_service.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateVehicleRequest {

    @NotBlank(message = "Registration number is required")
    @Pattern(regexp = "^[A-Z]{3}\\s?\\d{3}[A-Z]$", message = "Invalid Kenyan plate format (e.g., KBA 123A)")
    private String registrationNumber;

    @NotBlank(message = "Chassis number is required")
    @Size(min = 5, max = 30)
    private String chassisNumber;

    @Size(max = 30)
    private String engineNumber;

    @NotBlank(message = "Vehicle make is required")
    @Size(max = 50)
    private String make;

    @NotBlank(message = "Vehicle model is required")
    @Size(max = 50)
    private String model;

    @NotNull(message = "Year of manufacture is required")
    @Min(value = 1990, message = "Year must be 1990 or later")
    @Max(value = 2026, message = "Year cannot be in the future")
    private Integer yearOfManufacture;

    @Size(max = 30)
    private String color;

    @Size(max = 30)
    private String bodyType;

    @Positive(message = "Engine capacity must be positive")
    private Integer engineCapacity;

    @Positive(message = "Seating capacity must be positive")
    private Integer seatingCapacity;

    @Size(max = 20)
    private String fuelType;

    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal tonnage;
}
