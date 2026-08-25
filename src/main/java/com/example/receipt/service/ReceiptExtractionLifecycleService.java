package com.example.receipt.service;

import com.example.receipt.domain.*;
import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.Receipt;
import com.example.receipt.entity.ReceiptExtractionJob;
import com.example.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.extraction.ExtractionResult;
import com.example.receipt.extraction.ExtractionException;
import com.example.receipt.quality.ImageQualityResult;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.service.model.ClaimedReceiptJob;
import com.example.receipt.exception.JobOwnershipLostException;
import com.example.receipt.validation.ReceiptStatusRouter;
import com.example.receipt.validation.ValidationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReceiptExtractionLifecycleService {
    private final ReceiptRepository receiptRepository;
    private final ReceiptExtractionJobRepository jobRepository;
    private final AuditEventRepository auditRepository;
    private final ValidationEngine validationEngine;
    private final ReceiptStatusRouter statusRouter;
    private final Clock clock;

    @Transactional
    public Receipt completeQualityRejection(ClaimedReceiptJob claimedJob, ImageQualityResult quality) {
        ReceiptExtractionJob job = findOwnedJob(claimedJob);
        Receipt receipt = findReceipt(claimedJob.receiptId());
        ReceiptStatus next = quality.status() == com.example.receipt.quality.ImageQualityStatus.NEEDS_RECAPTURE
                ? ReceiptStatus.NEEDS_RECAPTURE : ReceiptStatus.UNREADABLE;
        Instant now = Instant.now(clock);
        receipt.completeExtraction(null, List.of(), next, now);
        job.complete(claimedJob.workerId(), claimedJob.claimToken(), now);
        auditRepository.save(new AuditEvent(receipt.id(), now, "system", AuditAction.QUALITY_REJECTED,
                null, next, qualityDetails(quality)));
        return receipt;
    }

    @Transactional
    public Receipt completeExtraction(ClaimedReceiptJob claimedJob, ExtractionResult extraction,
                                      Instant extractedAt) {
        ReceiptExtractionJob job = findOwnedJob(claimedJob);
        Receipt receipt = findReceipt(claimedJob.receiptId());
        // 중복 제출이 추출 도중 감지됐을 수 있으므로 선점 시 스냅샷이 아니라 현재 Job 값을 사용한다.
        List<RuleResult> rules = validationEngine.validate(extraction.data(), job.duplicateDetected());
        ReceiptStatus next = statusRouter.route(extraction.data(), rules);
        Instant completedAt = Instant.now(clock);
        receipt.completeExtraction(extraction.data(), rules, next, completedAt);
        job.complete(claimedJob.workerId(), claimedJob.claimToken(), completedAt);
        auditRepository.save(new AuditEvent(receipt.id(), extractedAt, "system",
                AuditAction.EXTRACTION_COMPLETED, null, null, extractionDetails(extraction)));
        auditRepository.save(new AuditEvent(receipt.id(), completedAt, "system",
                AuditAction.VALIDATION_COMPLETED, null, next, Map.of("rules", rules)));
        return receipt;
    }

    @Transactional
    public Receipt scheduleRetry(ClaimedReceiptJob claimedJob, ExtractionException exception,
                                 Instant requestedRetryAt) {
        ReceiptExtractionJob job = findOwnedJob(claimedJob);
        Receipt receipt = findReceipt(claimedJob.receiptId());
        Instant now = Instant.now(clock);
        Instant nextRetryAt = requestedRetryAt.isAfter(now) ? requestedRetryAt : now.plusMillis(1);
        job.scheduleRetry(claimedJob.workerId(), claimedJob.claimToken(), nextRetryAt,
                exception.getClass().getSimpleName(), exception.getMessage(), now);

        auditRepository.save(new AuditEvent(receipt.id(), now, "system",
                AuditAction.EXTRACTION_RETRY_SCHEDULED, null, null,
                retryDetails(job, exception, nextRetryAt, now)));
        return receipt;
    }

    @Transactional
    public Receipt failExtraction(ClaimedReceiptJob claimedJob, ExtractionException exception) {
        return failExtraction(claimedJob, exception.getClass().getSimpleName(), exception.getMessage());
    }

    @Transactional
    public Receipt failExtraction(ClaimedReceiptJob claimedJob, String errorCode, String errorMessage) {
        ReceiptExtractionJob job = findOwnedJob(claimedJob);
        Receipt receipt = findReceipt(claimedJob.receiptId());
        Instant now = Instant.now(clock);
        receipt.completeExtraction(null, List.of(), ReceiptStatus.MANUAL_ENTRY, now);
        job.fail(claimedJob.workerId(), claimedJob.claimToken(), errorCode, errorMessage, now);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("errorCode", errorCode);
        if (errorMessage != null) details.put("error", errorMessage);
        details.put("attemptCount", job.attemptCount());
        auditRepository.save(new AuditEvent(receipt.id(), now, "system", AuditAction.EXTRACTION_FAILED,
                null, ReceiptStatus.MANUAL_ENTRY, details));
        return receipt;
    }

    private Map<String, Object> retryDetails(ReceiptExtractionJob job, ExtractionException exception,
                                             Instant nextRetryAt, Instant now) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("errorCode", exception.getClass().getSimpleName());
        details.put("attemptCount", job.attemptCount());
        details.put("nextAvailableAt", nextRetryAt.toString());
        details.put("delayMillis", Duration.between(now, nextRetryAt).toMillis());
        return details;
    }

    private Receipt findReceipt(Long receiptId) {
        return receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(receiptId));
    }

    private ReceiptExtractionJob findOwnedJob(ClaimedReceiptJob claimedJob) {
        ReceiptExtractionJob job = jobRepository.findById(claimedJob.jobId())
                .orElseThrow(() -> new IllegalStateException("영수증 추출 작업을 찾을 수 없습니다."));
        if (!job.receiptId().equals(claimedJob.receiptId())
                || !job.isOwnedBy(claimedJob.workerId(), claimedJob.claimToken())) {
            throw new JobOwnershipLostException(claimedJob.jobId());
        }
        return job;
    }

    private Map<String, Object> qualityDetails(ImageQualityResult quality) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (quality.width() != null) details.put("width", quality.width());
        if (quality.height() != null) details.put("height", quality.height());
        if (quality.reason() != null) details.put("reason", quality.reason());
        return details;
    }

    private Map<String, Object> extractionDetails(ExtractionResult extraction) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", extraction.provider());
        details.put("model", extraction.model());
        if (extraction.data() != null) details.put("proposedData", extraction.data());
        return details;
    }
}
