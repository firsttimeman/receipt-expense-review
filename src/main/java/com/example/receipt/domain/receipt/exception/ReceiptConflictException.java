package com.example.receipt.domain.receipt.exception;

public class ReceiptConflictException extends RuntimeException {
    public ReceiptConflictException(String message) {
        super(message);
    }
}
