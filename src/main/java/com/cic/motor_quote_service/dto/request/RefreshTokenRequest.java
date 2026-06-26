package com.cic.motor_quote_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /auth/refresh request body. */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh token is required")
    private String refreshToken;
}
