package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.entity.MotorQuote;
import com.cic.motor_quote_service.entity.Payment;
import com.cic.motor_quote_service.kafka.KafkaEvents;
import com.cic.motor_quote_service.kafka.QuoteEventProducer;
import com.cic.motor_quote_service.repository.MotorQuoteRepository;
import com.cic.motor_quote_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Processes M-Pesa STK push callbacks with exactly-once semantics.
 *
 * THE PROBLEM:
 *   Safaricom's Daraja API will send the same callback multiple times
 *   if your server doesn't respond within 20 seconds, or during network
 *   retries. In a Kubernetes deployment with 3+ pods, two pods could
 *   receive the same callback simultaneously.
 *
 *   Without locking: both pods read the PENDING payment, both mark it
 *   COMPLETED, both publish "payment.confirmed" → duplicate policy issuance.
 *
 * THE SOLUTION: Redisson Distributed Lock
 *   Lock key = mpesaReceiptNumber (unique per M-Pesa transaction).
 *   Only one pod can hold this lock at a time.
 *   tryLock with 10-second timeout: if we can't acquire within 10s,
 *   another pod is processing it — return 200 OK silently (duplicate).
 *
 * LOCK LIFECYCLE:
 *   1. tryLock(waitTime=10s, leaseTime=30s)
 *      - waitTime: how long to wait for the lock before giving up
 *      - leaseTime: auto-release after 30s even if we crash (prevents deadlock)
 *   2. Inside lock: check if already processed (idempotency check in DB)
 *   3. Process: update payment status → update quote status → publish Kafka event
 *   4. unlock() in finally block — ALWAYS release, even on exception
 *
 * INTERN: This is the #1 pattern for payment deduplication at CIC.
 * Study this carefully — you'll use it in every payment-related service.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MpesaCallbackService {

    private final PaymentRepository paymentRepository;
    private final MotorQuoteRepository quoteRepository;
    private final QuoteEventProducer quoteEventProducer;
    private final RedissonClient redissonClient;

    // Lock key prefix — namespaced to avoid collisions with other services
    private static final String LOCK_PREFIX = "cic:mpesa:lock:";

    // How long to wait for the lock (another pod might hold it)
    private static final long LOCK_WAIT_SECONDS = 10;

    // Auto-release lock after this time even if the pod crashes
    private static final long LOCK_LEASE_SECONDS = 30;

    /**
     * Handles an M-Pesa STK callback — exactly once.
     *
     * @param paymentReference    Our internal reference (sent in STK push metadata)
     * @param mpesaReceiptNumber  M-Pesa's unique receipt (e.g., "QKJ12AB34C")
     * @param success             Whether the customer completed payment (ResultCode = 0)
     * @param traceId             Distributed trace ID (from MDC/Sleuth)
     */
    public void handleCallback(
            String paymentReference,
            String mpesaReceiptNumber,
            boolean success,
            String traceId) {

        // Lock key is the M-Pesa receipt number — unique per transaction
        String lockKey = LOCK_PREFIX + mpesaReceiptNumber;
        RLock lock = redissonClient.getLock(lockKey);

        log.info("MPESA CALLBACK | ref={} | receipt={} | success={} | traceId={}",
                paymentReference, mpesaReceiptNumber, success, traceId);

        boolean lockAcquired = false;

        try {
            // Try to acquire lock — non-blocking after waitTime
            lockAcquired = lock.tryLock(LOCK_WAIT_SECONDS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);

            if (!lockAcquired) {
                // Another pod is currently processing this receipt
                log.warn("MPESA DUPLICATE — could not acquire lock | receipt={} | traceId={}. Returning silently.",
                        mpesaReceiptNumber, traceId);
                return;    // Return 200 OK silently — Safaricom doesn't retry on 200
            }

            // We hold the lock — now do the actual processing
            processCallback(paymentReference, mpesaReceiptNumber, success, traceId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("MPESA LOCK INTERRUPTED | receipt={} | traceId={}", mpesaReceiptNumber, traceId, e);
        } finally {
            // CRITICAL: ALWAYS release the lock, even on exception.
            // Without finally, a thrown exception would leave the lock held until leaseTime.
            if (lockAcquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("MPESA LOCK RELEASED | receipt={} | traceId={}", mpesaReceiptNumber, traceId);
            }
        }
    }

    @Transactional
    protected void processCallback(
            String paymentReference,
            String mpesaReceiptNumber,
            boolean success,
            String traceId) {

        // ── Idempotency check inside the lock ────────────────────────────────
        // Even with the distributed lock, we check DB state in case the lock
        // expired and a second pod completed processing (defensive programming).
        Payment payment = paymentRepository.findByPaymentReference(paymentReference)
                .orElseThrow(() -> new RuntimeException(
                        "Payment not found for reference: " + paymentReference));

        if (payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            log.warn("MPESA ALREADY PROCESSED | ref={} | receipt={} | traceId={}. Skipping.",
                    paymentReference, mpesaReceiptNumber, traceId);
            return;
        }

        try {
            if (success) {
                // ── 1. Update payment in PostgreSQL ───────────────────────────
                payment.setStatus(Payment.PaymentStatus.COMPLETED);
                payment.setMpesaReceiptNumber(mpesaReceiptNumber);
                payment.setTransactionDate(LocalDateTime.now());
                payment.setCallbackReceivedAt(LocalDateTime.now());
                paymentRepository.save(payment);

                // ── 2. Convert quote → policy ─────────────────────────────────
                MotorQuote quote = payment.getQuote();
                quote.setStatus(MotorQuote.QuoteStatus.CONVERTED);
                quoteRepository.save(quote);

                log.info("MPESA SUCCESS | quote={} | receipt={} | traceId={}",
                        quote.getQuoteNumber(), mpesaReceiptNumber, traceId);

                // ── 3. Publish Kafka event → downstream services ───────────────
                // The certificate service and SMS service listen to "payment.confirmed"
                KafkaEvents.QuoteCreatedEvent event = KafkaEvents.QuoteCreatedEvent.builder()
                        .quoteNumber(quote.getQuoteNumber())
                        .vehicleRegNumber(quote.getVehicleRegNumber())
                        .insuredName(quote.getInsuredName())
                        .phoneNumber(quote.getPhoneNumber())
                        .premium(quote.getPremium())
                        .sumInsured(quote.getSumInsured())
                        .status(quote.getStatus().name())
                        .createdAt(quote.getCreatedAt())
                        .correlationId(traceId != null ? traceId : UUID.randomUUID().toString())
                        .build();

                quoteEventProducer.publishQuoteCreated(event);

            } else {
                // ── Failed payment ─────────────────────────────────────────────
                payment.setStatus(Payment.PaymentStatus.FAILED);
                payment.setRetryCount(payment.getRetryCount() + 1);
                payment.setCallbackReceivedAt(LocalDateTime.now());
                paymentRepository.save(payment);

                log.warn("MPESA FAILED | ref={} | retry=#{} | traceId={}",
                        paymentReference, payment.getRetryCount(), traceId);
            }

        } catch (DataIntegrityViolationException e) {
            // Unique constraint on mpesa_receipt_number fired — true duplicate
            log.warn("MPESA DB DUPLICATE | receipt={} | traceId={}. Already processed.",
                    mpesaReceiptNumber, traceId);
        }
    }
}
