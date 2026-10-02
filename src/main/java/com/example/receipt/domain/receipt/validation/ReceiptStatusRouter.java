package com.example.receipt.domain.receipt.validation;

import com.example.receipt.domain.receipt.model.ReceiptData;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import com.example.receipt.domain.receipt.model.RuleResult;
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
