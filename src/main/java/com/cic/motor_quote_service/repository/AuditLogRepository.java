package com.cic.motor_quote_service.repository;

import com.cic.motor_quote_service.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** Retrieve the full change history for a single record */
    List<AuditLog> findByTableNameAndRecordIdOrderByChangedAtDesc(
            String tableName, Long recordId);

    List<AuditLog> findByCorrelationId(String correlationId);
}
