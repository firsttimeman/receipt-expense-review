package com.example.receipt.domain.extraction.service;

import com.example.receipt.domain.extraction.config.ReceiptWorkerProperties;
import com.example.receipt.domain.extraction.dto.ClaimedReceiptJob;
import com.example.receipt.domain.extraction.dto.ReceiptWorkerIdentity;
import com.example.receipt.domain.extraction.exception.JobOwnershipLostException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
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

    // DB 선점 전 허가증을 확보하고, 실제 작업이 끝날 때 반환합니다.
    private final Semaphore jobPermits;

    // 종료 요청을 스케줄러와 추출 스레드가 함께 확인합니다.
    private volatile boolean stopped;

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
        this.jobPermits = new Semaphore(properties.getConcurrency());
    }

    /** 중단된 작업을 복구한 뒤, 처리할 여유가 있는 만큼 대기 작업을 가져옵니다. */
    @Scheduled(fixedDelayString = "${receipt.worker.poll-delay-millis:1000}")
    public void poll() {
        if (stopped) {
            return;
        }

        recoveryService.recoverExpired(properties.getBatchSize());
        startNextJobs(properties.getBatchSize());
    }

    /** 허가증을 확보한 개수만큼만 DB에서 작업을 가져옵니다. */
    private void startNextJobs(int maxJobs) {
        if (stopped) {
            return;
        }

        int permitCount = acquireJobPermits(maxJobs);
        if (permitCount == 0) {
            return;
        }

        List<ClaimedReceiptJob> jobs;
        int claimedCount = 0;
        try {
            if (stopped) {
                return;
            }

            jobs = claimService.claimAvailable(
                    identity.value(), permitCount, properties.getLeaseDuration());
            claimedCount = jobs.size();
        } finally {
            // 조회 실패나 작업 부족으로 사용하지 못한 허가증은 바로 반환합니다.
            jobPermits.release(permitCount - claimedCount);
        }

        for (ClaimedReceiptJob job : jobs) {
            submitJob(job);
        }
    }

    private int acquireJobPermits(int maxJobs) {
        int acquiredCount = 0;
        while (acquiredCount < maxJobs && jobPermits.tryAcquire()) {
            acquiredCount++;
        }
        return acquiredCount;
    }

    private void submitJob(ClaimedReceiptJob job) {
        if (stopped) {
            returnUnstartedJob(job);
            return;
        }

        try {
            executor.execute(() -> processJob(job));
        } catch (TaskRejectedException exception) {
            returnUnstartedJob(job);
        }
    }

    private void processJob(ClaimedReceiptJob job) {
        try {
            processor.process(job);
        } catch (JobOwnershipLostException exception) {
            log.info("다른 Worker가 다시 가져간 작업의 이전 결과를 무시합니다. jobId={}", job.jobId());
        } catch (RuntimeException exception) {
            // 처리 중 상태를 유지하면 만료 작업 복구 과정에서 다시 실행할 수 있습니다.
            log.error("영수증 추출 중 예상하지 못한 오류가 발생했습니다. jobId={}", job.jobId(), exception);
        } finally {
            onJobFinished();
        }
    }

    /** 한 건이 끝나면 정기 조회를 기다리지 않고 다음 작업을 바로 시작합니다. */
    private void onJobFinished() {
        jobPermits.release();

        try {
            startNextJobs(1);
        } catch (RuntimeException exception) {
            log.warn("다음 작업을 가져오지 못했습니다. 다음 정기 조회에서 다시 시도합니다.", exception);
        }
    }

    private void returnUnstartedJob(ClaimedReceiptJob job) {
        try {
            claimService.release(job);
            log.info("실행하지 못한 작업을 처리 대기 상태로 돌렸습니다. jobId={}", job.jobId());
        } catch (RuntimeException exception) {
            // 한 건의 반환 실패가 나머지 작업 실행을 막지 않도록 만료 작업 복구에 맡깁니다.
            log.warn("실행하지 못한 작업을 반환하지 못했습니다. 만료 후 복구합니다. jobId={}", job.jobId(), exception);
        } finally {
            jobPermits.release();
        }
    }

    /** DB 연결이 닫히기 전에 새 작업 조회를 중단합니다. */
    @EventListener(ContextClosedEvent.class)
    @PreDestroy
    public void stopPolling() {
        stopped = true;
    }
}
