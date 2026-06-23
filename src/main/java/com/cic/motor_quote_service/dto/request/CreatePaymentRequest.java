package com.cic.motor_quote_service.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentRequest {

    @NotBlank(message = "Quote number is required")
    private String quoteNumber;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^2547\\d{8}$", message = "Phone must be in format 2547XXXXXXXX")
    private String phoneNumber;

    @NotBlank(message = "Payment method is required")
    @Pattern(regexp = "^(MPESA|AIRTEL_MONEY|PESALINK)$",
            message = "Payment method must be MPESA, AIRTEL_MONEY, or PESALINK")
    private String paymentMethod;
}
