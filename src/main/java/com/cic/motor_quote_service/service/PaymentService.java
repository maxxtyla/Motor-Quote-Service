package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.dto.request.CreatePaymentRequest;
import com.cic.motor_quote_service.dto.response.PaymentResponse;
import com.cic.motor_quote_service.entity.MotorQuote;
import com.cic.motor_quote_service.entity.Payment;
import com.cic.motor_quote_service.exception.DuplicateResourceException;
import com.cic.motor_quote_service.exception.ResourceNotFoundException;
import com.cic.motor_quote_service.repository.MotorQuoteRepository;
import com.cic.motor_quote_service.repository.PaymentRepository;
import com.cic.motor_quote_service.service.DarajaService.DarajaException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Handles payment initiation and status queries.
 *
 * PAYMENT FLOW:
 *   1. POST /api/v1/payments    → initiatePayment()
 *        ├─ validates quote is ACTIVE
 *        ├─ creates PENDING payment row
 *        └─ calls DarajaService.initiateSTKPush() → Safaricom sends PIN prompt
 *
 *   2. Customer enters PIN on phone
 *
 *   3. POST /payments/mpesa/callback  → MpesaCallbackController
 *        └─ MpesaCallbackService.handleCallback()
 *              ├─ acquires Redisson distributed lock
 *              ├─ marks payment COMPLETED
 *              └─ flips quote to CONVERTED
 *
 * STK PUSH FAILURE HANDLING:
 *   If DarajaService throws DarajaException (Safaricom rejected the request),
 *   we mark the payment FAILED immediately — no callback will ever arrive.
 *   The client can retry via POST /api/v1/payments with the same quoteNumber;
 *   we allow a new PENDING payment because the old one is already FAILED.
 *
 * INTERN — the @Transactional boundary matters here:
 *   The payment row is saved inside the transaction BEFORE calling Daraja.
 *   If Daraja fails we catch DarajaException, mark it FAILED, and still
 *   commit — so there's always an audit trail even for rejected pushes.
 *   We do NOT roll back on Daraja failure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final MotorQuoteRepository quoteRepository;
    private final DarajaService darajaService;

    @Transactional
    public PaymentResponse initiatePayment(CreatePaymentRequest request) {
        String quoteNumber = request.getQuoteNumber().strip();
        log.info("PAYMENT_INIT | method={} | quote={}", request.getPaymentMethod(), quoteNumber);

        // ── 1. Validate quote ─────────────────────────────────────────────────
        MotorQuote quote = quoteRepository.findByQuoteNumber(quoteNumber)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteNumber));

        if (quote.getStatus() != MotorQuote.QuoteStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Quote " + quoteNumber + " must be ACTIVE before payment. "
                            + "Current status: " + quote.getStatus());
        }

        // ── 2. Guard against double payment ───────────────────────────────────
        // Allow a new attempt if the only existing payment is FAILED (customer
        // cancelled or had insufficient funds). Block if already COMPLETED.
        if (paymentRepository.hasCompletedPayment(quote.getId())) {
            throw new DuplicateResourceException(
                    "A completed payment already exists for quote: " + quoteNumber);
        }

        // ── 3. Create PENDING payment row ─────────────────────────────────────
        Payment payment = Payment.builder()
                .quote(quote)
                .paymentReference(generatePaymentReference())
                .amount(quote.getPremium())
                .currency("KES")
                .paymentMethod(Payment.PaymentMethod.valueOf(request.getPaymentMethod()))
                .status(Payment.PaymentStatus.PENDING)
                .phoneNumber(request.getPhoneNumber().strip())
                .build();

        Payment saved = paymentRepository.save(payment);
        log.info("PAYMENT_SAVED | ref={} | amount={} | status=PENDING",
                saved.getPaymentReference(), saved.getAmount());

        // ── 4. Fire STK push (M-Pesa only) ───────────────────────────────────
        // Non-M-Pesa methods (AIRTEL_MONEY, PESALINK) don't go through Daraja.
        // They are handled out-of-band by the finance team; the payment stays PENDING
        // until a manual update or a separate integration marks it COMPLETED.
        if (saved.getPaymentMethod() == Payment.PaymentMethod.MPESA) {
            fireStkPush(saved);
        } else {
            log.info("PAYMENT_NON_MPESA | ref={} | method={} — STK push skipped",
                    saved.getPaymentReference(), saved.getPaymentMethod());
        }

        return mapToResponse(saved, quoteNumber);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentByReference(String reference) {
        Payment payment = paymentRepository.findByPaymentReference(reference.strip())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Payment not found: " + reference));
        return mapToResponse(payment, payment.getQuote().getQuoteNumber());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Calls DarajaService to send the STK push, then stores the CheckoutRequestID.
     *
     * If Daraja rejects the request (network error, invalid credentials, wrong
     * phone format, etc.), we catch DarajaException, mark the payment FAILED,
     * and save — so the client gets a meaningful error response and the audit
     * trail is intact. No payment row is left as a zombie PENDING.
     */
    private void fireStkPush(Payment payment) {
        try {
            String checkoutRequestId = darajaService.initiateSTKPush(
                    payment.getPaymentReference(),
                    payment.getAmount(),
                    payment.getPhoneNumber()
            );

            // Store Safaricom's CheckoutRequestID for reconciliation
            payment.setCheckoutRequestId(checkoutRequestId);
            paymentRepository.save(payment);

            log.info("STK_PUSH_SENT | ref={} | checkoutRequestId={}",
                    payment.getPaymentReference(), checkoutRequestId);

        } catch (DarajaException e) {
            // Daraja rejected our request — mark payment FAILED immediately.
            // The client will get status=FAILED in the response and can retry.
            log.error("STK_PUSH_REJECTED | ref={} | reason={}",
                    payment.getPaymentReference(), e.getMessage());

            payment.setStatus(Payment.PaymentStatus.FAILED);
            payment.setRetryCount(payment.getRetryCount() + 1);
            paymentRepository.save(payment);

            // Re-throw so PaymentController returns a 502 with a useful message
            throw new DarajaException(
                    "M-Pesa STK push failed — please check the phone number and try again. "
                            + "Reason: " + e.getMessage(), e);
        }
    }

    private String generatePaymentReference() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private PaymentResponse mapToResponse(Payment p, String quoteNumber) {
        String statusMessage = switch (p.getStatus()) {
            case PENDING   -> "STK push sent — waiting for customer to enter M-Pesa PIN";
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