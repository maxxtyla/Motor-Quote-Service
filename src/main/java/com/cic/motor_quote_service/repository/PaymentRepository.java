package com.cic.motor_quote_service.repository;

import com.cic.motor_quote_service.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByPaymentReference(String paymentReference);

    Optional<Payment> findByMpesaReceiptNumber(String mpesaReceiptNumber);

    /** All payments for a quote — useful for the payment history screen */
    List<Payment> findByQuoteIdOrderByCreatedAtDesc(Long quoteId);

    /** Find pending payments — used by scheduled job that checks for timed-out STK pushes */
    List<Payment> findByStatus(Payment.PaymentStatus status);

    /**
     * Check if a completed payment exists for a quote.
     * Used before attempting a duplicate payment.
     */
    @Query("SELECT COUNT(p) > 0 FROM Payment p WHERE p.quote.id = :quoteId AND p.status = 'COMPLETED'")
    boolean hasCompletedPayment(@Param("quoteId") Long quoteId);
}
