package com.cic.motor_quote_service.kafka;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Events published to and consumed from Kafka.
 *
 * NAMING CONVENTION: <Entity><Action>Event
 * These are DTOs — they must be JSON-serializable (no JPA entities!).
 *
 * INTERN NOTE: Never put JPA @Entity classes on Kafka topics.
 * They have lazy-loaded collections, circular references, and hibernate proxies
 * that break JSON serialization. Always create a separate event DTO.
 */
public class KafkaEvents {

    /**
     * Published to "motor.quote.created" when a new quote is created.
     * Downstream: SMS service sends confirmation to customer.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QuoteCreatedEvent {
        private String quoteNumber;
        private String vehicleRegNumber;
        private String insuredName;
        private String phoneNumber;
        private BigDecimal premium;
        private BigDecimal sumInsured;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime expiresAt;
        private String correlationId;    // For distributed tracing
    }

    /**
     * Consumed from "payment.confirmed" — published by the payment/M-Pesa service.
     * On receipt: convert quote to policy, trigger certificate generation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentConfirmedEvent {
        private String quoteNumber;
        private String paymentReference;
        private String mpesaReceiptNumber;
        private BigDecimal amount;
        private String currency;
        private String phoneNumber;
        private LocalDateTime transactionDate;
        private String correlationId;
    }
}
