package com.example.receipt.domain.extraction.dto;

import com.example.receipt.domain.receipt.model.ReceiptData;
public record ExtractionResult(ReceiptData data, String provider, String model) {
}
