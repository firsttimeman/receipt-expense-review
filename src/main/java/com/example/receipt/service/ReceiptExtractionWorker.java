package com.example.receipt.service;

import com.example.receipt.config.ReceiptWorkerProperties;
import com.example.receipt.exception.JobOwnershipLostException;
import com.example.receipt.service.model.ClaimedReceiptJob;
import com.example.receipt.service.model.ReceiptWorkerIdentity;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "receipt.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReceiptExtractionWorker implements ApplicationListener<ContextClosedEvent> {
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
        dispatch(properties.getBatchSize());
    }

    /** 빈 처리 슬롯 범위에서 Job을 선점하고 Executor에 전달한다. */
    private synchronized void dispatch(int requestedJobs) {
        if (!running) return;
        int reserved = reserveCapacity(requestedJobs);
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

    /** 컨텍스트 종료 이벤트는 DataSource 파괴보다 먼저 오므로 신규 DB polling을 즉시 차단한다. */
    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        running = false;
    }

    /** 이벤트를 받지 못하는 특수한 종료 경로에서도 마지막 방어선으로 polling을 중단한다. */
    @PreDestroy
    public synchronized void stopPolling() {
        running = false;
    }

    private int reserveCapacity(int requestedJobs) {
        int limit = Math.min(requestedJobs, capacity.availablePermits());
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
            // 고정 polling 주기를 기다리지 않고 방금 빈 슬롯에 다음 Job 한 건을 즉시 보충한다.
            try {
                dispatch(1);
            } catch (RuntimeException exception) {
                // 이미 끝난 Job 결과에는 영향을 주지 않고 다음 정기 polling에서 다시 시도한다.
                log.warn("빈 Worker 슬롯을 즉시 보충하지 못했습니다. 다음 polling에서 재시도합니다.", exception);
            }
        }
    }
}
