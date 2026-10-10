package com.example.receipt.domain.receipt.dto;

import com.example.receipt.domain.extraction.model.ExtractionJobStatus;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.model.ReceiptStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ReceiptListItemResponse(
        Long id,
        long version,
        Long ownerEmployeeId,
        ReceiptStatus status,
        ExtractionJobStatus jobStatus,
        String shopName,
        LocalDate date,
        BigDecimal totalAmount,
        String originalFileName,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReceiptListItemResponse from(Receipt receipt, ExtractionJobStatus jobStatus) {
        var data = receipt.currentData();
        return new ReceiptListItemResponse(receipt.id(), receipt.version(), receipt.ownerEmployeeId(),
                receipt.status(), jobStatus, data == null ? null : data.shopName(),
                data == null ? null : data.date(), data == null ? null : data.totalAmount(),
                receipt.originalFileName(), receipt.createdAt(), receipt.updatedAt());
    }
}
