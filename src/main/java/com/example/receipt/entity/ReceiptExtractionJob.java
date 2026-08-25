package com.example.receipt.entity;

import com.example.receipt.domain.ExtractionJobStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "receipt_extraction_jobs", uniqueConstraints = @UniqueConstraint(
        name = "uk_receipt_extraction_jobs_receipt", columnNames = "receipt_id"))
public class ReceiptExtractionJob {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ExtractionJobStatus status;

    @Column(name = "image_storage_key", nullable = false, length = 512)
    private String imageStorageKey;

    @Column(name = "duplicate_detected", nullable = false)
    private boolean duplicateDetected;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = 1000)
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReceiptExtractionJob() {
    }

    public ReceiptExtractionJob(Long receiptId, String imageStorageKey, Instant now) {
        this.receiptId = receiptId;
        this.imageStorageKey = imageStorageKey;
        this.status = ExtractionJobStatus.QUEUED;
        this.availableAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long id() { return id; }
    public long version() { return version; }
    public Long receiptId() { return receiptId; }
    public ExtractionJobStatus status() { return status; }
    public String imageStorageKey() { return imageStorageKey; }
    public boolean duplicateDetected() { return duplicateDetected; }
    public int attemptCount() { return attemptCount; }
    public Instant availableAt() { return availableAt; }
    public Instant startedAt() { return startedAt; }
    public Instant completedAt() { return completedAt; }
    public String lockedBy() { return lockedBy; }
    public Instant leaseUntil() { return leaseUntil; }
    public String claimToken() { return claimToken; }
    public String lastErrorCode() { return lastErrorCode; }
    public String lastErrorMessage() { return lastErrorMessage; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void claim(String workerId, String claimToken, Instant now, Instant newLeaseUntil) {
        if (status != ExtractionJobStatus.QUEUED && status != ExtractionJobStatus.RETRY_WAIT) {
            throw new IllegalStateException("대기 중인 추출 작업만 시작할 수 있습니다.");
        }
        status = ExtractionJobStatus.PROCESSING;
        this.lockedBy = workerId;
        this.claimToken = claimToken;
        this.leaseUntil = newLeaseUntil;
        attemptCount++;
        startedAt = now;
        completedAt = null;
        updatedAt = now;
    }

    public void complete(String workerId, String claimToken, Instant now) {
        requireOwnership(workerId, claimToken);
        status = ExtractionJobStatus.COMPLETED;
        completedAt = now;
        lockedBy = null;
        leaseUntil = null;
        this.claimToken = null;
        updatedAt = now;
    }

    public void fail(String workerId, String claimToken,
                     String errorCode, String errorMessage, Instant now) {
        requireOwnership(workerId, claimToken);
        status = ExtractionJobStatus.FAILED;
        lastErrorCode = errorCode;
        lastErrorMessage = truncate(errorMessage, 1000);
        completedAt = now;
        lockedBy = null;
        leaseUntil = null;
        this.claimToken = null;
        updatedAt = now;
    }

    public void scheduleRetry(String workerId, String claimToken, Instant nextAvailableAt,
                              String errorCode, String errorMessage, Instant now) {
        requireOwnership(workerId, claimToken);
        if (nextAvailableAt == null || !nextAvailableAt.isAfter(now)) {
            throw new IllegalArgumentException("다음 재시도 시각은 현재보다 미래여야 합니다.");
        }
        status = ExtractionJobStatus.RETRY_WAIT;
        availableAt = nextAvailableAt;
        lastErrorCode = errorCode;
        lastErrorMessage = truncate(errorMessage, 1000);
        completedAt = null;
        lockedBy = null;
        leaseUntil = null;
        this.claimToken = null;
        updatedAt = now;
    }

    public void recoverExpiredLease(Instant now) {
        if (status != ExtractionJobStatus.PROCESSING || leaseUntil == null || !leaseUntil.isBefore(now)) {
            throw new IllegalStateException("Lease가 만료된 처리 작업만 복구할 수 있습니다.");
        }
        status = ExtractionJobStatus.QUEUED;
        availableAt = now;
        lockedBy = null;
        leaseUntil = null;
        claimToken = null;
        updatedAt = now;
    }

    public void releaseClaim(String workerId, String claimToken, Instant now) {
        requireOwnership(workerId, claimToken);
        status = ExtractionJobStatus.QUEUED;
        availableAt = now;
        lockedBy = null;
        leaseUntil = null;
        this.claimToken = null;
        updatedAt = now;
    }

    public boolean isOwnedBy(String workerId, String claimToken) {
        return status == ExtractionJobStatus.PROCESSING
                && Objects.equals(lockedBy, workerId)
                && Objects.equals(this.claimToken, claimToken);
    }

    public void markDuplicate(Instant now) {
        duplicateDetected = true;
        updatedAt = now;
    }

    private void requireOwnership(String workerId, String claimToken) {
        if (!isOwnedBy(workerId, claimToken)) {
            throw new com.example.receipt.exception.JobOwnershipLostException(id);
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
