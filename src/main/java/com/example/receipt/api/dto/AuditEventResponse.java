package com.example.receipt.api.dto;

import com.example.receipt.domain.*;
import com.example.receipt.entity.AuditEvent;

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
