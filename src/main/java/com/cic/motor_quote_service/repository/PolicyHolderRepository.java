package com.cic.motor_quote_service.repository;

import com.cic.motor_quote_service.entity.PolicyHolder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for PolicyHolder persistence.
 *
 * INTERN NOTE: Spring Data JPA derives SQL from method names automatically:
 *   findByIdNumber()  →  SELECT * FROM policyholders WHERE id_number = ?
 *   existsByEmail()   →  SELECT COUNT(*) > 0 FROM policyholders WHERE email = ?
 *
 * Use @Query for anything more complex (joins, aggregations, subqueries).
 */
@Repository
public interface PolicyHolderRepository extends JpaRepository<PolicyHolder, Long> {

    Optional<PolicyHolder> findByCustomerNumber(String customerNumber);

    Optional<PolicyHolder> findByIdNumber(String idNumber);

    Optional<PolicyHolder> findByEmail(String email);

    boolean existsByIdNumber(String idNumber);

    boolean existsByEmail(String email);

    /**
     * Find a policyholder by phone number.
     * Useful at payment time — customer calls in with phone, we look them up.
     */
    Optional<PolicyHolder> findByPhoneNumber(String phoneNumber);

    /**
     * Custom JPQL — fetch the policyholder and eagerly load their quotes
     * in one query using a JOIN FETCH. Use ONLY when you need both.
     * Don't call this from every screen — it can return huge result sets.
     */
    @Query("SELECT p FROM PolicyHolder p LEFT JOIN FETCH p.quotes WHERE p.id = :id")
    Optional<PolicyHolder> findByIdWithQuotes(@Param("id") Long id);
}
