package com.cic.motor_quote_service.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka producer and consumer configuration for CIC Motor Quote Service.
 *
 * TOPICS OWNED BY THIS SERVICE:
 *   motor.quote.created   — published when a new quote is created
 *   payment.confirmed     — subscribed to (published by payment service / M-Pesa callback)
 *
 * PRODUCER SETTINGS:
 *   - acks=all: wait for all in-sync replicas before confirming (no data loss)
 *   - retries=3: retry transient network errors automatically
 *   - Key = M-Pesa reference or quoteNumber (ensures ordering per transaction)
 *
 * CONSUMER SETTINGS:
 *   - AckMode.MANUAL_IMMEDIATE: we commit offset ourselves after processing.
 *     This ensures at-least-once delivery — if processing fails, the message
 *     is redelivered. @RetryableTopic handles the retry/DLT from there.
 *   - groupId = "motor-service": all instances of this pod share work
 *     (Kafka distributes partitions across the group).
 *
 * INTERN NOTE: In Kubernetes, each pod replica is a consumer in the same
 * group. Kafka assigns different partitions to each pod, so messages are
 * processed in parallel without duplication — as long as you use the same
 * groupId across all instances (which we do via application.yaml).
 */
@Configuration
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:motor-service}")
    private String groupId;

    // ── Producer ──────────────────────────────────────────────────────────────

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Durability: require all replicas to confirm before ack
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // Retry transient broker errors (network blip, leader election)
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        // Batch small messages together for throughput (16KB default)
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 16_384);
        // Wait up to 1ms for batch to fill before sending
        props.put(ProducerConfig.LINGER_MS_CONFIG, 1);
        // Enable idempotent producer — exactly-once delivery per partition
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        KafkaTemplate<String, Object> template = new KafkaTemplate<>(producerFactory());
        // Log every send result — success or failure
        template.setObservationEnabled(true);
        return template;
    }

    // ── Consumer ──────────────────────────────────────────────────────────────

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);

        // Trust CIC classes for deserialization — required for JsonDeserializer
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.cic.motor_quote_service.*");
        // Don't use value type header — deserialize to Map and let @KafkaListener handle casting
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "java.util.Map");

        // Start from earliest offset for new consumer groups (dev/test safety)
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Disable auto-commit — we commit manually after successful processing
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());

        // MANUAL_IMMEDIATE: ack immediately when we call acknowledgment.acknowledge()
        // This means: only commit the offset after our processing succeeds
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // Process up to 3 messages in parallel per consumer (one per Kafka partition)
        factory.setConcurrency(3);

        return factory;
    }

    // ── Topic Definitions ─────────────────────────────────────────────────────
    // Spring will auto-create these topics if they don't exist in the broker.
    // In production, topics are pre-created by the platform team with
    // appropriate replication and retention settings.

    @Bean
    public NewTopic quoteCreatedTopic() {
        return TopicBuilder.name("motor.quote.created")
                .partitions(3)      // 3 partitions = 3 pods can process in parallel
                .replicas(1)        // Set to 3 in production for HA
                .build();
    }

    @Bean
    public NewTopic paymentConfirmedTopic() {
        return TopicBuilder.name("payment.confirmed")
                .partitions(3)
                .replicas(1)
                .build();
    }

    // Dead Letter Topics — created automatically by @RetryableTopic
    // but declared here for visibility and documentation
    @Bean
    public NewTopic paymentConfirmedDltTopic() {
        return TopicBuilder.name("payment.confirmed.DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
