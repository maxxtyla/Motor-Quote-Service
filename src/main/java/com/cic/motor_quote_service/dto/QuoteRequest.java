package com.cic.motor_quote_service.dto;

import jakarta.validation.constraints.*;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Builder;

@Data
@Builder
@NoArgsConstructor  // <--- 
@AllArgsConstructor // <--- Required if you use @Builder or @NoArgsConstructor together
public class QuoteRequest {

    @NotBlank(message = "Vehicle registration number is required")
    @Pattern(regexp = "^[A-Z]{3}\\s?\\d{3}[A-Z]$", message = "Invalid Kenyan plate format (e.g., KBA 123A)")
    private String vehicleRegNumber;

    @NotBlank(message = "Vehicle make is required")
    @Size(max = 50)
    private String vehicleMake;

    @NotBlank(message = "Vehicle model is required")
    @Size(max = 50)
    private String vehicleModel;

    @NotNull(message = "Vehicle year is required")
    @Min(value = 1990, message = "Vehicle year must be 1990 or later")
    @Max(value = 2026, message = "Vehicle year cannot be in the future")
    private Integer vehicleYear;

    @NotNull(message = "Sum insured is required")
    @DecimalMin(value = "50000.00", message = "Minimum sum insured is KES 50,000")
    @Digits(integer = 12, fraction = 2)
    private BigDecimal sumInsured;

    @NotBlank(message = "Insured name is required")
    @Size(max = 100)
    private String insuredName;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^2547\\d{8}$", message = "Phone must be in format 2547XXXXXXXX")
    private String phoneNumber;
}