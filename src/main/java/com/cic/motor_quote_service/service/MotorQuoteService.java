package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.dto.QuoteRequest;
import com.cic.motor_quote_service.dto.QuoteResponse;
import com.cic.motor_quote_service.entity.MotorQuote;
import com.cic.motor_quote_service.exception.QuoteNotFoundException;
import com.cic.motor_quote_service.repository.MotorQuoteRepository;
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
 * Business Layer: All business rules live here.
 * Orchestrates repository calls. Testable without HTTP.
 *
 * CIC Business Rule: Premium = 3.5% of sum insured +
 *   500 loading if vehicle > 10 years old
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MotorQuoteService {

    private final MotorQuoteRepository quoteRepository;

    // CIC business constant
    private static final BigDecimal BASE_RATE = new BigDecimal("0.035");  // 3.5%
    private static final BigDecimal AGE_LOADING = new BigDecimal("500.00");
    private static final int VEHICLE_AGE_THRESHOLD = 10;

    @Transactional
    public QuoteResponse createQuote(QuoteRequest request) {
        log.info("Creating motor quote for vehicle: {}", request.getVehicleRegNumber());

        BigDecimal premium = calculatePremium(request);
        String quoteNumber = generateQuoteNumber().trim();

        MotorQuote quote = MotorQuote.builder()
                .quoteNumber(quoteNumber)
                .vehicleRegNumber(request.getVehicleRegNumber().toUpperCase().replace(" ", "").trim())
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
        log.info("Quote created successfully: {}", saved.getQuoteNumber()); // ← log from saved entity, not variable

        return mapToResponse(saved);
    }
    @Transactional(readOnly = true)
    public QuoteResponse getQuoteByNumber(String quoteNumber) {
        String clean = quoteNumber.strip(); //
        log.info("Fetching quote: [{}]", clean); // use [] brackets in logs to reveal hidden whitespace

        MotorQuote quote = quoteRepository.findByQuoteNumber(clean)
                .orElseThrow(() -> new QuoteNotFoundException("Quote not found: " + clean));

        return mapToResponse(quote);
    }
    @Transactional(readOnly = true)
    public List<QuoteResponse> searchByRegNumber(String regNumber) {
        // Normalize the same way we store: strip spaces, uppercase
        String normalized = regNumber.toUpperCase().replace(" ", "").strip();

        log.info("Searching for reg number: [{}]", normalized);

        return quoteRepository.findByVehicleRegNumberContainingIgnoreCase(normalized)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    // Private business logic methods
    private BigDecimal calculatePremium(QuoteRequest request) {
        BigDecimal basePremium = request.getSumInsured().multiply(BASE_RATE);

        int vehicleAge = LocalDateTime.now().getYear() - request.getVehicleYear();
        if (vehicleAge > VEHICLE_AGE_THRESHOLD) {
            basePremium = basePremium.add(AGE_LOADING);
        }

        return basePremium.setScale(2, RoundingMode.HALF_UP);
    }

    private String generateQuoteNumber() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String uuid = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return "QTE-" + timestamp + "-" + uuid;
    }

    private QuoteResponse mapToResponse(MotorQuote quote) {
        return QuoteResponse.builder()
                .quoteNumber(quote.getQuoteNumber())
                .vehicleRegNumber(quote.getVehicleRegNumber())
                .vehicleMake(quote.getVehicleMake())
                .vehicleModel(quote.getVehicleModel())
                .vehicleYear(quote.getVehicleYear())
                .sumInsured(quote.getSumInsured())
                .premium(quote.getPremium())
                .insuredName(quote.getInsuredName())
                .phoneNumber(quote.getPhoneNumber())
                .status(quote.getStatus().name())
                .createdAt(quote.getCreatedAt())
                .expiresAt(quote.getCreatedAt().plusDays(30))
                .build();
    }
}