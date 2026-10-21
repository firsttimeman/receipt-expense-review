package com.example.receipt.domain.receipt.service;

import com.example.receipt.domain.receipt.dto.ReceiptImageResponse;
import com.example.receipt.global.storage.ReceiptImageFormat;
import com.example.receipt.global.storage.ReceiptImageStorage;
import com.example.receipt.global.storage.ReceiptImageStorageException;
import com.example.receipt.global.storage.UnsupportedReceiptImageException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReceiptImageService {
    private final ReceiptQueryService queryService;
    private final ReceiptImageStorage imageStorage;

    public ReceiptImageResponse getOriginalImage(Long receiptId) {
        // 기존 열람 권한을 확인하고 DB 조회를 끝낸 뒤 S3에서 이미지를 가져옵니다.
        // S3 응답을 기다리는 동안 DB 연결을 점유하지 않도록 이 메서드에는 트랜잭션을 걸지 않습니다.
        String storageKey = queryService.getExtractionJob(receiptId).imageStorageKey();
        byte[] bytes = imageStorage.load(storageKey);

        try {
            // 과거 .bin으로 저장된 JPG·PNG도 파일 내용으로 종류를 판단해 표시합니다.
            ReceiptImageFormat format = ReceiptImageFormat.detect(bytes);
            return new ReceiptImageResponse(bytes, format.contentType());
        } catch (UnsupportedReceiptImageException exception) {
            throw new ReceiptImageStorageException("저장된 원본 이미지 형식을 확인할 수 없습니다.", exception);
        }
    }
}
