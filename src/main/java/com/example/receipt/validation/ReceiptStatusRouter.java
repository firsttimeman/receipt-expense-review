package com.example.receipt.validation;

import com.example.receipt.domain.ReceiptData;
import com.example.receipt.domain.ReceiptStatus;
import com.example.receipt.domain.RuleResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReceiptStatusRouter {
    public ReceiptStatus route(ReceiptData data, List<RuleResult> results) {
        if (data == null || (data.merchant() == null && data.date() == null && data.totalAmount() == null)) {
            return ReceiptStatus.MANUAL_ENTRY;
        }
        return results.stream().anyMatch(RuleResult::failed)
                ? ReceiptStatus.NEEDS_REVIEW
                : ReceiptStatus.AUTO_APPROVED;
    }
}
