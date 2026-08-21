package com.example.receipt.validation;

import com.example.receipt.config.ReceiptProperties;
import com.example.receipt.domain.LineItem;
import com.example.receipt.domain.ReceiptData;
import com.example.receipt.domain.RuleOutcome;
import com.example.receipt.domain.RuleResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ValidationEngine {
    private final ReceiptProperties properties;
    private final BusinessRegistrationNumberValidator businessNumberValidator;
    private final Clock clock;

    public List<RuleResult> validate(ReceiptData data, boolean duplicate) {
        List<RuleResult> results = new ArrayList<>();
        if (data == null) {
            data = new ReceiptData(null, null, null, null, null, List.of());
        }

        results.add(required("MERCHANT_REQUIRED", data.merchant(), "상호"));
        results.add(required("DATE_REQUIRED", data.date(), "거래일"));
        results.add(required("TOTAL_AMOUNT_REQUIRED", data.totalAmount(), "총액"));
        results.add(dateRule(data.date()));
        results.add(amountRule(data.totalAmount()));
        results.add(itemTotalRule(data));
        results.add(businessNumberRule(data.businessRegistrationNumber()));
        results.add(limitRule(data.totalAmount()));
        results.add(weekendRule(data.date()));
        results.add(prohibitedMerchantRule(data.merchant()));
        results.add(duplicate
                ? fail("DUPLICATE_SUBMISSION", "동일한 이미지가 이미 제출되었습니다.")
                : pass("DUPLICATE_SUBMISSION", "중복 제출이 아닙니다."));
        return List.copyOf(results);
    }

    private RuleResult required(String code, Object value, String label) {
        return value == null
                ? fail(code, label + " 필드가 없습니다.")
                : pass(code, label + " 필드가 존재합니다.");
    }

    private RuleResult dateRule(LocalDate date) {
        if (date == null) {
            return notApplicable("DATE_NOT_FUTURE", "거래일이 없어 검사하지 않았습니다.");
        }
        return date.isAfter(LocalDate.now(clock))
                ? fail("DATE_NOT_FUTURE", "거래일이 현재 날짜보다 미래입니다.")
                : pass("DATE_NOT_FUTURE", "거래일이 유효합니다.");
    }

    private RuleResult amountRule(BigDecimal amount) {
        if (amount == null) {
            return notApplicable("TOTAL_AMOUNT_POSITIVE", "총액이 없어 검사하지 않았습니다.");
        }
        return amount.signum() <= 0
                ? fail("TOTAL_AMOUNT_POSITIVE", "총액은 0보다 커야 합니다.")
                : pass("TOTAL_AMOUNT_POSITIVE", "총액이 유효합니다.");
    }

    private RuleResult itemTotalRule(ReceiptData data) {
        if (data.lineItems().isEmpty() || data.totalAmount() == null) {
            return notApplicable("ITEM_TOTAL_MATCH", "품목 또는 총액이 없어 검사하지 않았습니다.");
        }
        if (data.lineItems().stream().map(LineItem::amount).anyMatch(amount -> amount == null)) {
            return notApplicable("ITEM_TOTAL_MATCH", "금액을 읽지 못한 품목이 있어 검사하지 않았습니다.");
        }
        BigDecimal sum = data.lineItems().stream().map(LineItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.compareTo(data.totalAmount()) == 0
                ? pass("ITEM_TOTAL_MATCH", "품목 합계와 총액이 일치합니다.")
                : fail("ITEM_TOTAL_MATCH", "품목 합계와 총액이 일치하지 않습니다.");
    }

    private RuleResult businessNumberRule(String number) {
        if (number == null) {
            return notApplicable("BUSINESS_NUMBER_CHECKSUM", "사업자등록번호가 없어 검사하지 않았습니다.");
        }
        return businessNumberValidator.isValid(number)
                ? pass("BUSINESS_NUMBER_CHECKSUM", "사업자등록번호 체크섬이 유효합니다.")
                : fail("BUSINESS_NUMBER_CHECKSUM", "사업자등록번호 형식 또는 체크섬이 유효하지 않습니다.");
    }

    private RuleResult limitRule(BigDecimal amount) {
        if (amount == null) {
            return notApplicable("POLICY_AMOUNT_LIMIT", "총액이 없어 한도를 검사하지 않았습니다.");
        }
        return amount.compareTo(properties.policy().maxAmount()) > 0
                ? fail("POLICY_AMOUNT_LIMIT", "회사 경비 한도를 초과했습니다.")
                : pass("POLICY_AMOUNT_LIMIT", "회사 경비 한도 이내입니다.");
    }

    private RuleResult weekendRule(LocalDate date) {
        if (!properties.policy().weekendRequiresReview() || date == null) {
            return notApplicable("POLICY_WEEKEND", "주말 검사를 적용하지 않았습니다.");
        }
        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;
        return weekend
                ? fail("POLICY_WEEKEND", "주말 사용 건은 사람 검수가 필요합니다.")
                : pass("POLICY_WEEKEND", "평일 사용 건입니다.");
    }

    private RuleResult prohibitedMerchantRule(String merchant) {
        if (merchant == null) {
            return notApplicable("POLICY_PROHIBITED_MERCHANT", "상호가 없어 금지 업종을 검사하지 않았습니다.");
        }
        boolean prohibited = properties.policy().prohibitedMerchantKeywords().stream().anyMatch(merchant::contains);
        return prohibited
                ? fail("POLICY_PROHIBITED_MERCHANT", "금지 업종 키워드가 포함되어 있습니다.")
                : pass("POLICY_PROHIBITED_MERCHANT", "금지 업종 키워드가 없습니다.");
    }

    private RuleResult pass(String code, String message) {
        return new RuleResult(code, RuleOutcome.PASS, message);
    }

    private RuleResult fail(String code, String message) {
        return new RuleResult(code, RuleOutcome.FAIL, message);
    }

    private RuleResult notApplicable(String code, String message) {
        return new RuleResult(code, RuleOutcome.NOT_APPLICABLE, message);
    }
}
