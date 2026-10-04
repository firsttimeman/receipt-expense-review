package com.example.receipt.domain.receipt.dto;

import com.example.receipt.domain.extraction.model.ExtractionJobStatus;
import java.time.Instant;

public record ReceiptAcceptedResponse(
        Long receiptId,
        Long jobId,
        ExtractionJobStatus jobStatus,
        Instant acceptedAt
) {
    public static ReceiptAcceptedResponse from(UploadResult result) {
        return new ReceiptAcceptedResponse(result.receipt().id(), result.job().id(),
                result.job().status(), result.receipt().createdAt());
    }
}
