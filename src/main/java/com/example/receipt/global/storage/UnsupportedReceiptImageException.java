package com.example.receipt.global.storage;

public class UnsupportedReceiptImageException extends RuntimeException {
    public UnsupportedReceiptImageException() {
        super("JPG 또는 PNG 이미지만 업로드할 수 있습니다.");
    }
}
