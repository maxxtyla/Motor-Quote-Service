package com.cic.motor_quote_service.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes motor quote events to Kafka topics.
 *
 * USAGE (in MotorQuoteService, after saving a quote):
 *   quoteEventProducer.publishQuoteCreated(event);
 *
 * KEY SELECTION:
 *   We use quoteNumber as the Kafka message key.
 *   - All messages for the same quote land on the same partition.
 *   - This guarantees ordering: "quote created" always arrives before
 *     downstream services process it. Critical for idempotency.
 *
 * FIRE-AND-FORGET vs AWAIT:
 *   We use the async callback pattern (CompletableFuture).
 *   We do NOT block the HTTP response thread waiting for Kafka ack.
 *   Kafka failures are logged — in production, add alerting/DLQ fallback.
 *
 * INTERN NOTE: KafkaTemplate.send() is non-blocking. The future resolves
 * when the broker acknowledges (acks=all from KafkaConfig). If the broker
 * is down, the producer buffers messages and retries automatically up to
 * the configured retry count. Beyond that, the future completes exceptionally.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QuoteEventProducer {

    private static final String TOPIC_QUOTE_CREATED = "motor.quote.created";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * Publishes a QuoteCreatedEvent.
     * Key = quoteNumber → all events for the same quote go to the same partition.
     */
    public void publishQuoteCreated(KafkaEvents.QuoteCreatedEvent event) {
        CompletableFuture<SendResult<String, Object>> future =
                kafkaTemplate.send(TOPIC_QUOTE_CREATED, event.getQuoteNumber(), event);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("KAFKA SEND FAILURE | topic={} | key={} | correlationId={} | error={}",
                        TOPIC_QUOTE_CREATED, event.getQuoteNumber(),
                        event.getCorrelationId(), ex.getMessage(), ex);
                // TODO: Add to outbox table for guaranteed delivery (Transactional Outbox Pattern)
            } else {
                log.info("KAFKA SEND SUCCESS | topic={} | key={} | partition={} | offset={} | correlationId={}",
                        TOPIC_QUOTE_CREATED,
                        event.getQuoteNumber(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        event.getCorrelationId());
            }
        });
    }
}
