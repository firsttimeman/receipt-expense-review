package com.example.receipt.service;

import com.example.receipt.concurrency.DuplicateReceiptLock;
import com.example.receipt.domain.*;
import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.IdempotencyRecord;
import com.example.receipt.entity.Receipt;
import com.example.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.extraction.*;
import com.example.receipt.quality.*;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.validation.ReceiptStatusRouter;
import com.example.receipt.validation.ValidationEngine;
import com.example.receipt.service.model.UploadResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
/**
 * 영수증 업로드
 */
public class ReceiptUploadService {
    private final ImageQualityInspector qualityInspector;
    private final ReceiptExtractor extractor;
    private final ValidationEngine validationEngine;
    private final ReceiptStatusRouter statusRouter;
    private final ReceiptPersistenceService persistenceService;
    private final ReceiptRepository receiptRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final DuplicateReceiptLock duplicateReceiptLock;
    private final Clock clock;

    /**
     * @param idempotencyKey 같은 요청을 재전송할 때 최초 영수증을 재사용하기 위한 중복 요청 방지 키
     */
    public UploadResult upload(String companyId, String idempotencyKey, String fileName,
                               String contentType, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("업로드 파일은 비어 있을 수 없습니다.");
        }
        // 동일한 중복 요청 방지 키가 이미 처리됐다면 이미지 검사와 AI 호출을 반복하지 않는다.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyRecord> existing = idempotencyRepository
                    .findByCompanyIdAndIdempotencyKey(companyId, idempotencyKey);
            if (existing.isPresent()) {
                Receipt receipt = receiptRepository.findById(existing.get().receiptId())
                        .orElseThrow(() -> new ReceiptNotFoundException(existing.get().receiptId()));
                return new UploadResult(receipt, false, true);
            }
        }

        Instant now = Instant.now(clock);
        String hash = sha256(bytes);
        Optional<Receipt> sameImage = receiptRepository.findByCompanyIdAndImageSha256(companyId, hash);
        if (sameImage.isPresent()) {
            return duplicateReceiptLock.execute(companyId, hash,
                    () -> markExistingDuplicate(companyId, hash));
        }
        List<AuditEvent> events = new ArrayList<>();
        Map<String, Object> uploadDetails = createUploadDetails(
                fileName, contentType, bytes.length, hash);
        events.add(event(now, AuditAction.UPLOADED, null, null,
                uploadDetails));

        ImageQualityResult quality = qualityInspector.inspect(bytes);
        ReceiptData data = null;
        List<RuleResult> rules = List.of();
        ReceiptStatus status;

        if (quality.status() != ImageQualityStatus.ACCEPTABLE) {
            status = quality.status() == ImageQualityStatus.NEEDS_RECAPTURE
                    ? ReceiptStatus.NEEDS_RECAPTURE : ReceiptStatus.UNREADABLE;
            Map<String, Object> qualityDetails = createQualityDetails(quality);
            events.add(event(now, AuditAction.QUALITY_REJECTED, null, status,
                    qualityDetails));
        } else {
            try {
                ExtractionResult extracted = extractor.extract(new ExtractionRequest(bytes, contentType, fileName));
                data = extracted.data();
                Map<String, Object> extractionDetails = createExtractionDetails(extracted, data);
                events.add(event(now, AuditAction.EXTRACTION_COMPLETED, null, null,
                        extractionDetails));
                rules = validationEngine.validate(data, false);
                status = statusRouter.route(data, rules);
                Map<String, Object> validationDetails = createValidationDetails(rules);
                events.add(event(now, AuditAction.VALIDATION_COMPLETED, null, status,
                        validationDetails));
            } catch (ExtractionException exception) {
                status = ReceiptStatus.MANUAL_ENTRY;
                Map<String, Object> failureDetails = createExtractionFailureDetails(exception.getMessage());
                events.add(event(now, AuditAction.EXTRACTION_FAILED, null, status,
                        failureDetails));
            }
        }

        Receipt receipt = new Receipt(companyId, hash, safeFileName(fileName), contentType,
                bytes.length, data, status, rules, now);
        return duplicateReceiptLock.execute(companyId, hash,
                () -> persistWhileLocked(receipt, normalize(idempotencyKey), events));
    }

    private UploadResult persistWhileLocked(Receipt receipt, String idempotencyKey,
                                            List<AuditEvent> events) {
        Optional<Receipt> existing = receiptRepository.findByCompanyIdAndImageSha256(
                receipt.companyId(), receipt.imageSha256());
        if (existing.isPresent()) {
            return new UploadResult(persistenceService.markDuplicate(existing.get().id()), false, false);
        }
        try {
            return new UploadResult(persistenceService.create(receipt, idempotencyKey, events), true, false);
        } catch (DataIntegrityViolationException exception) {
            return resolveConstraintConflict(receipt, idempotencyKey, exception);
        }
    }

    //todo 설명이 필요
    private UploadResult resolveConstraintConflict(Receipt receipt, String idempotencyKey,
                                                    DataIntegrityViolationException exception) {
            // 동시 요청이 같은 키를 먼저 저장했다면 그 요청의 영수증을 반환한다.
            if (idempotencyKey != null) {
                Optional<IdempotencyRecord> record = idempotencyRepository
                        .findByCompanyIdAndIdempotencyKey(receipt.companyId(), idempotencyKey);
                if (record.isPresent()) {
                    Receipt replay = receiptRepository.findById(record.get().receiptId())
                            .orElseThrow(() -> new ReceiptNotFoundException(record.get().receiptId()));
                    return new UploadResult(replay, false, true);
                }
            }
            Receipt existing = receiptRepository.findByCompanyIdAndImageSha256(
                            receipt.companyId(), receipt.imageSha256())
                    .orElseThrow(() -> exception);
            return new UploadResult(persistenceService.markDuplicate(existing.id()), false, false);
    }

    //todo markduplicate랑 차이점 설명
    private UploadResult markExistingDuplicate(String companyId, String imageSha256) {
        Receipt existing = receiptRepository.findByCompanyIdAndImageSha256(companyId, imageSha256)
                .orElseThrow(() -> new IllegalStateException("Redis 락 획득 후 기존 영수증을 찾지 못했습니다."));
        return new UploadResult(persistenceService.markDuplicate(existing.id()), false, false);
    }

    private AuditEvent event(Instant at, AuditAction action,
                             ReceiptStatus previous, ReceiptStatus next, Map<String, Object> details) {
        return new AuditEvent(null, at, "system", action, previous, next, details);
    }


    private Map<String, Object> createUploadDetails(
            String fileName,
            String contentType,
            long fileSize,
            String imageSha256
    ) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (fileName != null) {
            details.put("fileName", safeFileName(fileName));
        }
        if (contentType != null) {
            details.put("contentType", contentType);
        }

        details.put("fileSize", fileSize);
        details.put("imageSha256", imageSha256);
        return details;
    }

    private Map<String, Object> createQualityDetails(ImageQualityResult quality) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (quality.width() != null) {
            details.put("width", quality.width());
        }
        if (quality.height() != null) {
            details.put("height", quality.height());
        }
        if (quality.reason() != null) {
            details.put("reason", quality.reason());
        }

        return details;
    }

    private Map<String, Object> createExtractionDetails(
            ExtractionResult extracted,
            ReceiptData proposedData
    ) {
        Map<String, Object> details = new LinkedHashMap<>();

        details.put("provider", extracted.provider());
        details.put("model", extracted.model());
        if (proposedData != null) {
            details.put("proposedData", proposedData);
        }

        return details;
    }

    private Map<String, Object> createValidationDetails(List<RuleResult> rules) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rules", rules);
        return details;
    }

    private Map<String, Object> createExtractionFailureDetails(String errorMessage) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (errorMessage != null) {
            details.put("error", errorMessage);
        }

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
