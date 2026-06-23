package com.cic.motor_quote_service.controller;

import com.cic.motor_quote_service.dto.request.CreateQuoteRequest;
import com.cic.motor_quote_service.dto.response.QuoteResponse;
import com.cic.motor_quote_service.service.MotorQuoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Layer: Motor quote endpoints.
 * Zero business logic — all delegation to MotorQuoteService.
 *
 * Base path: /api/v1/motor-quotes
 */
@RestController
@RequestMapping("/api/v1/motor-quotes")
@RequiredArgsConstructor
@Slf4j
public class MotorQuoteController {

    private final MotorQuoteService quoteService;

    /** POST /api/v1/motor-quotes */
    @PostMapping
    public ResponseEntity<QuoteResponse> createQuote(
            @Valid @RequestBody CreateQuoteRequest request) {
        log.info("POST /api/v1/motor-quotes - vehicle: {}", request.getVehicleRegNumber());
        return ResponseEntity.status(HttpStatus.CREATED).body(quoteService.createQuote(request));
    }

    /** GET /api/v1/motor-quotes/{quoteNumber} */
    @GetMapping("/{quoteNumber}")
    public ResponseEntity<QuoteResponse> getQuote(@PathVariable String quoteNumber) {
        log.info("GET /api/v1/motor-quotes/{}", quoteNumber.trim());
        return ResponseEntity.ok(quoteService.getQuoteByNumber(quoteNumber));
    }

    /** GET /api/v1/motor-quotes/search?regNumber=KBA123A */
    @GetMapping("/search")
    public ResponseEntity<List<QuoteResponse>> searchByReg(@RequestParam String regNumber) {
        log.info("GET /api/v1/motor-quotes/search?regNumber=[{}]", regNumber);
        return ResponseEntity.ok(quoteService.searchByRegNumber(regNumber));
    }

    /**
     * PATCH /api/v1/motor-quotes/{quoteNumber}/activate
     * Moves a DRAFT quote to ACTIVE — called after underwriter approval.
     */
    @PatchMapping("/{quoteNumber}/activate")
    public ResponseEntity<QuoteResponse> activateQuote(@PathVariable String quoteNumber) {
        log.info("PATCH /api/v1/motor-quotes/{}/activate", quoteNumber);
        return ResponseEntity.ok(quoteService.activateQuote(quoteNumber));
    }
}
