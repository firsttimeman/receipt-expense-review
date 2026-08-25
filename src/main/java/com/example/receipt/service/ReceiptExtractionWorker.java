package com.example.receipt.service;

import com.example.receipt.config.ReceiptWorkerProperties;
import com.example.receipt.exception.JobOwnershipLostException;
import com.example.receipt.service.model.ClaimedReceiptJob;
import com.example.receipt.service.model.ReceiptWorkerIdentity;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "receipt.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReceiptExtractionWorker {
    private final ReceiptJobClaimService claimService;
    private final ExpiredJobRecoveryService recoveryService;
    private final ReceiptExtractionProcessor processor;
    private final ReceiptWorkerProperties properties;
    private final ReceiptWorkerIdentity identity;
    private final Executor executor;
    private final Semaphore capacity;
    private volatile boolean running = true;

    public ReceiptExtractionWorker(ReceiptJobClaimService claimService,
                                   ExpiredJobRecoveryService recoveryService,
                                   ReceiptExtractionProcessor processor,
                                   ReceiptWorkerProperties properties,
                                   ReceiptWorkerIdentity identity,
                                   @Qualifier("receiptExtractionExecutor") Executor executor) {
        this.claimService = claimService;
        this.recoveryService = recoveryService;
        this.processor = processor;
        this.properties = properties;
        this.identity = identity;
        this.executor = executor;
        this.capacity = new Semaphore(properties.getConcurrency());
    }

    // 이 Spring 버전의 fixedDelayString에는 숫자(밀리초)를 전달한다.
    @Scheduled(fixedDelayString = "${receipt.worker.poll-delay-millis:1000}")
    public synchronized void poll() {
        if (!running) return;
        recoveryService.recoverExpired(properties.getBatchSize());

        int reserved = reserveCapacity();
        if (reserved == 0) return;

        List<ClaimedReceiptJob> claimedJobs;
        try {
            claimedJobs = claimService.claimAvailable(identity.value(), reserved,
                    properties.getLeaseDuration());
        } catch (RuntimeException exception) {
            capacity.release(reserved);
            throw exception;
        }

        capacity.release(reserved - claimedJobs.size());
        for (ClaimedReceiptJob claimedJob : claimedJobs) {
            submit(claimedJob);
        }
    }

    /** 컨텍스트 종료가 시작되면 진행 중인 짧은 polling을 마친 뒤 신규 DB 선점을 중단한다. */
    @PreDestroy
    public synchronized void stopPolling() {
        running = false;
    }

    private int reserveCapacity() {
        int limit = Math.min(properties.getBatchSize(), capacity.availablePermits());
        int reserved = 0;
        while (reserved < limit && capacity.tryAcquire()) reserved++;
        return reserved;
    }

    private void submit(ClaimedReceiptJob claimedJob) {
        try {
            executor.execute(() -> process(claimedJob));
        } catch (TaskRejectedException exception) {
            capacity.release();
            claimService.release(claimedJob);
            log.warn("추출 Executor가 작업을 거부해 Job을 다시 QUEUED로 돌렸습니다. jobId={}", claimedJob.jobId());
        }
    }

    private void process(ClaimedReceiptJob claimedJob) {
        try {
            processor.process(claimedJob);
        } catch (JobOwnershipLostException exception) {
            log.info("Lease 만료 후 재선점된 Job의 오래된 결과를 무시합니다. jobId={}", claimedJob.jobId());
        } catch (RuntimeException exception) {
            // 예상하지 못한 프로세스 오류는 Job을 PROCESSING으로 남겨 Lease 복구 경로를 검증 가능하게 한다.
            log.error("추출 Worker 처리 중 예상하지 못한 오류가 발생했습니다. jobId={}",
                    claimedJob.jobId(), exception);
        } finally {
            capacity.release();
        }
    }
}
