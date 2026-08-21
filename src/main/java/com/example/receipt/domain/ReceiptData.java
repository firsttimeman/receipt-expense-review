package com.example.receipt.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReceiptData(
        String merchant,
        LocalDate date,
        BigDecimal totalAmount,
        String businessRegistrationNumber,
        String paymentMethod,
        List<LineItem> lineItems
) {
    public ReceiptData {
        merchant = normalize(merchant);
        businessRegistrationNumber = normalize(businessRegistrationNumber);
        paymentMethod = normalize(paymentMethod);
        lineItems = lineItems == null ? List.of() : List.copyOf(lineItems);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
