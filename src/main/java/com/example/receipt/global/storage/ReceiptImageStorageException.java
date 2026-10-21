package com.example.receipt.global.storage;

public class ReceiptImageStorageException extends RuntimeException {
    private final boolean retryable;

    public ReceiptImageStorageException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public ReceiptImageStorageException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
