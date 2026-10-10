package com.example.receipt.domain.receipt.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ReceiptData(
        String shopName,
        LocalDate date,
        BigDecimal totalAmount,
        String businessRegistrationNumber,
        String paymentMethod,
        List<LineItem> lineItems
) {
    public ReceiptData {
        shopName = normalize(shopName);
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
