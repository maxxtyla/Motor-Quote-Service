package com.cic.motor_quote_service.controller;

import com.cic.motor_quote_service.dto.request.CreatePaymentRequest;
import com.cic.motor_quote_service.dto.response.PaymentResponse;
import com.cic.motor_quote_service.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * API Layer: Payment endpoints for CIC Motor Quote Service.
 *
 * ENDPOINTS:
 *   POST /api/v1/payments              — Initiate payment (triggers M-Pesa STK push)
 *   GET  /api/v1/payments/{reference}  — Check payment status by reference
 *
 * PAYMENT FLOW:
 *   1. Client POSTs a quote number + phone number + payment method
 *   2. We create a PENDING payment record and simulate the STK push
 *   3. M-Pesa calls back to POST /payments/mpesa/callback (no auth required)
 *   4. On success: payment → COMPLETED, quote → CONVERTED
 *
 * SECURITY:
 *   - Both endpoints require a valid JWT access token
 *   - The M-Pesa callback endpoint (/payments/mpesa/callback) is in
 *     MpesaCallbackController and is intentionally public (Safaricom calls it)
 *
 * INTERN NOTE:
 *   Zero business logic here — all delegation to PaymentService.
 *   @Valid triggers Jakarta Bean Validation on the request body before
 *   this method is even called. If validation fails, Spring returns 400
 *   automatically via GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * POST /api/v1/payments
     *
     * Initiates an M-Pesa STK push for a motor insurance quote.
     *
     * Request body:
     * {
     *   "quoteNumber": "QTE-20260626-ABC123",
     *   "phoneNumber": "254712345678",
     *   "paymentMethod": "MPESA"
     * }
     *
     * Rules enforced by PaymentService:
     *   - Quote must exist and be in ACTIVE status
     *   - No completed payment already exists for this quote
     *
     * Returns 201 Created with the PENDING payment record.
     * The client should then poll GET /api/v1/payments/{reference} for status.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> initiatePayment(
            @Valid @RequestBody CreatePaymentRequest request) {

        log.info("POST /api/v1/payments — quoteNumber={} method={}",
                request.getQuoteNumber(), request.getPaymentMethod());

        PaymentResponse response = paymentService.initiatePayment(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/payments/{reference}
     *
     * Checks payment status by internal payment reference (e.g., PAY-A1B2C3D4E5F6).
     *
     * The client polls this endpoint after initiating payment to see when
     * M-Pesa confirms. Status transitions:
     *   PENDING  → customer hasn't completed the STK push yet
     *   COMPLETED → M-Pesa confirmed payment; quote is now CONVERTED
     *   FAILED   → customer cancelled or STK push timed out
     *
     * Returns 200 OK with the payment record (and statusMessage for the UI).
     */
    @GetMapping("/{reference}")
    public ResponseEntity<PaymentResponse> getPaymentStatus(
            @PathVariable String reference) {

        log.info("GET /api/v1/payments/{}", reference);
        return ResponseEntity.ok(paymentService.getPaymentByReference(reference));
    }
}