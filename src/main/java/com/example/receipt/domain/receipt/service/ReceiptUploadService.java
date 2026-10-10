package com.example.receipt.domain.receipt.service;

import com.example.receipt.domain.extraction.entity.ReceiptExtractionJob;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.receipt.dto.UploadResult;
import com.example.receipt.domain.receipt.entity.AuditEvent;
import com.example.receipt.domain.receipt.entity.IdempotencyRecord;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.exception.ReceiptConflictException;
import com.example.receipt.domain.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.domain.receipt.model.AuditAction;
import com.example.receipt.domain.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import com.example.receipt.global.lock.DuplicateReceiptLock;
import com.example.receipt.global.storage.ReceiptImageStorage;
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
import java.util.Objects;
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
                               String contentType, byte[] bytes, Long ownerId) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("업로드 파일은 비어 있을 수 없습니다.");
        }
        String normalizedKey = normalize(idempotencyKey);
        Optional<UploadResult> replay = findIdempotentReplay(companyId, normalizedKey, ownerId);
        if (replay.isPresent()) return replay.get();

        String imageSha256 = sha256(bytes);
        Optional<UploadResult> processedDuplicate = findProcessedDuplicate(companyId, imageSha256, ownerId);
        if (processedDuplicate.isPresent()) {
            return processedDuplicate.get();
        }

        try {
            return duplicateReceiptLock.execute(companyId, imageSha256,
                    () -> acceptWhileLocked(companyId, normalizedKey, fileName,
                            contentType, bytes, imageSha256, ownerId));
        } catch (ReceiptConflictException exception) {
            // 락 대기 중 다른 요청이 DB 생성을 끝냈다면 충돌 응답 대신 그 결과로 수렴한다.
            return findProcessedDuplicate(companyId, imageSha256, ownerId)
                    .orElseThrow(() -> exception);
        }
    }

    private void ensureOwner(Receipt receipt, Long ownerId) {
        if (!Objects.equals(receipt.ownerEmployeeId(), ownerId)) {
            throw new ReceiptConflictException("이미 사용 중인 이미지 또는 요청 키입니다.");
        }
    }

    private UploadResult acceptWhileLocked(String companyId, String idempotencyKey, String fileName,
                                           String contentType, byte[] bytes, String imageSha256, Long ownerId) {
        Optional<UploadResult> replay = findIdempotentReplay(companyId, idempotencyKey, ownerId);
        if (replay.isPresent()) return replay.get();

        Optional<Receipt> sameImage = receiptRepository.findByCompanyIdAndImageSha256(companyId, imageSha256);
        if (sameImage.isPresent()) {
            ensureOwner(sameImage.get(), ownerId);
            Receipt duplicate = persistenceService.markDuplicate(sameImage.get().id());
            return existingResult(duplicate, false);
        }

        Instant now = Instant.now(clock);
        String storageKey = imageStorage.store(companyId, imageSha256, bytes);
        Receipt receipt = new Receipt(companyId, imageSha256, safeFileName(fileName), contentType,
                bytes.length, null, null, List.of(), now);
        receipt.submittedBy(ownerId);
        AuditEvent uploaded = new AuditEvent(null, now, ownerId == null ? "system" : "employee:" + ownerId, AuditAction.UPLOADED,
                null, null, createUploadDetails(fileName, contentType, bytes.length, imageSha256));

        try {
            ReceiptExtractionJob job = persistenceService.createQueued(
                    receipt, storageKey, idempotencyKey, List.of(uploaded));
            return new UploadResult(receipt, job, true, false);
        } catch (DataIntegrityViolationException exception) {
            return resolveConstraintConflict(companyId, imageSha256, idempotencyKey, exception, ownerId);
        }
    }

    private UploadResult resolveConstraintConflict(String companyId, String imageSha256,
                                                   String idempotencyKey,
                                                   DataIntegrityViolationException exception, Long ownerId) {
        Optional<UploadResult> replay = findIdempotentReplay(companyId, idempotencyKey, ownerId);
        if (replay.isPresent()) return replay.get();

        Receipt existing = receiptRepository.findByCompanyIdAndImageSha256(companyId, imageSha256)
                .orElseThrow(() -> exception);
        ensureOwner(existing, ownerId);
        return existingResult(persistenceService.markDuplicate(existing.id()), false);
    }

    private Optional<UploadResult> findIdempotentReplay(String companyId, String idempotencyKey, Long ownerId) {
        if (idempotencyKey == null) return Optional.empty();
        return idempotencyRepository.findByCompanyIdAndIdempotencyKey(companyId, idempotencyKey)
                .map(IdempotencyRecord::receiptId)
                .map(receiptId -> {
                    Receipt receipt = findReceipt(receiptId);
                    ensureOwner(receipt, ownerId);
                    return existingResult(receipt, true);
                });
    }

    /**
     * 최초 중복 감지가 이미 감사 로그와 규칙에 반영된 영수증은 Redis 락을 다시 기다릴 이유가 없다.
     * 아직 중복 표시가 없으면 기존 락 경로에서 한 번만 markDuplicate를 실행한다.
     */
    private Optional<UploadResult> findProcessedDuplicate(String companyId, String imageSha256, Long ownerId) {
        return receiptRepository.findByCompanyIdAndImageSha256(companyId, imageSha256)
                .map(receipt -> { ensureOwner(receipt, ownerId); return receipt; })
                .flatMap(receipt -> jobRepository.findByReceiptId(receipt.id())
                        .filter(ReceiptExtractionJob::duplicateDetected)
                        .map(job -> existingResult(receipt, job, false)));
    }

    private UploadResult existingResult(Receipt receipt, boolean idempotentReplay) {
        ReceiptExtractionJob job = jobRepository.findByReceiptId(receipt.id())
                .orElseThrow(() -> new IllegalStateException("영수증 추출 작업을 찾을 수 없습니다."));
        return existingResult(receipt, job, idempotentReplay);
    }

    private UploadResult existingResult(Receipt receipt, ReceiptExtractionJob job,
                                        boolean idempotentReplay) {
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
