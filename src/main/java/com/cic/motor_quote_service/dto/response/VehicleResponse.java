package com.cic.motor_quote_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class VehicleResponse {
    private Long id;
    private String registrationNumber;
    private String chassisNumber;
    private String engineNumber;
    private String make;
    private String model;
    private Integer yearOfManufacture;
    private Integer ageInYears;
    private String color;
    private String bodyType;
    private Integer engineCapacity;
    private Integer seatingCapacity;
    private String fuelType;
    private BigDecimal tonnage;
    private LocalDateTime createdAt;
}
