package com.example.receipt.entity;

import com.example.receipt.domain.AuditAction;
import com.example.receipt.domain.ReceiptStatus;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * 영수증 변경 이력을 나타내는 도메인
 */


@Entity
@Table(name = "audit_events")
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false, length = 100)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 32)
    private ReceiptStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 32)
    private ReceiptStatus newStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private Map<String, Object> details = Map.of();

    protected AuditEvent() {
    }

    public AuditEvent(Long receiptId, Instant occurredAt, String actor, AuditAction action,
                      ReceiptStatus previousStatus, ReceiptStatus newStatus, Map<String, Object> details) {
        this.receiptId = receiptId;
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public Long id() { return id; }
    public Long receiptId() { return receiptId; }
    public Instant occurredAt() { return occurredAt; }
    public String actor() { return actor; }
    public AuditAction action() { return action; }
    public ReceiptStatus previousStatus() { return previousStatus; }
    public ReceiptStatus newStatus() { return newStatus; }
    public Map<String, Object> details() { return Map.copyOf(details); }

    public void attachTo(Long receiptId) {
        this.receiptId = receiptId;
    }
}
