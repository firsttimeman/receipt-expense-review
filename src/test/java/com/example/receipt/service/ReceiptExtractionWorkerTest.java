package com.example.receipt.service;

import com.example.receipt.config.ReceiptWorkerProperties;
import com.example.receipt.service.model.ClaimedReceiptJob;
import com.example.receipt.service.model.ReceiptWorkerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReceiptExtractionWorkerTest {
    private ThreadPoolTaskExecutor executor;

    @AfterEach
    void shutdownExecutor() {
        if (executor != null) executor.shutdown();
    }

    @Test
    void refillsFreedCapacityWithoutWaitingForNextScheduledPoll() throws Exception {
        ReceiptJobClaimService claimService = mock(ReceiptJobClaimService.class);
        ExpiredJobRecoveryService recoveryService = mock(ExpiredJobRecoveryService.class);
        ReceiptExtractionProcessor processor = mock(ReceiptExtractionProcessor.class);
        ReceiptWorkerProperties properties = properties();
        Queue<ClaimedReceiptJob> jobs = new ConcurrentLinkedQueue<>(jobs(3));
        CountDownLatch processed = new CountDownLatch(jobs.size());

        when(claimService.claimAvailable(anyString(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    int requested = invocation.getArgument(1);
                    List<ClaimedReceiptJob> claimed = new ArrayList<>();
                    while (claimed.size() < requested) {
                        ClaimedReceiptJob job = jobs.poll();
                        if (job == null) break;
                        claimed.add(job);
                    }
                    return claimed;
                });
        doAnswer(invocation -> {
            processed.countDown();
            return null;
        }).when(processor).process(any(ClaimedReceiptJob.class));

        executor = executor(properties.getConcurrency());
        ReceiptExtractionWorker worker = new ReceiptExtractionWorker(
                claimService, recoveryService, processor, properties,
                new ReceiptWorkerIdentity("worker-test"), executor);

        // 정기 poll은 한 번만 호출한다. 나머지 두 Job은 완료 콜백이 즉시 보충해야 한다.
        worker.poll();

        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(jobs).isEmpty();
    }

    private ReceiptWorkerProperties properties() {
        ReceiptWorkerProperties properties = new ReceiptWorkerProperties();
        properties.setBatchSize(1);
        properties.setConcurrency(1);
        properties.setLeaseDuration(Duration.ofSeconds(30));
        return properties;
    }

    private ThreadPoolTaskExecutor executor(int concurrency) {
        ThreadPoolTaskExecutor result = new ThreadPoolTaskExecutor();
        result.setCorePoolSize(concurrency);
        result.setMaxPoolSize(concurrency);
        result.setQueueCapacity(concurrency);
        result.initialize();
        return result;
    }

    private List<ClaimedReceiptJob> jobs(int count) {
        return java.util.stream.LongStream.rangeClosed(1, count)
                .mapToObj(id -> new ClaimedReceiptJob(
                        id, id, 0, 1, "worker-test", "token-" + id,
                        Instant.now().plusSeconds(30), "image-" + id,
                        "image/png", "receipt-" + id + ".png", false))
                .toList();
    }
}
