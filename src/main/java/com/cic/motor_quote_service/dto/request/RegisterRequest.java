package com.cic.motor_quote_service.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

/**
 * Registration request for the CIC customer portal.
 *
 * MERGE NOTE: AppUser and PolicyHolder are now one table/entity — signing up
 * for a login also registers you as a policyholder, so this request carries
 * both the auth fields (username/password/email) and the KYC/customer fields
 * (idNumber, dateOfBirth, address, city, kraPin) that used to live on a
 * separate CreatePolicyHolderRequest.
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$", message = "Username can only contain letters, numbers, dots, hyphens and underscores")
    private String username;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    @Pattern(
            regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]+$",
            message = "Password must contain uppercase, lowercase, number and special character"
    )
    private String password;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Full name is required")
    @Size(max = 100)
    private String fullName;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^2547\\d{8}$", message = "Phone must be in format 2547XXXXXXXX")
    private String phoneNumber;

    // ── Policyholder / KYC fields ────────────────────────────────────────────

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

    @NotNull(message = "Date of birth is required")
    @Past(message = "Date of birth must be in the past")
    private LocalDate dateOfBirth;

    @Size(max = 200)
    private String address;                 // Optional

    @Size(max = 50)
    private String city;                    // Optional

    /** KRA PIN format: A followed by 9 digits and a letter, e.g. A123456789B */
    @Pattern(regexp = "^[A-Z]\\d{9}[A-Z]$", message = "Invalid KRA PIN format")
    private String kraPin;                  // Optional at registration
}