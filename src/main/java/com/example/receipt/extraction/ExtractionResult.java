package com.example.receipt.extraction;

import com.example.receipt.domain.ReceiptData;

public record ExtractionResult(ReceiptData data, String provider, String model) {
}
