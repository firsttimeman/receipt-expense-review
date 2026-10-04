package com.example.receipt.global.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 저장소를 전환해도 DB에 저장한 이미지 키는 동일하게 유지합니다. */
final class ReceiptImageKey {
    private ReceiptImageKey() {
    }

    static String create(String companyId, String imageSha256) {
        try {
            String companyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(companyId.getBytes(StandardCharsets.UTF_8)));
            return companyHash.substring(0, 16) + "/" + imageSha256 + ".bin";
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }
}
