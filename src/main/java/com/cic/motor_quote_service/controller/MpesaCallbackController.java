package com.cic.motor_quote_service.controller;

import com.cic.motor_quote_service.service.MpesaCallbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Receives M-Pesa STK Push callbacks from Safaricom Daraja API.
 *
 * ENDPOINT: POST /payments/mpesa/callback
 * This is called by Safaricom's servers — it does NOT require JWT authentication.
 * In production, restrict this endpoint by Safaricom's IP range in your ingress config.
 *
 * SAFARICOM CALLBACK PAYLOAD (simplified):
 * {
 *   "Body": {
 *     "stkCallback": {
 *       "MerchantRequestID": "...",
 *       "CheckoutRequestID": "...",
 *       "ResultCode": 0,            // 0 = success, anything else = failure
 *       "ResultDesc": "Success.",
 *       "CallbackMetadata": {
 *         "Item": [
 *           { "Name": "Amount",              "Value": 1500.00 },
 *           { "Name": "MpesaReceiptNumber",  "Value": "QKJ12AB34C" },
 *           { "Name": "TransactionDate",     "Value": 20240601123456 },
 *           { "Name": "PhoneNumber",         "Value": 254712345678 }
 *         ]
 *       }
 *     }
 *   }
 * }
 *
 * NOTE: Safaricom expects a 200 OK within 20 seconds or they retry.
 * We return 200 immediately and process asynchronously (or synchronously
 * within the timeout — the distributed lock keeps it safe).
 *
 * INTERN: The `paymentReference` (our internal ID) is sent in the
 * AccountReference field of the initial STK push request. Safaricom
 * echoes it back in CheckoutRequestID or as a custom field depending
 * on the integration type. Map this correctly in your Daraja integration.
 */
@RestController
@RequestMapping("/payments/mpesa")
@RequiredArgsConstructor
@Slf4j
public class MpesaCallbackController {

    private final MpesaCallbackService mpesaCallbackService;

    /**
     * POST /payments/mpesa/callback
     *
     * Accepts the raw Daraja callback payload.
     * MUST return 200 OK within 20 seconds to prevent Safaricom retries.
     */
    @PostMapping("/callback")
    public ResponseEntity<Map<String, String>> handleCallback(
            @RequestBody Map<String, Object> payload) {

        // Generate a trace ID for this request (useful in Kibana/Grafana log search)
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);

        try {
            log.info("MPESA CALLBACK RECEIVED | traceId={} | payload keys={}", traceId, payload.keySet());

            // Extract the nested stkCallback object
            @SuppressWarnings("unchecked")
            Map<String, Object> body        = (Map<String, Object>) payload.get("Body");
            @SuppressWarnings("unchecked")
            Map<String, Object> stkCallback = (Map<String, Object>) body.get("stkCallback");

            int resultCode = ((Number) stkCallback.get("ResultCode")).intValue();
            boolean success = (resultCode == 0);

            // Extract our internal payment reference (sent in AccountReference at STK push time)
            // This mapping depends on how you built the initial STK push — adjust field name accordingly
            String paymentReference = (String) stkCallback.get("AccountReference");

            String mpesaReceiptNumber = "UNKNOWN";
            if (success) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) stkCallback.get("CallbackMetadata");
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> items =
                        (java.util.List<Map<String, Object>>) metadata.get("Item");

                for (Map<String, Object> item : items) {
                    if ("MpesaReceiptNumber".equals(item.get("Name"))) {
                        mpesaReceiptNumber = (String) item.get("Value");
                        break;
                    }
                }
            }

            // Hand off to service with distributed lock
            mpesaCallbackService.handleCallback(paymentReference, mpesaReceiptNumber, success, traceId);

            // Always return 200 OK — Safaricom doesn't retry on 200
            return ResponseEntity.ok(Map.of(
                    "ResultCode", "0",
                    "ResultDesc", "Accepted",
                    "TraceId", traceId
            ));

        } catch (Exception e) {
            // Log but still return 200 — we don't want Safaricom to keep retrying
            // Our retry/DLT mechanism handles internal failures
            log.error("MPESA CALLBACK ERROR | traceId={} | error={}", traceId, e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "ResultCode", "0",
                    "ResultDesc", "Received",
                    "TraceId", traceId
            ));
        } finally {
            MDC.clear();
        }
    }
}
