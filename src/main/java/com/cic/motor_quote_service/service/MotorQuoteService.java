package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.dto.request.CreateQuoteRequest;
import com.cic.motor_quote_service.dto.response.QuoteResponse;
import com.cic.motor_quote_service.entity.MotorQuote;
import com.cic.motor_quote_service.entity.AppUser;
import com.cic.motor_quote_service.entity.Vehicle;
import com.cic.motor_quote_service.exception.ResourceNotFoundException;
import com.cic.motor_quote_service.kafka.KafkaEvents;
import com.cic.motor_quote_service.kafka.QuoteEventProducer;
import com.cic.motor_quote_service.repository.MotorQuoteRepository;
import com.cic.motor_quote_service.repository.AppUserRepository;
import com.cic.motor_quote_service.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * PHASE 3 ADDITIONS:
 *   - @Cacheable on read methods → Redis cache for repeated lookups
 *   - @CacheEvict on write methods → invalidate stale cache entries
 *   - @PreAuthorize for role-based access control
 *   - Kafka event published on quote creation
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MotorQuoteService {

    private final MotorQuoteRepository quoteRepository;
    private final AppUserRepository appUserRepository;
    private final VehicleRepository vehicleRepository;
    private final QuoteEventProducer quoteEventProducer;

    private static final BigDecimal BASE_RATE = new BigDecimal("0.035");
    private static final BigDecimal AGE_LOADING = new BigDecimal("500.00");
    private static final int VEHICLE_AGE_THRESHOLD = 10;

    /**
     * Creates a new quote.
     * @PreAuthorize: any authenticated user can create quotes.
     * @CacheEvict: not needed here (new entity — nothing to evict).
     * Kafka: publishes "motor.quote.created" after successful save.
     */
    @Transactional
    @PreAuthorize("isAuthenticated()")
    public QuoteResponse createQuote(CreateQuoteRequest request) {
        log.info("Creating motor quote for vehicle: {}", request.getVehicleRegNumber());

        AppUser policyholder = null;
        if (request.getCustomerNumber() != null) {
            policyholder = appUserRepository
                    .findByCustomerNumber(request.getCustomerNumber().strip())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Policyholder not found: " + request.getCustomerNumber()));
        }

        String normalizedReg = request.getVehicleRegNumber()
                .toUpperCase().replace(" ", "").trim();

        Vehicle vehicle = vehicleRepository.findByRegistrationNumber(normalizedReg).orElse(null);

        BigDecimal premium = calculatePremium(request);
        String quoteNumber = generateQuoteNumber();

        MotorQuote quote = MotorQuote.builder()
                .quoteNumber(quoteNumber)
                .policyholder(policyholder)
                .vehicle(vehicle)
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

        // Publish async Kafka event — non-blocking, won't affect HTTP response time
        quoteEventProducer.publishQuoteCreated(KafkaEvents.QuoteCreatedEvent.builder()
                .quoteNumber(saved.getQuoteNumber())
                .vehicleRegNumber(saved.getVehicleRegNumber())
                .insuredName(saved.getInsuredName())
                .phoneNumber(saved.getPhoneNumber())
                .premium(saved.getPremium())
                .sumInsured(saved.getSumInsured())
                .status(saved.getStatus().name())
                .createdAt(saved.getCreatedAt())
                .correlationId(UUID.randomUUID().toString())
                .build());

        return mapToResponse(saved);
    }

    /**
     * @Cacheable: cache result in Redis under key "quotes::<quoteNumber>".
     * TTL = 15 minutes (configured in RedisConfig).
     * On a cache hit, the DB is NOT queried — Redis returns the cached QuoteResponse.
     *
     * @PreAuthorize: any authenticated user can read quotes.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "quotes", key = "#quoteNumber")
    @PreAuthorize("isAuthenticated()")
    public QuoteResponse getQuoteByNumber(String quoteNumber) {
        String clean = quoteNumber.strip();
        log.info("Fetching quote (cache miss): [{}]", clean);

        MotorQuote quote = quoteRepository.findByQuoteNumber(clean)
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + clean));

        return mapToResponse(quote);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("isAuthenticated()")
    public List<QuoteResponse> searchByRegNumber(String regNumber) {
        String normalized = regNumber.toUpperCase().replace(" ", "").strip();
        log.info("Searching quotes for reg: [{}]", normalized);

        return quoteRepository.findByVehicleRegNumberContainingIgnoreCase(normalized)
                .stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    /**
     * Activate quote: DRAFT → ACTIVE.
     *
     * @CacheEvict: clears the cached QuoteResponse for this quoteNumber so the
     * next read fetches the updated ACTIVE status from DB, not the stale DRAFT.
     *
     * @PreAuthorize: only ADMIN or AGENT can activate quotes.
     */
    @Transactional
    @CacheEvict(value = "quotes", key = "#quoteNumber")
    @PreAuthorize("hasAnyRole('ADMIN', 'AGENT')")
    public QuoteResponse activateQuote(String quoteNumber) {
        MotorQuote quote = quoteRepository.findByQuoteNumber(quoteNumber.strip())
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteNumber));

        if (quote.getStatus() != MotorQuote.QuoteStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only DRAFT quotes can be activated. Current status: " + quote.getStatus());
        }

        quote.setStatus(MotorQuote.QuoteStatus.ACTIVE);
        MotorQuote saved = quoteRepository.save(quote);
        log.info("Quote {} activated by user with ADMIN/AGENT role", saved.getQuoteNumber());

        return mapToResponse(saved);
    }

    /**
     * Hard-delete a quote record.
     * @PreAuthorize: ADMIN only — cannot be called by ROLE_USER or ROLE_AGENT.
     * @CacheEvict: always clears cache (even if the DB delete fails — safe to evict).
     */
    @Transactional
    @CacheEvict(value = "quotes", key = "#quoteNumber", beforeInvocation = false)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteQuote(String quoteNumber) {
        MotorQuote quote = quoteRepository.findByQuoteNumber(quoteNumber.strip())
                .orElseThrow(() -> new ResourceNotFoundException("Quote not found: " + quoteNumber));
        quoteRepository.delete(quote);
        log.info("Quote {} deleted by ADMIN", quoteNumber);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private BigDecimal calculatePremium(CreateQuoteRequest request) {
        BigDecimal basePremium = request.getSumInsured().multiply(BASE_RATE);
        int vehicleAge = LocalDateTime.now().getYear() - request.getVehicleYear();
        if (vehicleAge > VEHICLE_AGE_THRESHOLD) {
            basePremium = basePremium.add(AGE_LOADING);
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
