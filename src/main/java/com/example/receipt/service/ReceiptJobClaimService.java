package com.example.receipt.service;

import com.example.receipt.domain.AuditAction;
import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.Receipt;
import com.example.receipt.entity.ReceiptExtractionJob;
import com.example.receipt.exception.JobOwnershipLostException;
import com.example.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.service.model.ClaimedReceiptJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReceiptJobClaimService {
    private final ReceiptExtractionJobRepository jobRepository;
    private final ReceiptRepository receiptRepository;
    private final AuditEventRepository auditRepository;
    private final Clock clock;

    /**
     * MySQL Row Lock을 잡은 짧은 트랜잭션 안에서 실행 가능한 Job을 선점한다.
     * 외부 AI 호출은 이 트랜잭션이 커밋된 뒤 수행한다.
     */
    @Transactional
    public List<ClaimedReceiptJob> claimAvailable(String workerId, int batchSize, Duration leaseDuration) {
        validateClaimRequest(workerId, batchSize, leaseDuration);
        Instant now = Instant.now(clock);
        Instant leaseUntil = now.plus(leaseDuration);
        List<ReceiptExtractionJob> jobs = jobRepository.lockAvailableJobs(now, batchSize);

        for (ReceiptExtractionJob job : jobs) {
            String claimToken = UUID.randomUUID().toString();
            job.claim(workerId, claimToken, now, leaseUntil);
            auditRepository.save(new AuditEvent(job.receiptId(), now, "system",
                    AuditAction.EXTRACTION_JOB_CLAIMED, null, null,
                    Map.of("workerId", workerId,
                            "attemptCount", job.attemptCount(),
                            "leaseUntil", leaseUntil.toString())));
        }

        // @Version 증가값까지 ClaimedReceiptJob에 담기 위해 커밋 전에 명시적으로 flush한다.
        jobRepository.flush();
        return jobs.stream().map(this::toClaimedJob).toList();
    }

    @Transactional
    public void release(ClaimedReceiptJob claimedJob) {
        ReceiptExtractionJob job = jobRepository.findById(claimedJob.jobId())
                .orElseThrow(() -> new IllegalStateException("영수증 추출 작업을 찾을 수 없습니다."));
        if (!job.isOwnedBy(claimedJob.workerId(), claimedJob.claimToken())) {
            throw new JobOwnershipLostException(job.id());
        }
        job.releaseClaim(claimedJob.workerId(), claimedJob.claimToken(), Instant.now(clock));
    }

    private ClaimedReceiptJob toClaimedJob(ReceiptExtractionJob job) {
        Receipt receipt = receiptRepository.findById(job.receiptId())
                .orElseThrow(() -> new ReceiptNotFoundException(job.receiptId()));
        return new ClaimedReceiptJob(job.id(), receipt.id(), job.version(), job.attemptCount(), job.lockedBy(),
                job.claimToken(), job.leaseUntil(), job.imageStorageKey(), receipt.contentType(),
                receipt.originalFileName(), job.duplicateDetected());
    }

    private void validateClaimRequest(String workerId, int batchSize, Duration leaseDuration) {
        if (workerId == null || workerId.isBlank() || workerId.length() > 100) {
            throw new IllegalArgumentException("workerId는 1~100자여야 합니다.");
        }
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize는 1 이상이어야 합니다.");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration은 0보다 커야 합니다.");
        }
    }
}
