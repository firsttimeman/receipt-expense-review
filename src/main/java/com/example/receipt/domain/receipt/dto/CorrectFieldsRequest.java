package com.example.receipt.domain.receipt.dto;

import com.example.receipt.domain.receipt.model.LineItem;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public record CorrectFieldsRequest(
        @NotNull @PositiveOrZero Long version,
        String merchant,
        LocalDate date,
        BigDecimal totalAmount,
        String businessRegistrationNumber,
        String paymentMethod,
        List<LineItem> lineItems,
        Set<String> clearFields
) {
    public FieldCorrections toCorrections() {
        return new FieldCorrections(merchant, date, totalAmount, businessRegistrationNumber,
                paymentMethod, lineItems, clearFields);
    }
}
