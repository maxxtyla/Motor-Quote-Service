package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.dto.request.CreateQuoteRequest;
import com.cic.motor_quote_service.dto.response.QuoteResponse;
import com.cic.motor_quote_service.entity.AppUser;
import com.cic.motor_quote_service.entity.MotorQuote;
import com.cic.motor_quote_service.exception.ResourceNotFoundException;
import com.cic.motor_quote_service.repository.AppUserRepository;
import com.cic.motor_quote_service.repository.MotorQuoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Handles motor insurance quote creation, retrieval, and lifecycle.
 *
 * PHASE 2 CHANGES:
 *   - Anonymous users can create and view quotes (no JWT required).
 *   - Logged-in users are automatically attached as the policyholder.
 *   - Payment (in PaymentService) requires authentication and enforces ownership.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MotorQuoteService {

    private final MotorQuoteRepository quoteRepository;
    private final AppUserRepository appUserRepository;

    /**
     * Creates a new motor quote.
     *
     * ANONYMOUS: policyholder_id = NULL. Quote is fully functional but unowned.
     * AUTHENTICATED: policyholder_id = current user's AppUser.id. Auto-attached by server.
     */
    @Transactional
    public QuoteResponse createQuote(CreateQuoteRequest request) {
        log.info("Creating quote for vehicle: {} {}", request.getVehicleMake(), request.getVehicleModel());

        AppUser policyholder = resolveCurrentPolicyholder();

        MotorQuote quote = MotorQuote.builder()
                .quoteNumber(generateQuoteNumber())
                .policyholder(policyholder)
                .vehicleRegNumber(request.getVehicleRegNumber().strip().toUpperCase())
                .vehicleMake(request.getVehicleMake().strip())
                .vehicleModel(request.getVehicleModel().strip())
                .vehicleYear(request.getVehicleYear())
                .sumInsured(request.getSumInsured())
                .premium(calculatePremium(request.getSumInsured()))
                .insuredName(request.getInsuredName().strip())
                .phoneNumber(request.getPhoneNumber().strip())
                .status(MotorQuote.QuoteStatus.DRAFT)
                .build();

        MotorQuote saved = quoteRepository.save(quote);

        log.info("Quote created: {} | policyholder={} | anonymous={}",
                saved.getQuoteNumber(),
                policyholder != null ? policyholder.getCustomerNumber() : "N/A",
                policyholder == null);

        return mapToResponse(saved);
    }

    /**
     * Retrieves a quote by its human-readable number.
     * Available to anonymous and authenticated users alike.
     */
    @Transactional(readOnly = true)
    public QuoteResponse getQuoteByNumber(String quoteNumber) {
        MotorQuote quote = quoteRepository.findByQuoteNumber(quoteNumber.strip().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteNumber));
        return mapToResponse(quote);
    }

    /**
     * Search quotes by vehicle registration number.
     * Available to anonymous and authenticated users alike.
     */
    @Transactional(readOnly = true)
    public List<QuoteResponse> searchByRegNumber(String regNumber) {
        List<MotorQuote> quotes = quoteRepository.findByVehicleRegNumber(regNumber.strip().toUpperCase());
        return quotes.stream().map(this::mapToResponse).toList();
    }

    /**
     * Admin or underwriter: activate a DRAFT quote to ACTIVE.
     * Requires authentication.
     */
    @Transactional
    public QuoteResponse activateQuote(String quoteNumber) {
        MotorQuote quote = quoteRepository.findByQuoteNumber(quoteNumber.strip().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteNumber));

        if (quote.getStatus() != MotorQuote.QuoteStatus.DRAFT) {
            throw new IllegalStateException(
                    "Quote " + quoteNumber + " cannot be activated (current status: " + quote.getStatus() + ")");
        }

        quote.setStatus(MotorQuote.QuoteStatus.ACTIVE);
        quote.setUpdatedAt(LocalDateTime.now());

        MotorQuote saved = quoteRepository.save(quote);
        log.info("Quote {} activated to ACTIVE", quoteNumber);

        return mapToResponse(saved);
    }

    /**
     * Admin-only: list all quotes.
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<QuoteResponse> getAllQuotes() {
        return quoteRepository.findAll().stream()
                .map(this::mapToResponse)
                .toList();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private AppUser resolveCurrentPolicyholder() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }

        String username = auth.getName();
        return appUserRepository.findByUsername(username).orElse(null);
    }

    private String generateQuoteNumber() {
        String year = String.valueOf(LocalDateTime.now().getYear());
        String seq = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "QT-" + year + "-" + seq;
    }

    private BigDecimal calculatePremium(BigDecimal sumInsured) {
        return sumInsured.multiply(new BigDecimal("0.035"))
                .add(new BigDecimal("2500"))
                .setScale(2, java.math.RoundingMode.HALF_UP);
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
                .expiresAt(q.getCreatedAt().plusDays(30))
                .build();
    }
}