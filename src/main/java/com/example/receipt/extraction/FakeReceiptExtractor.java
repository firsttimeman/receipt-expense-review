package com.example.receipt.extraction;

import com.example.receipt.domain.ReceiptData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class FakeReceiptExtractor implements ReceiptExtractor {
    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        String fileName = request.fileName() == null ? "" : request.fileName().toLowerCase();
        if (fileName.contains("extract-fail")) {
            throw new ExtractionException("Fake 추출 실패 시나리오");
        }
        if (fileName.contains("manual")) {
            return new ExtractionResult(new ReceiptData(null, null, null, null, null, List.of()),
                    "fake", "deterministic-v1");
        }

        String merchant = fileName.contains("missing-merchant") ? null : "테스트상점";
        LocalDate date = fileName.contains("weekend") ? LocalDate.of(2026, 1, 17) : LocalDate.of(2026, 1, 15);
        BigDecimal amount = fileName.contains("over-limit") ? new BigDecimal("500000") : new BigDecimal("12000");
        ReceiptData data = new ReceiptData(merchant, date, amount, null, "신용카드", List.of());
        return new ExtractionResult(data, "fake", "deterministic-v1");
    }
}
