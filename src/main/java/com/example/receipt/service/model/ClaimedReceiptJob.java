package com.example.receipt.service.model;

import java.time.Instant;

/** Worker가 DB 선점 트랜잭션을 커밋한 뒤 안전하게 처리할 수 있는 작업 정보다. */
public record ClaimedReceiptJob(
        Long jobId,
        Long receiptId,
        long jobVersion,
        int attemptCount,
        String workerId,
        String claimToken,
        Instant leaseUntil,
        String imageStorageKey,
        String contentType,
        String fileName,
        boolean duplicateDetected
) {
}
