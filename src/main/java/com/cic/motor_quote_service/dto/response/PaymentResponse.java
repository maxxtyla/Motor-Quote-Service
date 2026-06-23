package com.cic.motor_quote_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PaymentResponse {
    private Long id;
    private String quoteNumber;
    private String paymentReference;
    private String mpesaReceiptNumber;      // Null until M-Pesa confirms
    private BigDecimal amount;
    private String currency;
    private String paymentMethod;
    private String status;
    private String phoneNumber;
    private LocalDateTime transactionDate;
    private LocalDateTime createdAt;

    /** Human-readable status message for the front-end */
    private String statusMessage;
}
