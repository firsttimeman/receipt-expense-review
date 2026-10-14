package com.example.receipt.global.storage;

/** 이미지의 실제 파일 헤더를 보고 저장 확장자와 Content-Type을 결정합니다. */
enum ReceiptImageFormat {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp"),
    UNKNOWN("bin", "application/octet-stream");

    private final String extension;

    private final String contentType;

    ReceiptImageFormat(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    String extension() {
        return extension;
    }

    String contentType() {
        return contentType;
    }

    static ReceiptImageFormat detect(byte[] bytes) {
        if (matches(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (matches(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (matches(bytes, 0, 'R', 'I', 'F', 'F') && matches(bytes, 8, 'W', 'E', 'B', 'P')) {
            return WEBP;
        }

        // 읽을 수 없는 파일도 접수한 뒤 Worker의 이미지 품질 검사에서 처리합니다.
        return UNKNOWN;
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
