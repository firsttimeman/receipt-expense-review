package com.example.receipt.domain.receipt.exception;

public class ReceiptNotFoundException extends RuntimeException {
    public ReceiptNotFoundException(Long id) {
        super("영수증을 찾을 수 없습니다: " + id);
    }
}
