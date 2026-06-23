package com.cic.motor_quote_service.exception;

public class QuoteNotFoundException extends RuntimeException {  // ✅ Extends Throwable chain
    public QuoteNotFoundException(String message) {
        super(message);
    }
}