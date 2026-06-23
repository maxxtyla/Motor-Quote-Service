package com.cic.motor_quote_service.controller;

import com.cic.motor_quote_service.dto.request.CreatePolicyHolderRequest;
import com.cic.motor_quote_service.dto.response.PolicyHolderResponse;
import com.cic.motor_quote_service.service.PolicyHolderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for policyholder registration.
 *
 * INTERN REMINDER:
 *   - No business logic here — delegate everything to PolicyHolderService.
 *   - @Valid triggers Bean Validation — invalid requests are rejected before
 *     they reach the service (caught by GlobalExceptionHandler).
 *   - No @Transactional here — belongs in the service layer ONLY.
 */
@RestController
@RequestMapping("/api/v1/policyholders")
@RequiredArgsConstructor
@Slf4j
public class PolicyHolderController {

    private final PolicyHolderService policyHolderService;

    /** POST /api/v1/policyholders — Register a new customer */
    @PostMapping
    public ResponseEntity<PolicyHolderResponse> register(
            @Valid @RequestBody CreatePolicyHolderRequest request) {
        log.info("POST /api/v1/policyholders - Registering: {}", request.getIdNumber());
        PolicyHolderResponse response = policyHolderService.createPolicyHolder(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** GET /api/v1/policyholders/{customerNumber} */
    @GetMapping("/{customerNumber}")
    public ResponseEntity<PolicyHolderResponse> getByCustomerNumber(
            @PathVariable String customerNumber) {
        log.info("GET /api/v1/policyholders/{}", customerNumber);
        return ResponseEntity.ok(policyHolderService.getByCustomerNumber(customerNumber));
    }

    /** GET /api/v1/policyholders/by-id/{idNumber} — Look up by Kenyan ID number */
    @GetMapping("/by-id/{idNumber}")
    public ResponseEntity<PolicyHolderResponse> getByIdNumber(
            @PathVariable String idNumber) {
        log.info("GET /api/v1/policyholders/by-id/{}", idNumber);
        return ResponseEntity.ok(policyHolderService.getByIdNumber(idNumber));
    }
}
