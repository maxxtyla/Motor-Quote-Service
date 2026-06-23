package com.cic.motor_quote_service.dto;

import com.cic.motor_quote_service.exception.QuoteNotFoundException;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class QuoteResponse {
    private String quoteNumber;
    private String vehicleRegNumber;
    private String vehicleMake;
    private String vehicleModel;
    private Integer vehicleYear;
    private BigDecimal sumInsured;
    private BigDecimal premium;
    private String insuredName;
    private String phoneNumber;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;  // Quote valid for 30 days
}
