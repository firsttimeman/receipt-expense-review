package com.example.receipt.domain.receipt.model;

import java.math.BigDecimal;

public record LineItem(String name, Integer quantity, BigDecimal unitPrice, BigDecimal amount) {
}
