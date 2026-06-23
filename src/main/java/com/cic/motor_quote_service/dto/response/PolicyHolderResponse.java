package com.cic.motor_quote_service.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class PolicyHolderResponse {
    private Long id;
    private String customerNumber;
    private String firstName;
    private String lastName;
    private String fullName;
    private String idNumber;
    private String email;
    private String phoneNumber;
    private LocalDate dateOfBirth;
    private String address;
    private String city;
    private String kraPin;
    private LocalDateTime createdAt;
}
