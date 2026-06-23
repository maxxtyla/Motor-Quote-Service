package com.cic.motor_quote_service.repository;

import com.cic.motor_quote_service.entity.MotorQuote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Data Layer: Talks to PostgreSQL only.
 * Spring Data JPA generates SQL from method names.
 * No business logic allowed here.
 */
@Repository
public interface MotorQuoteRepository extends JpaRepository<MotorQuote, Long> {

    // Spring generates: SELECT * FROM motor_quotes WHERE quote_number = ?
    Optional<MotorQuote> findByQuoteNumber(String quoteNumber);

    // Spring generates with LIKE for search
    List<MotorQuote> findByVehicleRegNumberContainingIgnoreCase(String regNumber);

    // Custom JPQL for complex queries
    @Query("SELECT q FROM MotorQuote q WHERE q.status = :status AND q.premium > :minPremium")
    List<MotorQuote> findByStatusAndMinPremium(
            @Param("status") MotorQuote.QuoteStatus status,
            @Param("minPremium") BigDecimal
                    minPremium
    );

    // Check if quote exists
    boolean existsByQuoteNumber(String quoteNumber);
}