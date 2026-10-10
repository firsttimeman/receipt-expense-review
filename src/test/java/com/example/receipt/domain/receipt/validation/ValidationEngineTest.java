package com.example.receipt.domain.receipt.validation;

import com.example.receipt.domain.receipt.model.ReceiptData;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import com.example.receipt.domain.receipt.model.RuleResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationEngineTest {
    private ValidationEngine engine;
    private ReceiptStatusRouter router;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        engine = new ValidationEngine(new BusinessRegistrationNumberValidator(), clock);
        router = new ReceiptStatusRouter();
    }

    @Test
    void validReceiptIsAutoApprovedWithoutExpensePolicyChecks() {
        ReceiptData data = new ReceiptData("카지노", LocalDate.of(2026, 1, 17),
                new BigDecimal("500000"), null, "카드", List.of());
        List<RuleResult> results = engine.validate(data, false);

        assertThat(results).noneMatch(RuleResult::failed);
        assertThat(router.route(data, results)).isEqualTo(ReceiptStatus.AUTO_APPROVED);
    }

    @Test
    void duplicateSubmissionNeedsReview() {
        ReceiptData data = new ReceiptData("테스트상점", LocalDate.of(2026, 1, 15),
                new BigDecimal("500000"), null, "카드", List.of());
        List<RuleResult> results = engine.validate(data, true);

        assertThat(results).filteredOn(RuleResult::failed).extracting(RuleResult::code)
                .containsExactly("DUPLICATE_SUBMISSION");
        assertThat(router.route(data, results)).isEqualTo(ReceiptStatus.NEEDS_REVIEW);
    }

    @Test
    void completelyMissingCoreDataRoutesToManualEntry() {
        ReceiptData data = new ReceiptData(null, null, null, null, null, List.of());
        assertThat(router.route(data, engine.validate(data, false))).isEqualTo(ReceiptStatus.MANUAL_ENTRY);
    }
}
