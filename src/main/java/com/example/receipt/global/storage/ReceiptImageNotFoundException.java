package com.example.receipt.global.storage;

public class ReceiptImageNotFoundException extends ReceiptImageStorageException {
    public ReceiptImageNotFoundException(Throwable cause) {
        super("원본 이미지를 찾을 수 없습니다.", cause);
    }
}
