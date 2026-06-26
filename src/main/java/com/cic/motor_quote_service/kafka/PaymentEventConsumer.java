package com.cic.motor_quote_service.kafka;

import com.cic.motor_quote_service.entity.MotorQuote;
import com.cic.motor_quote_service.exception.ResourceNotFoundException;
import com.cic.motor_quote_service.repository.MotorQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.retry.annotation.Backoff;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Consumes "payment.confirmed" events from Kafka.
 *
 * WHAT WE DO ON EACH MESSAGE:
 *   1. Extract quoteNumber from the event payload
 *   2. Find the MotorQuote in PostgreSQL
 *   3. Transition status: ACTIVE → CONVERTED
 *   4. (Future) Trigger certificate generation
 *
 * RETRY STRATEGY (@RetryableTopic):
 *   - Attempt 1: immediate processing
 *   - Attempt 2: after 5 seconds (backoff)
 *   - Attempt 3: after 15 seconds (backoff * multiplier)
 *   - Attempt 4: after 30 seconds
 *   - After 4 failures: route to payment.confirmed.DLT for manual investigation
 *
 * WHY KAFKA RETRY vs @Retryable:
 *   @Retryable retries in the same thread (blocks the Kafka consumer!).
 *   @RetryableTopic publishes to a retry topic and releases the consumer
 *   thread immediately. The retry is consumed after the backoff delay.
 *   This means other messages in the partition keep flowing. Critical for
 *   high-throughput systems like CIC's payment pipeline.
 *
 * MANUAL ACK:
 *   We call acknowledgment.acknowledge() only after successful processing.
 *   If the method throws, the offset is NOT committed — Kafka will redeliver.
 *   This gives us at-least-once processing semantics.
 *
 * INTERN NOTE: "at-least-once" means the same message could be processed
 * twice in a crash scenario. Our Redis Redisson lock in MpesaCallbackService
 * provides the idempotency guard that makes this safe.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventConsumer {

    private final MotorQuoteRepository quoteRepository;

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 5_000, multiplier = 3.0, maxDelay = 30_000),
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE,
            dltTopicSuffix = ".DLT"
    )
    @KafkaListener(
            topics = "payment.confirmed",
            groupId = "motor-service",
            containerFactory = "kafkaListenerContainerFactory"
    )
    @Transactional
    public void onPaymentConfirmed(
            @Payload Map<String, Object> payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        String quoteNumber    = (String) payload.get("quoteNumber");
        String paymentRef     = (String) payload.get("paymentReference");
        String mpesaReceipt   = (String) payload.get("mpesaReceiptNumber");
        String correlationId  = (String) payload.getOrDefault("correlationId", "N/A");

        log.info("KAFKA CONSUME | topic={} | partition={} | offset={} | quoteNumber={} | correlationId={}",
                topic, partition, offset, quoteNumber, correlationId);

        // Guard: validate required fields
        if (quoteNumber == null || quoteNumber.isBlank()) {
            log.error("KAFKA BAD MESSAGE — missing quoteNumber | offset={} | payload={}", offset, payload);
            // ACK to discard malformed message — retrying won't fix bad data
            acknowledgment.acknowledge();
            return;
        }

        // Find the quote
        MotorQuote quote = quoteRepository.findByQuoteNumber(quoteNumber)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Quote not found for payment event: " + quoteNumber));

        // Idempotency: skip if already converted (duplicate event delivery)
        if (quote.getStatus() == MotorQuote.QuoteStatus.CONVERTED) {
            log.warn("KAFKA DUPLICATE EVENT — quote {} already CONVERTED | paymentRef={}",
                    quoteNumber, paymentRef);
            acknowledgment.acknowledge();
            return;
        }

        // Convert the quote
        quote.setStatus(MotorQuote.QuoteStatus.CONVERTED);
        quoteRepository.save(quote);

        log.info("QUOTE CONVERTED | quoteNumber={} | mpesaReceipt={} | correlationId={}",
                quoteNumber, mpesaReceipt, correlationId);

        // TODO Phase 4: Publish "policy.issued" event → trigger certificate generation
        // certificateProducer.publishPolicyIssued(...)

        // Commit the Kafka offset — processing succeeded
        acknowledgment.acknowledge();
    }

    /**
     * Dead Letter Topic handler.
     * Called when a message fails all 4 retry attempts.
     *
     * At this point: log it, alert the team, and store for manual replay.
     * In production: send to PagerDuty / Slack #alerts channel.
     */
    @DltHandler
    public void onDeadLetter(
            @Payload Map<String, Object> payload,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.error("DEAD LETTER | topic={} | offset={} | payload={} | ACTION=REQUIRES_MANUAL_INTERVENTION",
                topic, offset, payload);

        // TODO: Persist to audit_logs table for manual review
        // TODO: Send alert to Slack / PagerDuty

        // ACK the DLT message — we've recorded it, don't loop forever
        acknowledgment.acknowledge();
    }
}
