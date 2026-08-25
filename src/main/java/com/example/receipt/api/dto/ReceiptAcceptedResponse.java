package com.example.receipt.api.dto;

import com.example.receipt.domain.ExtractionJobStatus;
import com.example.receipt.service.model.UploadResult;

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
