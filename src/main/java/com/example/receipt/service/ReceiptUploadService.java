package com.example.receipt.service;

import com.example.receipt.concurrency.DuplicateReceiptLock;
import com.example.receipt.domain.AuditAction;
import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.IdempotencyRecord;
import com.example.receipt.entity.Receipt;
import com.example.receipt.entity.ReceiptExtractionJob;
import com.example.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.service.model.UploadResult;
import com.example.receipt.storage.ReceiptImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
/** 영수증을 내구성 있게 접수하고 AI 추출 작업을 생성한다. 외부 AI는 호출하지 않는다. */
public class ReceiptUploadService {
    private final ReceiptPersistenceService persistenceService;
    private final ReceiptRepository receiptRepository;
    private final ReceiptExtractionJobRepository jobRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final DuplicateReceiptLock duplicateReceiptLock;
    private final ReceiptImageStorage imageStorage;
    private final Clock clock;

    public UploadResult upload(String companyId, String idempotencyKey, String fileName,
                               String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("업로드 파일은 비어 있을 수 없습니다.");
        }
        String normalizedKey = normalize(idempotencyKey);
        Optional<UploadResult> replay = findIdempotentReplay(companyId, normalizedKey);
        if (replay.isPresent()) return replay.get();

        String imageSha256 = sha256(bytes);
        return duplicateReceiptLock.execute(companyId, imageSha256,
                () -> acceptWhileLocked(companyId, normalizedKey, fileName, contentType, bytes, imageSha256));
    }

    private UploadResult acceptWhileLocked(String companyId, String idempotencyKey, String fileName,
                                           String contentType, byte[] bytes, String imageSha256) {
        Optional<UploadResult> replay = findIdempotentReplay(companyId, idempotencyKey);
        if (replay.isPresent()) return replay.get();

        Optional<Receipt> sameImage = receiptRepository.findByCompanyIdAndImageSha256(companyId, imageSha256);
        if (sameImage.isPresent()) {
            Receipt duplicate = persistenceService.markDuplicate(sameImage.get().id());
            return existingResult(duplicate, false);
        }

        Instant now = Instant.now(clock);
        String storageKey = imageStorage.store(companyId, imageSha256, bytes);
        Receipt receipt = new Receipt(companyId, imageSha256, safeFileName(fileName), contentType,
                bytes.length, null, null, List.of(), now);
        AuditEvent uploaded = new AuditEvent(null, now, "system", AuditAction.UPLOADED,
                null, null, createUploadDetails(fileName, contentType, bytes.length, imageSha256));

        try {
            ReceiptExtractionJob job = persistenceService.createQueued(
                    receipt, storageKey, idempotencyKey, List.of(uploaded));
            return new UploadResult(receipt, job, true, false);
        } catch (DataIntegrityViolationException exception) {
            return resolveConstraintConflict(companyId, imageSha256, idempotencyKey, exception);
        }
    }

    private UploadResult resolveConstraintConflict(String companyId, String imageSha256,
                                                   String idempotencyKey,
                                                   DataIntegrityViolationException exception) {
        Optional<UploadResult> replay = findIdempotentReplay(companyId, idempotencyKey);
        if (replay.isPresent()) return replay.get();

        Receipt existing = receiptRepository.findByCompanyIdAndImageSha256(companyId, imageSha256)
                .orElseThrow(() -> exception);
        return existingResult(persistenceService.markDuplicate(existing.id()), false);
    }

    private Optional<UploadResult> findIdempotentReplay(String companyId, String idempotencyKey) {
        if (idempotencyKey == null) return Optional.empty();
        return idempotencyRepository.findByCompanyIdAndIdempotencyKey(companyId, idempotencyKey)
                .map(IdempotencyRecord::receiptId)
                .map(receiptId -> existingResult(findReceipt(receiptId), true));
    }

    private UploadResult existingResult(Receipt receipt, boolean idempotentReplay) {
        ReceiptExtractionJob job = jobRepository.findByReceiptId(receipt.id())
                .orElseThrow(() -> new IllegalStateException("영수증 추출 작업을 찾을 수 없습니다."));
        return new UploadResult(receipt, job, false, idempotentReplay);
    }

    private Receipt findReceipt(Long receiptId) {
        return receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(receiptId));
    }

    private Map<String, Object> createUploadDetails(String fileName, String contentType,
                                                    long fileSize, String imageSha256) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (fileName != null) details.put("fileName", safeFileName(fileName));
        if (contentType != null) details.put("contentType", contentType);
        details.put("fileSize", fileSize);
        details.put("imageSha256", imageSha256);
        return details;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private String safeFileName(String fileName) {
        if (fileName == null) return null;
        String normalized = fileName.replace('\\', '/');
        return normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
