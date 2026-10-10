package com.example.receipt.domain.extraction.extractor;

import com.example.receipt.domain.extraction.dto.ExtractionRequest;
import com.example.receipt.domain.extraction.dto.ExtractionResult;
import com.example.receipt.domain.extraction.exception.ExtractionException;
import com.example.receipt.domain.receipt.model.ReceiptData;
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

        String shopName = fileName.contains("missing-shop-name") ? null : "테스트상점";
        LocalDate date = LocalDate.of(2026, 1, 15);
        BigDecimal amount = new BigDecimal("12000");
        ReceiptData data = new ReceiptData(shopName, date, amount, null, "신용카드", List.of());
        return new ExtractionResult(data, "fake", "deterministic-v1");
    }
}
