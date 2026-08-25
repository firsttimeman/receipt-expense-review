package com.example.receipt.service;

import com.example.receipt.entity.Receipt;
import com.example.receipt.extraction.*;
import com.example.receipt.quality.ImageQualityInspector;
import com.example.receipt.quality.ImageQualityResult;
import com.example.receipt.quality.ImageQualityStatus;
import com.example.receipt.service.model.ClaimedReceiptJob;
import com.example.receipt.storage.ReceiptImageStorage;
import com.example.receipt.storage.ReceiptImageStorageException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 한 건의 접수된 작업을 처리하는 실행 단위다.
 * MySQL에서 선점과 커밋이 끝난 Job만 받아 이미지·AI·규칙 처리 과정을 실행한다.
 */
@Service
@RequiredArgsConstructor
public class ReceiptExtractionProcessor {
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(10);

    private final ReceiptExtractionLifecycleService lifecycleService;
    private final ReceiptImageStorage imageStorage;
    private final ImageQualityInspector qualityInspector;
    private final ReceiptExtractor extractor;
    private final Clock clock;

    public Receipt process(ClaimedReceiptJob claimedJob) {
        try {
            byte[] image = imageStorage.load(claimedJob.imageStorageKey());
            ImageQualityResult quality = qualityInspector.inspect(image);
            if (quality.status() != ImageQualityStatus.ACCEPTABLE) {
                return lifecycleService.completeQualityRejection(claimedJob, quality);
            }

            ExtractionResult extraction = extractor.extract(
                    new ExtractionRequest(image, claimedJob.contentType(), claimedJob.fileName()));
            Instant extractedAt = Instant.now(clock);
            return lifecycleService.completeExtraction(claimedJob, extraction, extractedAt);
        } catch (ExtractionException exception) {
            if (claimedJob.attemptCount() < MAX_ATTEMPTS) {
                Instant nextRetryAt = Instant.now(clock).plus(RETRY_DELAY);
                return lifecycleService.scheduleRetry(claimedJob, exception, nextRetryAt);
            }
            return lifecycleService.failExtraction(claimedJob, exception);
        } catch (ReceiptImageStorageException exception) {
            return lifecycleService.failExtraction(claimedJob,
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

}
