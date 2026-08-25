package com.example.receipt.service.model;

import com.example.receipt.entity.Receipt;
import com.example.receipt.entity.ReceiptExtractionJob;

public record UploadResult(Receipt receipt, ReceiptExtractionJob job,
                           boolean created, boolean idempotentReplay) {
}
