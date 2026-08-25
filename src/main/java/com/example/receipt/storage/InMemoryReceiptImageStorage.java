package com.example.receipt.storage;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile("test")
public class InMemoryReceiptImageStorage implements ReceiptImageStorage {
    private final Map<String, byte[]> images = new ConcurrentHashMap<>();

    @Override
    public String store(String companyId, String imageSha256, byte[] bytes) {
        String key = companyId + "/" + imageSha256;
        images.putIfAbsent(key, Arrays.copyOf(bytes, bytes.length));
        return key;
    }

    @Override
    public byte[] load(String storageKey) {
        byte[] bytes = images.get(storageKey);
        if (bytes == null) throw new ReceiptImageStorageException("저장된 테스트 이미지를 찾지 못했습니다.", null);
        return Arrays.copyOf(bytes, bytes.length);
    }
}
