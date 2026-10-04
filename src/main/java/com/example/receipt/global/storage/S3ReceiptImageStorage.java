package com.example.receipt.global.storage;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3ReceiptImageStorage implements ReceiptImageStorage {
    private final S3Client client;

    private final String bucket;

    private final String prefix;

    public S3ReceiptImageStorage(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        String normalized = prefix == null ? "" : prefix.trim().replaceAll("^/+|/+$", "");
        this.prefix = normalized.isEmpty() ? "" : normalized + "/";
    }

    @Override
    public String store(String companyId, String imageSha256, byte[] bytes) {
        String storageKey = ReceiptImageKey.create(companyId, imageSha256);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(prefix + storageKey)
                .contentType("application/octet-stream")
                .build();
        try {
            // 이미지 내용으로 만든 키이므로 같은 이미지 재전송도 같은 객체에 저장됩니다.
            client.putObject(request, RequestBody.fromBytes(bytes));
            return storageKey;
        } catch (SdkException exception) {
            throw new ReceiptImageStorageException("공유 저장소에 영수증 이미지를 저장하지 못했습니다.", exception);
        }
    }

    @Override
    public byte[] load(String storageKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(prefix + storageKey)
                .build();
        try {
            return client.getObjectAsBytes(request).asByteArray();
        } catch (SdkException exception) {
            throw new ReceiptImageStorageException("공유 저장소에서 영수증 이미지를 읽지 못했습니다.", exception);
        }
    }
}
