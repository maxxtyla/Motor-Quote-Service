package com.cic.motor_quote_service.service;

import com.cic.motor_quote_service.dto.request.CreateVehicleRequest;
import com.cic.motor_quote_service.dto.response.VehicleResponse;
import com.cic.motor_quote_service.entity.Vehicle;
import com.cic.motor_quote_service.exception.DuplicateResourceException;
import com.cic.motor_quote_service.exception.ResourceNotFoundException;
import com.cic.motor_quote_service.repository.VehicleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Year;

@Service
@RequiredArgsConstructor
@Slf4j
public class VehicleService {

    private final VehicleRepository vehicleRepository;

    @Transactional
    public VehicleResponse registerVehicle(CreateVehicleRequest request) {
        // Normalize plate: strip spaces, uppercase — store consistently
        String normalizedReg = request.getRegistrationNumber()
                .toUpperCase().replace(" ", "").trim();

        log.info("Registering vehicle: {}", normalizedReg);

        if (vehicleRepository.existsByRegistrationNumber(normalizedReg)) {
            throw new DuplicateResourceException(
                    "Vehicle with registration " + normalizedReg + " already exists");
        }

        if (vehicleRepository.existsByChassisNumber(request.getChassisNumber())) {
            throw new DuplicateResourceException(
                    "Vehicle with chassis number " + request.getChassisNumber() + " already exists");
        }

        Vehicle vehicle = Vehicle.builder()
                .registrationNumber(normalizedReg)
                .chassisNumber(request.getChassisNumber().toUpperCase().trim())
                .engineNumber(request.getEngineNumber())
                .make(request.getMake().trim())
                .model(request.getModel().trim())
                .yearOfManufacture(request.getYearOfManufacture())
                .color(request.getColor())
                .bodyType(request.getBodyType())
                .engineCapacity(request.getEngineCapacity())
                .seatingCapacity(request.getSeatingCapacity())
                .fuelType(request.getFuelType())
                .tonnage(request.getTonnage())
                .build();

        Vehicle saved = vehicleRepository.save(vehicle);
        log.info("Vehicle registered: {} (ID: {})", saved.getRegistrationNumber(), saved.getId());

        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public VehicleResponse getByRegistrationNumber(String regNumber) {
        String normalized = regNumber.toUpperCase().replace(" ", "").strip();
        Vehicle vehicle = vehicleRepository.findByRegistrationNumber(normalized)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vehicle not found: " + regNumber));
        return mapToResponse(vehicle);
    }

    private VehicleResponse mapToResponse(Vehicle v) {
        return VehicleResponse.builder()
                .id(v.getId())
                .registrationNumber(v.getRegistrationNumber())
                .chassisNumber(v.getChassisNumber())
                .engineNumber(v.getEngineNumber())
                .make(v.getMake())
                .model(v.getModel())
                .yearOfManufacture(v.getYearOfManufacture())
                .ageInYears(Year.now().getValue() - v.getYearOfManufacture())
                .color(v.getColor())
                .bodyType(v.getBodyType())
                .engineCapacity(v.getEngineCapacity())
                .seatingCapacity(v.getSeatingCapacity())
                .fuelType(v.getFuelType())
                .tonnage(v.getTonnage())
                .createdAt(v.getCreatedAt())
                .build();
    }
}
