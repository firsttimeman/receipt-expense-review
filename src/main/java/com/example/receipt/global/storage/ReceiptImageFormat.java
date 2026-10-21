package com.example.receipt.global.storage;

/** 이미지의 실제 파일 헤더를 보고 저장 확장자와 Content-Type을 결정합니다. */
public enum ReceiptImageFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png");

    private final String extension;

    private final String contentType;

    ReceiptImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    public static ReceiptImageFormat detect(byte[] bytes) {
        if (matches(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (matches(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        throw new UnsupportedReceiptImageException();
    }

    private static boolean matches(byte[] bytes, int offset, int... signature) {
        if (bytes == null || bytes.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((bytes[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
