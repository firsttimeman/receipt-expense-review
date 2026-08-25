package com.example.receipt.service;

import com.example.receipt.domain.AuditAction;
import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.ReceiptExtractionJob;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ExpiredJobRecoveryService {
    private final ReceiptExtractionJobRepository jobRepository;
    private final AuditEventRepository auditRepository;
    private final Clock clock;

    @Transactional
    public int recoverExpired(int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize는 1 이상이어야 합니다.");
        Instant now = Instant.now(clock);
        List<ReceiptExtractionJob> expiredJobs = jobRepository.lockExpiredJobs(now, batchSize);

        for (ReceiptExtractionJob job : expiredJobs) {
            Map<String, Object> details = recoveryDetails(job);
            job.recoverExpiredLease(now);
            auditRepository.save(new AuditEvent(job.receiptId(), now, "system",
                    AuditAction.EXTRACTION_JOB_RECOVERED, null, null, details));
        }
        return expiredJobs.size();
    }

    private Map<String, Object> recoveryDetails(ReceiptExtractionJob job) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (job.lockedBy() != null) details.put("previousWorkerId", job.lockedBy());
        if (job.leaseUntil() != null) details.put("expiredLeaseUntil", job.leaseUntil().toString());
        details.put("attemptCount", job.attemptCount());
        return details;
    }
}
