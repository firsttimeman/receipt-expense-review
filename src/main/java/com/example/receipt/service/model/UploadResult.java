package com.example.receipt.service.model;

import com.example.receipt.entity.Receipt;

public record UploadResult(Receipt receipt, boolean created, boolean idempotentReplay) {
}
