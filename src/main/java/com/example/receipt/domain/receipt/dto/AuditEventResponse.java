package com.example.receipt.domain.receipt.dto;

import com.example.receipt.domain.receipt.entity.AuditEvent;
import com.example.receipt.domain.receipt.model.AuditAction;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import java.time.Instant;
import java.util.Map;

public record AuditEventResponse(
        Long id,
        Instant occurredAt,
        String actor,
        AuditAction action,
        ReceiptStatus previousStatus,
        ReceiptStatus newStatus,
        Map<String, Object> details
) {
    public static AuditEventResponse from(AuditEvent event) {
        return new AuditEventResponse(event.id(), event.occurredAt(), event.actor(), event.action(),
                event.previousStatus(), event.newStatus(), event.details());
    }
}
