package com.cic.motor_quote_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /auth/login request body. */
@Data
public class LoginRequest {

    @NotBlank(message = "Username is required")
    private String username;

    @NotBlank(message = "Password is required")
    private String password;
}
