package com.cic.motor_quote_service.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Immutable audit trail for all data changes.
 * Maps to PostgreSQL table: audit_logs
 *
 * CIC Compliance requirement: Every INSERT, UPDATE, DELETE on key tables
 * must produce an audit_logs record. This satisfies IRA (Insurance
 * Regulatory Authority of Kenya) record-keeping requirements.
 *
 * INTERN NOTE:
 *   - No @UpdateTimestamp here — audit rows are never updated.
 *   - @JdbcTypeCode(SqlTypes.JSON) maps Java Map → PostgreSQL JSONB.
 *   - We record IP address + correlation_id for traceability across services.
 *   - Never delete audit logs — that's a compliance violation.
 */
@Entity
@Table(name = "audit_logs",
        indexes = {
                @Index(name = "idx_audit_lookup", columnList = "table_name, record_id"),
                @Index(name = "idx_audit_time", columnList = "changed_at")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Which table was affected — e.g., "motor_quotes", "payments" */
    @Column(name = "table_name", nullable = false, length = 50)
    private String tableName;

    /** PK of the affected row in that table */
    @Column(name = "record_id", nullable = false)
    private Long recordId;

    /** INSERT | UPDATE | DELETE */
    @Column(name = "action", nullable = false, length = 10)
    private String action;

    /**
     * Previous state as JSONB — null on INSERT (nothing existed before).
     * Stored as a Map so we can query specific fields in PostgreSQL:
     *   SELECT * FROM audit_logs WHERE old_values->>'status' = 'DRAFT';
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_values", columnDefinition = "jsonb")
    private Map<String, Object> oldValues;

    /**
     * New state as JSONB — null on DELETE.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_values", columnDefinition = "jsonb")
    private Map<String, Object> newValues;

    /** Who made the change — username, system account, or "SYSTEM" for batch jobs */
    @Column(name = "changed_by", nullable = false, length = 100)
    private String changedBy;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    /** Client IP — captured from HttpServletRequest in the service layer */
    @Column(name = "ip_address", length = 45)    // 45 chars covers IPv6
    private String ipAddress;

    /**
     * Correlation ID — the same UUID attached to every log line in a single
     * HTTP request. Makes it easy to reconstruct what happened during one call.
     * Set this from MDC in your filter: MDC.put("correlationId", uuid)
     */
    @Column(name = "correlation_id", length = 50)
    private String correlationId;
}
