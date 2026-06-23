package com.cic.motor_quote_service.dto.request;

import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePolicyHolderRequest {

    @NotBlank(message = "First name is required")
    @Size(max = 50)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 50)
    private String lastName;

    /**
     * Kenyan National ID — 7 or 8 digits.
     * Foreigners use passport number (alphanumeric, up to 20 chars).
     */
    @NotBlank(message = "ID number is required")
    @Size(max = 20)
    private String idNumber;

    @Email(message = "Invalid email format")
    @Size(max = 100)
    private String email;                   // Optional

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^2547\\d{8}$", message = "Phone must be in format 2547XXXXXXXX")
    private String phoneNumber;

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @Size(max = 200)
    private String address;

    @Size(max = 50)
    private String city;

    /** KRA PIN format: A followed by 9 digits and a letter, e.g. A123456789B */
    @Pattern(regexp = "^[A-Z]\\d{9}[A-Z]$", message = "Invalid KRA PIN format")
    private String kraPin;                  // Optional at registration
}
