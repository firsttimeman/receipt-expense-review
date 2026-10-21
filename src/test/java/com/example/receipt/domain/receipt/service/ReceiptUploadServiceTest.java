package com.example.receipt.domain.receipt.service;

import com.example.receipt.domain.extraction.entity.ReceiptExtractionJob;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.receipt.dto.UploadResult;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.exception.ReceiptConflictException;
import com.example.receipt.domain.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import com.example.receipt.global.lock.DuplicateReceiptLock;
import com.example.receipt.global.storage.ReceiptImageStorage;
import com.example.receipt.global.storage.UnsupportedReceiptImageException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

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
        Receipt receipt = mock(Receipt.class);
        ReceiptExtractionJob job = mock(ReceiptExtractionJob.class);

        when(receipt.id()).thenReturn(1L);
        when(receipt.ownerEmployeeId()).thenReturn(1L);
        when(job.duplicateDetected()).thenReturn(true);
        when(receiptRepository.findByCompanyIdAndImageSha256(anyString(), anyString()))
                .thenReturn(Optional.empty(), Optional.of(receipt));
        when(jobRepository.findByReceiptId(1L)).thenReturn(Optional.of(job));
        when(duplicateReceiptLock.execute(anyString(), anyString(),
                org.mockito.ArgumentMatchers.<Supplier<UploadResult>>any()))
                .thenThrow(new ReceiptConflictException("락 대기 시간 초과"));

        ReceiptUploadService service = new ReceiptUploadService(
                persistenceService, receiptRepository, jobRepository, idempotencyRepository,
                duplicateReceiptLock, imageStorage, Clock.systemUTC());

        UploadResult result = service.upload(
                "company", null, "receipt.png", "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10}, 1L);

        assertThat(result.receipt()).isSameAs(receipt);
        assertThat(result.job()).isSameAs(job);
        assertThat(result.created()).isFalse();
    }

    @Test
    void rejectsUnsupportedImageBeforeReplayLookupStorageAndLock() {
        ReceiptPersistenceService persistenceService = mock(ReceiptPersistenceService.class);
        ReceiptRepository receiptRepository = mock(ReceiptRepository.class);
        ReceiptExtractionJobRepository jobRepository = mock(ReceiptExtractionJobRepository.class);
        IdempotencyRecordRepository idempotencyRepository = mock(IdempotencyRecordRepository.class);
        DuplicateReceiptLock duplicateReceiptLock = mock(DuplicateReceiptLock.class);
        ReceiptImageStorage imageStorage = mock(ReceiptImageStorage.class);
        ReceiptUploadService service = new ReceiptUploadService(
                persistenceService, receiptRepository, jobRepository, idempotencyRepository,
                duplicateReceiptLock, imageStorage, Clock.systemUTC());

        byte[] webp = java.util.Base64.getDecoder().decode(
                "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        assertThatThrownBy(() -> service.upload(
                "company", "existing-key", "disguised.jpg", "image/jpeg", webp, 1L))
                .isInstanceOf(UnsupportedReceiptImageException.class);
        verifyNoInteractions(persistenceService, receiptRepository, jobRepository,
                idempotencyRepository, duplicateReceiptLock, imageStorage);
    }
}
