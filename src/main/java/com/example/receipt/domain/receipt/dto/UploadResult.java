package com.example.receipt.domain.receipt.dto;

import com.example.receipt.domain.extraction.entity.ReceiptExtractionJob;
import com.example.receipt.domain.receipt.entity.Receipt;
public record UploadResult(Receipt receipt, ReceiptExtractionJob job,
                           boolean created, boolean idempotentReplay) {
}
