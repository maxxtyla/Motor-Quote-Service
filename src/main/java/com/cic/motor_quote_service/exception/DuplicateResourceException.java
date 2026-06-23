package com.cic.motor_quote_service.exception;

/**
 * Thrown when an attempt is made to create a duplicate resource
 * (e.g., a policyholder with the same ID number, or a vehicle
 * with the same registration number).
 *
 * GlobalExceptionHandler maps this to HTTP 409 Conflict.
 */
public class DuplicateResourceException extends RuntimeException {
    public DuplicateResourceException(String message) {
        super(message);
    }
}
