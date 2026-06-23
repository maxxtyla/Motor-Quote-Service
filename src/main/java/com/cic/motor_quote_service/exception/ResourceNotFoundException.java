package com.cic.motor_quote_service.exception;

/**
 * Thrown when a requested resource does not exist.
 * GlobalExceptionHandler maps this to HTTP 404.
 *
 * Replaces the phase-1 QuoteNotFoundException — more generic and
 * reusable across all domain objects (PolicyHolder, Vehicle, Payment).
 */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
