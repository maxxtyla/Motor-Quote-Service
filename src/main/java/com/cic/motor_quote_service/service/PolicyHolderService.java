package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.dto.request.CreatePolicyHolderRequest;
import com.cic.motor_quote_service.dto.response.PolicyHolderResponse;
import com.cic.motor_quote_service.entity.AuditLog;
import com.cic.motor_quote_service.entity.PolicyHolder;
import com.cic.motor_quote_service.exception.DuplicateResourceException;
import com.cic.motor_quote_service.exception.ResourceNotFoundException;
import com.cic.motor_quote_service.repository.AuditLogRepository;
import com.cic.motor_quote_service.repository.PolicyHolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Business logic for policyholder registration and retrieval.
 *
 * INTERN NOTES on patterns used here:
 *
 * 1. @Transactional on the service, NOT the controller.
 *    - createPolicyHolder() is read-write → Spring opens a transaction.
 *    - getBy*() methods use readOnly=true → Hibernate skips dirty checking,
 *      which gives 10-20% better performance on large reads.
 *
 * 2. We throw specific domain exceptions (DuplicateResourceException,
 *    ResourceNotFoundException). These are caught by GlobalExceptionHandler
 *    and mapped to HTTP 409 / 404 without leaking stack traces to clients.
 *
 * 3. Audit logging is manual here (one log entry per service call).
 *    Phase 3 will replace this with a JPA @EntityListener that fires
 *    automatically on @PreUpdate / @PrePersist.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PolicyHolderService {

    private final PolicyHolderRepository policyHolderRepository;
    private final AuditLogRepository auditLogRepository;

    @Transactional
    public PolicyHolderResponse createPolicyHolder(CreatePolicyHolderRequest request) {
        log.info("Registering new policyholder with ID number: {}", request.getIdNumber());

        // ── Business rule: no duplicates on Kenyan ID number ──────────────────
        if (policyHolderRepository.existsByIdNumber(request.getIdNumber())) {
            throw new DuplicateResourceException(
                    "A policyholder with ID number " + request.getIdNumber() + " already exists");
        }

        if (request.getEmail() != null && policyHolderRepository.existsByEmail(request.getEmail())) {
            throw new DuplicateResourceException(
                    "A policyholder with email " + request.getEmail() + " already exists");
        }

        PolicyHolder holder = PolicyHolder.builder()
                .customerNumber(generateCustomerNumber())
                .firstName(request.getFirstName().trim())
                .lastName(request.getLastName().trim())
                .idNumber(request.getIdNumber().trim())
                .email(request.getEmail() != null ? request.getEmail().toLowerCase().trim() : null)
                .phoneNumber(request.getPhoneNumber().trim())
                .dateOfBirth(request.getDateOfBirth())
                .address(request.getAddress())
                .city(request.getCity())
                .kraPin(request.getKraPin())
                .build();

        PolicyHolder saved = policyHolderRepository.save(holder);
        log.info("Policyholder created: {} ({})", saved.getCustomerNumber(), saved.getIdNumber());

        // Write audit log — who/what/when for IRA compliance
        writeAudit("policyholders", saved.getId(), "INSERT", null,
                Map.of("customerNumber", saved.getCustomerNumber(),
                        "idNumber", saved.getIdNumber(),
                        "name", saved.getFullName()));

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public PolicyHolderResponse getByCustomerNumber(String customerNumber) {
        PolicyHolder holder = policyHolderRepository
                .findByCustomerNumber(customerNumber.strip())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Policyholder not found: " + customerNumber));
        return mapToResponse(holder);
    }

    @Transactional(readOnly = true)
    public PolicyHolderResponse getByIdNumber(String idNumber) {
        PolicyHolder holder = policyHolderRepository
                .findByIdNumber(idNumber.strip())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Policyholder not found with ID: " + idNumber));
        return mapToResponse(holder);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String generateCustomerNumber() {
        String year = String.valueOf(LocalDateTime.now().getYear());
        // In production: replace with a DB sequence for guaranteed uniqueness.
        // For demo: timestamp millis last 5 digits.
        String seq = String.format("%05d", System.currentTimeMillis() % 100000);
        return "CIC-" + year + "-" + seq;
    }

    private void writeAudit(String table, Long recordId, String action,
                            Map<String, Object> oldValues, Map<String, Object> newValues) {
        AuditLog log = AuditLog.builder()
                .tableName(table)
                .recordId(recordId)
                .action(action)
                .oldValues(oldValues)
                .newValues(newValues)
                .changedBy("SYSTEM")        // Phase 3: replace with JWT subject
                .build();
        auditLogRepository.save(log);
    }

    private PolicyHolderResponse mapToResponse(PolicyHolder h) {
        return PolicyHolderResponse.builder()
                .id(h.getId())
                .customerNumber(h.getCustomerNumber())
                .firstName(h.getFirstName())
                .lastName(h.getLastName())
                .fullName(h.getFullName())
                .idNumber(h.getIdNumber())
                .email(h.getEmail())
                .phoneNumber(h.getPhoneNumber())
                .dateOfBirth(h.getDateOfBirth())
                .address(h.getAddress())
                .city(h.getCity())
                .kraPin(h.getKraPin())
                .createdAt(h.getCreatedAt())
                .build();
    }
}
