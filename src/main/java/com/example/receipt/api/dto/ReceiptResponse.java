package com.example.receipt.api.dto;

import com.example.receipt.domain.*;
import com.example.receipt.entity.Receipt;

import java.time.Instant;
import java.util.List;

public record ReceiptResponse(
        Long id,
        long version,
        String companyId,
        ReceiptStatus status,
        ExtractionJobStatus jobStatus,
        ReceiptData originalData,
        ReceiptData currentData,
        List<RuleResult> ruleResults,
        FileMetadata file,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReceiptResponse from(Receipt receipt, ExtractionJobStatus jobStatus) {
        return new ReceiptResponse(receipt.id(), receipt.version(), receipt.companyId(), receipt.status(), jobStatus,
                receipt.originalData(), receipt.currentData(), receipt.ruleResults(),
                new FileMetadata(receipt.originalFileName(), receipt.contentType(), receipt.fileSize(),
                        receipt.imageSha256()),
                receipt.createdAt(), receipt.updatedAt());
    }

    public record FileMetadata(String originalFileName, String contentType, long size, String sha256) {
    }
}
