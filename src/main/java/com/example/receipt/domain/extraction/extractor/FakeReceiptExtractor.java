package com.example.receipt.domain.extraction.extractor;

import com.example.receipt.domain.extraction.dto.ExtractionRequest;
import com.example.receipt.domain.extraction.dto.ExtractionResult;
import com.example.receipt.domain.extraction.exception.ExtractionException;
import com.example.receipt.domain.receipt.model.ReceiptData;
import lombok.extern.slf4j.Slf4j;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;

@Slf4j
public class FakeReceiptExtractor implements ReceiptExtractor {
    private final Duration delay;

    public FakeReceiptExtractor() {
        this(Duration.ZERO);
    }

    public FakeReceiptExtractor(Duration delay) {
        if (delay == null || delay.isNegative()) {
            throw new IllegalArgumentException("Fake 추출 지연은 0 이상이어야 합니다.");
        }
        this.delay = delay;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        // 부하테스트에서는 DB 작업 수와 별도로 실제 추출 진입 횟수를 대조합니다.
        log.debug("Fake receipt extraction started. fileName={}", request.fileName());
        // 외부 응답을 기다리는 Worker를 부하테스트에서 재현합니다. 기본 지연은 0입니다.
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExtractionException("Fake 추출 대기가 중단되었습니다.", exception);
            }
        }
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
