package com.example.receipt.validation;

import com.example.receipt.config.ReceiptProperties;
import com.example.receipt.domain.*;
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
        ReceiptProperties properties = new ReceiptProperties(
                new ReceiptProperties.Extractor("fake"),
                new ReceiptProperties.Quality(600, 600),
                new ReceiptProperties.Policy(new BigDecimal("300000"), true, List.of("카지노")),
                new ReceiptProperties.OpenAi("", "gpt-5.4-mini", "https://api.openai.com"));
        Clock clock = Clock.fixed(Instant.parse("2026-08-18T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        engine = new ValidationEngine(properties, new BusinessRegistrationNumberValidator(), clock);
        router = new ReceiptStatusRouter();
    }

    @Test
    void clearWeekdayReceiptIsAutoApproved() {
        ReceiptData data = new ReceiptData("테스트상점", LocalDate.of(2026, 1, 15),
                new BigDecimal("12000"), null, "카드", List.of());
        List<RuleResult> results = engine.validate(data, false);

        assertThat(results).noneMatch(RuleResult::failed);
        assertThat(router.route(data, results)).isEqualTo(ReceiptStatus.AUTO_APPROVED);
    }

    @Test
    void weekendOverLimitAndDuplicateNeedReview() {
        ReceiptData data = new ReceiptData("테스트상점", LocalDate.of(2026, 1, 17),
                new BigDecimal("500000"), null, "카드", List.of());
        List<RuleResult> results = engine.validate(data, true);

        assertThat(results).filteredOn(RuleResult::failed).extracting(RuleResult::code)
                .contains("POLICY_WEEKEND", "POLICY_AMOUNT_LIMIT", "DUPLICATE_SUBMISSION");
        assertThat(router.route(data, results)).isEqualTo(ReceiptStatus.NEEDS_REVIEW);
    }

    @Test
    void completelyMissingCoreDataRoutesToManualEntry() {
        ReceiptData data = new ReceiptData(null, null, null, null, null, List.of());
        assertThat(router.route(data, engine.validate(data, false))).isEqualTo(ReceiptStatus.MANUAL_ENTRY);
    }
}
