package com.cic.motor_quote_service.repository;

import com.cic.motor_quote_service.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Repository for CIC system users.
 * Used by UserDetailsService and AuthService for login, and by
 * MotorQuoteService / PaymentService for policyholder lookups — AppUser
 * now covers both responsibilities (merged from the old PolicyHolder table).
 */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    // ── Policyholder-style lookups (merged from PolicyHolderRepository) ───────

    Optional<AppUser> findByCustomerNumber(String customerNumber);

    Optional<AppUser> findByIdNumber(String idNumber);

    Optional<AppUser> findByPhoneNumber(String phoneNumber);

    boolean existsByIdNumber(String idNumber);

    /**
     * Custom JPQL — fetch the user and eagerly load their quotes
     * in one query using a JOIN FETCH. Use ONLY when you need both.
     * Don't call this from every screen — it can return huge result sets.
     */
    @Query("SELECT u FROM AppUser u LEFT JOIN FETCH u.quotes WHERE u.id = :id")
    Optional<AppUser> findByIdWithQuotes(@Param("id") Long id);

    /**
     * Increment failed login attempts atomically.
     * Called on each failed login; lock account at threshold in AuthService.
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.failedAttempts = u.failedAttempts + 1 WHERE u.username = :username")
    void incrementFailedAttempts(@Param("username") String username);

    /**
     * Reset failed attempts on successful login.
     */
    @Modifying
    @Query("UPDATE AppUser u SET u.failedAttempts = 0, u.lockedUntil = null WHERE u.username = :username")
    void resetFailedAttempts(@Param("username") String username);
}
