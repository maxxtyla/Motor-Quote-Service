package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.dto.request.CreatePaymentRequest;
import com.cic.motor_quote_service.dto.response.PaymentResponse;
import com.cic.motor_quote_service.entity.MotorQuote;
import com.cic.motor_quote_service.entity.Payment;
import com.cic.motor_quote_service.exception.DuplicateResourceException;
import com.cic.motor_quote_service.exception.ResourceNotFoundException;
import com.cic.motor_quote_service.repository.MotorQuoteRepository;
import com.cic.motor_quote_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles payment initiation and M-Pesa callback processing.
 *
 * PAYMENT FLOW:
 *   1. POST /payments          → initiatePayment()  → creates PENDING record, "sends" STK push
 *   2. M-Pesa callback POST    → handleMpesaCallback() → marks COMPLETED or FAILED
 *   3. On COMPLETED            → quote status flips to CONVERTED
 *
 * INTERN NOTES:
 *   - In a real system, step 1 would call Safaricom's Daraja API via WebClient.
 *     For this demo, we simulate it by just creating the PENDING record.
 *   - DataIntegrityViolationException on the payment_reference unique constraint
 *     means M-Pesa sent a duplicate callback — we handle it gracefully.
 *   - @Transactional(readOnly = false) is the default; written explicitly here
 *     for clarity since this class mixes read and write transactions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MotorQuoteRepository quoteRepository;

    @Transactional
    public PaymentResponse initiatePayment(CreatePaymentRequest request) {
        String quoteNumber = request.getQuoteNumber().strip();
        log.info("Initiating {} payment for quote: {}", request.getPaymentMethod(), quoteNumber);

        MotorQuote quote = quoteRepository.findByQuoteNumber(quoteNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteNumber));

        // Business rule: can only pay for an ACTIVE quote
        if (quote.getStatus() != MotorQuote.QuoteStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Quote " + quoteNumber + " is not ACTIVE (current status: " + quote.getStatus() + ")");
        }

        // Prevent double payment
        if (paymentRepository.hasCompletedPayment(quote.getId())) {
            throw new DuplicateResourceException(
                    "A completed payment already exists for quote: " + quoteNumber);
        }

        Payment payment = Payment.builder()
                .quote(quote)
                .paymentReference(generatePaymentReference())
                .amount(quote.getPremium())
                .currency("KES")
                .paymentMethod(Payment.PaymentMethod.valueOf(request.getPaymentMethod()))
                .status(Payment.PaymentStatus.PENDING)
                .phoneNumber(request.getPhoneNumber())
                .build();

        Payment saved = paymentRepository.save(payment);
        log.info("Payment initiated: {} (PENDING)", saved.getPaymentReference());

        // TODO Phase 3: Call Daraja API here via WebClient to trigger STK push
        // webClient.post().uri(darajaUrl).bodyValue(stkPushRequest).retrieve()...

        return mapToResponse(saved, quoteNumber);
    }

    /**
     * Handles the M-Pesa callback — called by POST /payments/mpesa/callback.
     * M-Pesa may send this multiple times; the unique constraint on
     * mpesa_receipt_number guards against saving duplicates.
     *
     * @param paymentReference  Our internal reference (sent in STK push metadata)
     * @param mpesaReceiptNumber  The receipt from M-Pesa (e.g., "QKJ12AB34C")
     * @param success  Whether the customer completed the payment
     */
    @Transactional
    public void handleMpesaCallback(String paymentReference, String mpesaReceiptNumber,
                                    boolean success) {
        log.info("M-Pesa callback received for ref: {} — success={}", paymentReference, success);

        Payment payment = paymentRepository.findByPaymentReference(paymentReference)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment not found for reference: " + paymentReference));

        // Guard: don't process an already-completed payment
        if (payment.getStatus() == Payment.PaymentStatus.COMPLETED) {
            log.warn("Duplicate M-Pesa callback for {}. Ignoring.", paymentReference);
            return;
        }

        try {
            if (success) {
                payment.setStatus(Payment.PaymentStatus.COMPLETED);
                payment.setMpesaReceiptNumber(mpesaReceiptNumber);

                // Convert the quote → policy trigger
                MotorQuote quote = payment.getQuote();
                quote.setStatus(MotorQuote.QuoteStatus.CONVERTED);
                quoteRepository.save(quote);

                log.info("Quote {} CONVERTED after successful payment {}",
                        quote.getQuoteNumber(), mpesaReceiptNumber);
            } else {
                payment.setStatus(Payment.PaymentStatus.FAILED);
                payment.setRetryCount(payment.getRetryCount() + 1);
                log.warn("Payment FAILED for ref: {} (retry #{})",
                        paymentReference, payment.getRetryCount());
            }

            paymentRepository.save(payment);

        } catch (DataIntegrityViolationException e) {
            // M-Pesa sent the same receipt number twice — safe to ignore
            log.warn("Duplicate M-Pesa receipt number: {}. Callback already processed.", mpesaReceiptNumber);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByReference(String reference) {
        Payment payment = paymentRepository.findByPaymentReference(reference.strip())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment not found: " + reference));
        return mapToResponse(payment, payment.getQuote().getQuoteNumber());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String generatePaymentReference() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private PaymentResponse mapToResponse(Payment p, String quoteNumber) {
        String statusMessage = switch (p.getStatus()) {
            case PENDING   -> "Waiting for customer to complete payment";
            case COMPLETED -> "Payment confirmed — M-Pesa receipt: " + p.getMpesaReceiptNumber();
            case FAILED    -> "Payment failed. Please retry.";
            case REFUNDED  -> "Payment has been refunded";
        };

        return PaymentResponse.builder()
                .id(p.getId())
                .quoteNumber(quoteNumber)
                .paymentReference(p.getPaymentReference())
                .mpesaReceiptNumber(p.getMpesaReceiptNumber())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .paymentMethod(p.getPaymentMethod().name())
                .status(p.getStatus().name())
                .phoneNumber(p.getPhoneNumber())
                .transactionDate(p.getTransactionDate())
                .createdAt(p.getCreatedAt())
                .statusMessage(statusMessage)
                .build();
    }
}
