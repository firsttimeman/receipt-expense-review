package com.example.receipt.service.model;

import com.example.receipt.domain.LineItem;
import com.example.receipt.domain.ReceiptData;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

public final class FieldCorrections {
    private final String merchant;
    private final LocalDate date;
    private final BigDecimal totalAmount;
    private final String businessRegistrationNumber;
    private final String paymentMethod;
    private final List<LineItem> lineItems;
    private final Set<String> clearFields;

    public FieldCorrections(
            String merchant,
            LocalDate date,
            BigDecimal totalAmount,
            String businessRegistrationNumber,
            String paymentMethod,
            List<LineItem> lineItems,
            Set<String> clearFields
    ) {
        this.merchant = merchant;
        this.date = date;
        this.totalAmount = totalAmount;
        this.businessRegistrationNumber = businessRegistrationNumber;
        this.paymentMethod = paymentMethod;
        this.lineItems = lineItems;

        if (clearFields == null) {
            this.clearFields = Set.of();
        } else {
            this.clearFields = Set.copyOf(clearFields);
        }
    }

    public ReceiptData applyTo(ReceiptData current) {
        ReceiptData base = current;

        // 기존 추출 데이터가 없다면 빈 영수증을 기준으로 수동 입력을 시작한다.
        if (base == null) {
            base = new ReceiptData(null, null, null, null, null, List.of());
        }

        // 먼저 현재 값을 그대로 복사한다.
        String updatedMerchant = base.merchant();
        LocalDate updatedDate = base.date();
        BigDecimal updatedTotalAmount = base.totalAmount();
        String updatedBusinessNumber = base.businessRegistrationNumber();
        String updatedPaymentMethod = base.paymentMethod();
        List<LineItem> updatedLineItems = base.lineItems();

        // 검수자가 새로운 값을 전달한 필드만 교체한다.
        if (merchant != null) {
            updatedMerchant = merchant;
        }
        if (date != null) {
            updatedDate = date;
        }
        if (totalAmount != null) {
            updatedTotalAmount = totalAmount;
        }
        if (businessRegistrationNumber != null) {
            updatedBusinessNumber = businessRegistrationNumber;
        }
        if (paymentMethod != null) {
            updatedPaymentMethod = paymentMethod;
        }
        if (lineItems != null) {
            updatedLineItems = lineItems;
        }

        // clearFields에 포함된 필드는 검수자가 명시적으로 삭제한 것으로 처리한다.
        if (clearFields.contains("merchant")) {
            updatedMerchant = null;
        }
        if (clearFields.contains("date")) {
            updatedDate = null;
        }
        if (clearFields.contains("totalAmount")) {
            updatedTotalAmount = null;
        }
        if (clearFields.contains("businessRegistrationNumber")) {
            updatedBusinessNumber = null;
        }
        if (clearFields.contains("paymentMethod")) {
            updatedPaymentMethod = null;
        }
        if (clearFields.contains("lineItems")) {
            updatedLineItems = List.of();
        }

        return new ReceiptData(
                updatedMerchant,
                updatedDate,
                updatedTotalAmount,
                updatedBusinessNumber,
                updatedPaymentMethod,
                updatedLineItems
        );
    }

    public Set<String> getClearFields() {
        return clearFields;
    }
}
