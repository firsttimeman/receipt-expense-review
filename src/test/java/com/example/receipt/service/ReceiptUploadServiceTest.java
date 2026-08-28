package com.example.receipt.service;

import com.example.receipt.concurrency.DuplicateReceiptLock;
import com.example.receipt.entity.Receipt;
import com.example.receipt.entity.ReceiptExtractionJob;
import com.example.receipt.exception.ReceiptConflictException;
import com.example.receipt.observability.ReceiptMetrics;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.service.model.UploadResult;
import com.example.receipt.storage.ReceiptImageStorage;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ReceiptUploadServiceTest {

    @Test
    void returnsCompletedDuplicateWhenRedisWaitTimesOut() {
        ReceiptPersistenceService persistenceService = mock(ReceiptPersistenceService.class);
        ReceiptRepository receiptRepository = mock(ReceiptRepository.class);
        ReceiptExtractionJobRepository jobRepository = mock(ReceiptExtractionJobRepository.class);
        IdempotencyRecordRepository idempotencyRepository = mock(IdempotencyRecordRepository.class);
        DuplicateReceiptLock duplicateReceiptLock = mock(DuplicateReceiptLock.class);
        ReceiptImageStorage imageStorage = mock(ReceiptImageStorage.class);
        ReceiptMetrics metrics = mock(ReceiptMetrics.class);
        Receipt receipt = mock(Receipt.class);
        ReceiptExtractionJob job = mock(ReceiptExtractionJob.class);

        when(receipt.id()).thenReturn(1L);
        when(job.duplicateDetected()).thenReturn(true);
        when(receiptRepository.findByCompanyIdAndImageSha256(anyString(), anyString()))
                .thenReturn(Optional.empty(), Optional.of(receipt));
        when(jobRepository.findByReceiptId(1L)).thenReturn(Optional.of(job));
        when(duplicateReceiptLock.execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.<Supplier<UploadResult>>any()))
                .thenThrow(new ReceiptConflictException("락 대기 시간 초과"));

        ReceiptUploadService service = new ReceiptUploadService(
                persistenceService, receiptRepository, jobRepository, idempotencyRepository,
                duplicateReceiptLock, imageStorage, Clock.systemUTC(), metrics);

        UploadResult result = service.upload(
                "company", null, "receipt.png", "image/png", new byte[]{1});

        assertThat(result.receipt()).isSameAs(receipt);
        assertThat(result.job()).isSameAs(job);
        assertThat(result.created()).isFalse();
        verify(metrics).recordUpload("duplicate");
    }
}
