package com.cic.motor_quote_service.controller;

import com.cic.motor_quote_service.dto.request.CreateVehicleRequest;
import com.cic.motor_quote_service.dto.response.VehicleResponse;
import com.cic.motor_quote_service.service.VehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/vehicles")
@RequiredArgsConstructor
@Slf4j
public class VehicleController {

    private final VehicleService vehicleService;

    /** POST /api/v1/vehicles — Register a vehicle */
    @PostMapping
    public ResponseEntity<VehicleResponse> register(
            @Valid @RequestBody CreateVehicleRequest request) {
        log.info("POST /api/v1/vehicles - Registering: {}", request.getRegistrationNumber());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(vehicleService.registerVehicle(request));
    }

    /** GET /api/v1/vehicles/{regNumber} */
    @GetMapping("/{regNumber}")
    public ResponseEntity<VehicleResponse> getByReg(@PathVariable String regNumber) {
        log.info("GET /api/v1/vehicles/{}", regNumber);
        return ResponseEntity.ok(vehicleService.getByRegistrationNumber(regNumber));
    }
}
