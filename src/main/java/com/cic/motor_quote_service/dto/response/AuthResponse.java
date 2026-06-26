package com.cic.motor_quote_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Returned by POST /auth/login and POST /auth/refresh. */
@Data
@Builder
public class AuthResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;           // Always "Bearer"
    private LocalDateTime expiresAt;    // Access token expiry
    private String username;
    private String role;
    private String fullName;
}
