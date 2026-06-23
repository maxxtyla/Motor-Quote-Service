package com.cic.motor_quote_service.controller;

import com.cic.motor_quote_service.dto.request.CreateQuoteRequest;
import com.cic.motor_quote_service.service.MotorQuoteService;
import com.cic.motor_quote_service.dto.response.QuoteResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * API Layer: Receives HTTP requests, validates input, returns responses.
 * Contains ZERO business logic. Delegates everything to Service.
 *
 * Base path: /api/v1/motor-quotes
 */
@RestController
@RequestMapping("/api/v1/motor-quotes")
@RequiredArgsConstructor
@Slf4j
public class MotorQuoteController {

    private final MotorQuoteService quoteService;

    /**
     * POST /api/v1/motor-quotes
     * Create a new motor insurance quote
     */
    @PostMapping
    public ResponseEntity<QuoteResponse> createQuote(
            @Valid @RequestBody CreateQuoteRequest request) {
        log.info("POST /api/v1/motor-quotes - Creating quote for: {}", request.getVehicleRegNumber());

        QuoteResponse response = quoteService.createQuote(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /api/v1/motor-quotes/{quoteNumber}
     * Retrieve a quote by its unique number
     */
    @GetMapping("/{quoteNumber}")
    public ResponseEntity<QuoteResponse> getQuote(
            @PathVariable String quoteNumber) {

        String cleanQuoteNumber = quoteNumber.trim();
        log.info("GET /api/v1/motor-quotes/{}", cleanQuoteNumber);

        QuoteResponse response = quoteService.getQuoteByNumber(cleanQuoteNumber);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/v1/motor-quotes/search?regNumber=KBA
     * Search quotes by vehicle registration number
     */
    @GetMapping("/search")
    public ResponseEntity<List<QuoteResponse>> searchByReg(
            @RequestParam String regNumber) {

        String cleanReg = regNumber.strip(); // handles URL-encoded spaces too
        log.info("GET /api/v1/motor-quotes/search?regNumber=[{}]", cleanReg);

        List<QuoteResponse> results = quoteService.searchByRegNumber(cleanReg);
        return ResponseEntity.ok(results);
    }
}