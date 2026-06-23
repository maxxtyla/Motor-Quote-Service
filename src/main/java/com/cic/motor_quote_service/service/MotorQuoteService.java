package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.dto.request.CreateQuoteRequest;
import com.cic.motor_quote_service.dto.response.QuoteResponse;
import com.cic.motor_quote_service.entity.MotorQuote;
import com.cic.motor_quote_service.entity.PolicyHolder;
import com.cic.motor_quote_service.entity.Vehicle;
import com.cic.motor_quote_service.exception.ResourceNotFoundException;
import com.cic.motor_quote_service.repository.MotorQuoteRepository;
import com.cic.motor_quote_service.repository.PolicyHolderRepository;
import com.cic.motor_quote_service.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business Layer: Premium calculation and quote lifecycle.
 *
 * PHASE 2 CHANGES:
 *   - Links quotes to PolicyHolder and Vehicle entities (FKs).
 *   - Vehicle reg number is still denormalised onto the quote for
 *     historical accuracy (vehicle record could be updated later).
 *
 * CIC BUSINESS RULES:
 *   - Premium = 3.5% of sum insured
 *   - Age loading: +KES 500 if vehicle > 10 years
 *   - Quotes expire after 30 days (enforced at payment time)
 *
 * @Transactional REMINDER:
 *   - Write methods → @Transactional (default — read/write)
 *   - Read methods  → @Transactional(readOnly = true)  ← free perf boost
 *   - NEVER put @Transactional on the controller
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MotorQuoteService {

    private final MotorQuoteRepository quoteRepository;
    private final PolicyHolderRepository policyHolderRepository;
    private final VehicleRepository vehicleRepository;

    // CIC rating constants
    private static final BigDecimal BASE_RATE = new BigDecimal("0.035");
    private static final BigDecimal AGE_LOADING = new BigDecimal("500.00");
    private static final int VEHICLE_AGE_THRESHOLD = 10;

    @Transactional
    public QuoteResponse createQuote(CreateQuoteRequest request) {
        log.info("Creating motor quote for vehicle: {}", request.getVehicleRegNumber());

        // ── Optionally link to existing policyholder ──────────────────────────
        // customerNumber is optional on the request — an agent can quote
        // without a registered customer (walk-in scenario).
        PolicyHolder policyholder = null;
        if (request.getCustomerNumber() != null) {
            policyholder = policyHolderRepository
                    .findByCustomerNumber(request.getCustomerNumber().strip())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Policyholder not found: " + request.getCustomerNumber()));
        }

        // ── Optionally link to registered vehicle ─────────────────────────────
        String normalizedReg = request.getVehicleRegNumber()
                .toUpperCase().replace(" ", "").trim();

        Vehicle vehicle = vehicleRepository
                .findByRegistrationNumber(normalizedReg)
                .orElse(null);  // OK if vehicle isn't registered yet — we snapshot below

        BigDecimal premium = calculatePremium(request);
        String quoteNumber = generateQuoteNumber();

        MotorQuote quote = MotorQuote.builder()
                .quoteNumber(quoteNumber)
                .policyholder(policyholder)     // null-safe: FK is nullable
                .vehicle(vehicle)               // null-safe: FK is nullable
                // Snapshot vehicle details at quote time:
                .vehicleRegNumber(normalizedReg)
                .vehicleMake(request.getVehicleMake().trim())
                .vehicleModel(request.getVehicleModel().trim())
                .vehicleYear(request.getVehicleYear())
                .sumInsured(request.getSumInsured())
                .premium(premium)
                .insuredName(request.getInsuredName().trim())
                .phoneNumber(request.getPhoneNumber().trim())
                .status(MotorQuote.QuoteStatus.DRAFT)
                .build();

        MotorQuote saved = quoteRepository.save(quote);
        log.info("Quote created: {} | Premium: KES {}", saved.getQuoteNumber(), saved.getPremium());

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public QuoteResponse getQuoteByNumber(String quoteNumber) {
        String clean = quoteNumber.strip();
        log.info("Fetching quote: [{}]", clean);

        MotorQuote quote = quoteRepository.findByQuoteNumber(clean)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + clean));

        return mapToResponse(quote);
    }

    @Transactional(readOnly = true)
    public List<QuoteResponse> searchByRegNumber(String regNumber) {
        String normalized = regNumber.toUpperCase().replace(" ", "").strip();
        log.info("Searching quotes for reg: [{}]", normalized);

        return quoteRepository.findByVehicleRegNumberContainingIgnoreCase(normalized)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Activate a DRAFT quote — called after underwriter review.
     * Only DRAFT → ACTIVE is allowed here.
     */
    @Transactional
    public QuoteResponse activateQuote(String quoteNumber) {
        MotorQuote quote = quoteRepository.findByQuoteNumber(quoteNumber.strip())
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteNumber));

        if (quote.getStatus() != MotorQuote.QuoteStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only DRAFT quotes can be activated. Current status: " + quote.getStatus());
        }

        quote.setStatus(MotorQuote.QuoteStatus.ACTIVE);
        MotorQuote saved = quoteRepository.save(quote);
        log.info("Quote {} activated", saved.getQuoteNumber());

        return mapToResponse(saved);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private BigDecimal calculatePremium(CreateQuoteRequest request) {
        BigDecimal basePremium = request.getSumInsured().multiply(BASE_RATE);

        int vehicleAge = LocalDateTime.now().getYear() - request.getVehicleYear();
        if (vehicleAge > VEHICLE_AGE_THRESHOLD) {
            basePremium = basePremium.add(AGE_LOADING);
            log.debug("Age loading applied: vehicle is {} years old", vehicleAge);
        }

        return basePremium.setScale(2, RoundingMode.HALF_UP);
    }

    private String generateQuoteNumber() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uid = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "QTE-" + timestamp + "-" + uid;
    }

    private QuoteResponse mapToResponse(MotorQuote q) {
        return QuoteResponse.builder()
                .quoteNumber(q.getQuoteNumber())
                .vehicleRegNumber(q.getVehicleRegNumber())
                .vehicleMake(q.getVehicleMake())
                .vehicleModel(q.getVehicleModel())
                .vehicleYear(q.getVehicleYear())
                .sumInsured(q.getSumInsured())
                .premium(q.getPremium())
                .insuredName(q.getInsuredName())
                .phoneNumber(q.getPhoneNumber())
                .status(q.getStatus().name())
                .createdAt(q.getCreatedAt())
                .expiresAt(q.getCreatedAt() != null ? q.getCreatedAt().plusDays(30) : null)
                .build();
    }
}
