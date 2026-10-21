package com.example.receipt.domain.extraction.service;

import com.example.receipt.domain.extraction.dto.ClaimedReceiptJob;
import com.example.receipt.domain.extraction.dto.ExtractionRequest;
import com.example.receipt.domain.extraction.dto.ExtractionResult;
import com.example.receipt.domain.extraction.exception.ExtractionException;
import com.example.receipt.domain.extraction.extractor.ReceiptExtractor;
import com.example.receipt.domain.extraction.quality.ImageQualityInspector;
import com.example.receipt.domain.extraction.quality.ImageQualityResult;
import com.example.receipt.domain.extraction.quality.ImageQualityStatus;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.global.storage.ReceiptImageStorage;
import com.example.receipt.global.storage.ReceiptImageStorageException;
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
    // 최초 실행을 포함한 전체 시도 횟수입니다. S3 읽기와 AI 추출이 같은 한도를 사용합니다.
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
            return retryOrFail(claimedJob, exception);
        } catch (ReceiptImageStorageException exception) {
            if (exception.isRetryable()) {
                return retryOrFail(claimedJob, exception);
            }
            return lifecycleService.failExtraction(claimedJob,
                    exception.getClass().getSimpleName(), exception.getMessage());
        }
    }

    private Receipt retryOrFail(ClaimedReceiptJob claimedJob, RuntimeException exception) {
        String errorCode = exception.getClass().getSimpleName();
        if (claimedJob.attemptCount() < MAX_ATTEMPTS) {
            // 스레드를 대기시키지 않고, DB에 다음 실행 가능 시각을 기록합니다.
            Instant nextRetryAt = Instant.now(clock).plus(RETRY_DELAY);
            return lifecycleService.scheduleRetry(claimedJob, errorCode, exception.getMessage(), nextRetryAt);
        }
        return lifecycleService.failExtraction(claimedJob, errorCode, exception.getMessage());
    }

}
