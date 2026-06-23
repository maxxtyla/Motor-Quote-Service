package com.cic.motor_quote_service.repository;

import com.cic.motor_quote_service.entity.Vehicle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VehicleRepository extends JpaRepository<Vehicle, Long> {

    Optional<Vehicle> findByRegistrationNumber(String registrationNumber);

    Optional<Vehicle> findByChassisNumber(String chassisNumber);

    boolean existsByRegistrationNumber(String registrationNumber);

    boolean existsByChassisNumber(String chassisNumber);
}
