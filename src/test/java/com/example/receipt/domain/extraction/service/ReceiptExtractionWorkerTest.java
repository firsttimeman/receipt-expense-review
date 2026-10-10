package com.example.receipt.domain.extraction.service;

import com.example.receipt.domain.extraction.config.ReceiptWorkerProperties;
import com.example.receipt.domain.extraction.dto.ClaimedReceiptJob;
import com.example.receipt.domain.extraction.dto.ReceiptWorkerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReceiptExtractionWorkerTest {
    private final ReceiptJobClaimService claimService = mock(ReceiptJobClaimService.class);
    private final ExpiredJobRecoveryService recoveryService = mock(ExpiredJobRecoveryService.class);
    private final ReceiptExtractionProcessor processor = mock(ReceiptExtractionProcessor.class);
    private final ReceiptWorkerProperties properties = properties();
    private ThreadPoolTaskExecutor executor;
    private ReceiptExtractionWorker worker;

    @AfterEach
    void shutdownExecutor() {
        if (worker != null) worker.stopPolling();
        if (executor != null) executor.shutdown();
    }

    @Test
    void refillsFreedCapacityWithoutWaitingForNextScheduledPoll() throws Exception {
        Queue<ClaimedReceiptJob> jobs = prepareQueuedJobs(3);
        CountDownLatch processed = new CountDownLatch(jobs.size());
        doAnswer(invocation -> {
            processed.countDown();
            return null;
        }).when(processor).process(any(ClaimedReceiptJob.class));

        startWorker();

        // 정기 poll은 한 번만 호출한다. 나머지 두 Job은 완료 콜백이 즉시 보충해야 한다.
        worker.poll();

        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(jobs).isEmpty();
    }

    @Test
    void repeatedPollingDoesNotClaimMoreThanTheConcurrencyLimit() throws Exception {
        properties.setConcurrency(2);
        properties.setBatchSize(4);
        Queue<ClaimedReceiptJob> jobs = prepareQueuedJobs(5);
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(5);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();

        doAnswer(invocation -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트 작업의 완료 신호를 받지 못했습니다.");
                }
                return null;
            } finally {
                active.decrementAndGet();
                processed.countDown();
            }
        }).when(processor).process(any(ClaimedReceiptJob.class));

        startWorker();
        try {
            worker.poll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 10; i++) {
                worker.poll();
            }

            assertThat(jobs).hasSize(3);
            verify(claimService).claimAvailable("worker-test", 2, properties.getLeaseDuration());
        } finally {
            finish.countDown();
        }

        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(maximum.get()).isEqualTo(2);
        assertThat(jobs).isEmpty();
    }

    @Test
    void concurrentPollingReservesCapacityBeforeTheDatabaseClaimCompletes() throws Exception {
        properties.setConcurrency(2);
        List<ClaimedReceiptJob> jobs = jobs(2);
        CountDownLatch claimsStarted = new CountDownLatch(2);
        CountDownLatch finishClaims = new CountDownLatch(1);
        AtomicInteger claimIndex = new AtomicInteger();
        Queue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

        when(claimService.claimAvailable(anyString(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    int index = claimIndex.getAndIncrement();
                    claimsStarted.countDown();
                    if (!finishClaims.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("DB 선점 완료 신호를 받지 못했습니다.");
                    }
                    return List.of(jobs.get(index));
                });
        worker = new ReceiptExtractionWorker(claimService, recoveryService, processor, properties,
                new ReceiptWorkerIdentity("worker-test"), pendingTasks::add);

        ExecutorService pollingThreads = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstPoll = pollingThreads.submit(worker::poll);
            Future<?> secondPoll = pollingThreads.submit(worker::poll);
            assertThat(claimsStarted.await(2, TimeUnit.SECONDS)).isTrue();

            // DB 조회가 아직 끝나지 않았어도 두 허가증은 이미 사용 중입니다.
            worker.poll();
            verify(claimService, times(2)).claimAvailable("worker-test", 1, properties.getLeaseDuration());

            finishClaims.countDown();
            firstPoll.get(2, TimeUnit.SECONDS);
            secondPoll.get(2, TimeUnit.SECONDS);

            // Executor에서 대기 중인 작업도 허가증을 계속 보유합니다.
            worker.poll();
            assertThat(pendingTasks).hasSize(2);
            verify(claimService, times(2)).claimAvailable("worker-test", 1, properties.getLeaseDuration());
            verifyNoInteractions(processor);
        } finally {
            finishClaims.countDown();
            pollingThreads.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void returnsUnusedPermitsWhenTheQueueHasFewerJobsThanAvailableSlots(int initialJobCount) throws Exception {
        properties.setConcurrency(3);
        properties.setBatchSize(3);
        Queue<ClaimedReceiptJob> queue = prepareQueuedJobs(0);
        List<ClaimedReceiptJob> jobs = jobs(3);
        queue.addAll(jobs.subList(0, initialJobCount));
        CountDownLatch initialJobsStarted = new CountDownLatch(initialJobCount);
        CountDownLatch allJobsStarted = new CountDownLatch(3);
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(3);

        doAnswer(invocation -> {
            initialJobsStarted.countDown();
            allJobsStarted.countDown();
            try {
                if (!finish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("테스트 작업의 완료 신호를 받지 못했습니다.");
                }
                return null;
            } finally {
                processed.countDown();
            }
        }).when(processor).process(any(ClaimedReceiptJob.class));
        startWorker();

        try {
            worker.poll();
            assertThat(initialJobsStarted.await(2, TimeUnit.SECONDS)).isTrue();

            queue.addAll(jobs.subList(initialJobCount, jobs.size()));
            worker.poll();

            assertThat(allJobsStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(queue).isEmpty();
        } finally {
            finish.countDown();
        }
        assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void failedClaimCanBeRetriedOnTheNextPoll() {
        ClaimedReceiptJob job = jobs(1).get(0);
        when(claimService.claimAvailable(anyString(), anyInt(), any(Duration.class)))
                .thenThrow(new IllegalStateException("DB 조회 실패"))
                .thenReturn(List.of(job))
                .thenReturn(List.of());
        startWorker();

        assertThatThrownBy(worker::poll).isInstanceOf(IllegalStateException.class);
        worker.poll();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(processor).process(job));
    }

    @Test
    void failedProcessingStillStartsTheNextJob() {
        Queue<ClaimedReceiptJob> queue = prepareQueuedJobs(2);
        List<ClaimedReceiptJob> jobs = new ArrayList<>(queue);
        when(processor.process(jobs.get(0))).thenThrow(new IllegalStateException("추출 중 오류"));
        startWorker();

        worker.poll();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(processor).process(jobs.get(1)));
        assertThat(queue).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedExecutionFreesCapacityAndDoesNotBlockOtherJobs(boolean releaseFails) {
        properties.setConcurrency(2);
        properties.setBatchSize(2);
        Queue<ClaimedReceiptJob> queue = prepareQueuedJobs(2);
        List<ClaimedReceiptJob> jobs = new ArrayList<>(queue);
        if (releaseFails) {
            doThrow(new IllegalStateException("DB 반환 실패")).when(claimService).release(jobs.get(0));
        }

        executor = executor(2);
        AtomicInteger submissions = new AtomicInteger();
        Executor rejectFirst = task -> {
            if (submissions.getAndIncrement() == 0) {
                throw new TaskRejectedException("첫 작업 실행 거부");
            }
            executor.execute(task);
        };
        worker = new ReceiptExtractionWorker(claimService, recoveryService, processor, properties,
                new ReceiptWorkerIdentity("worker-test"), rejectFirst);

        worker.poll();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> verify(processor).process(jobs.get(1)));
        await().atMost(Duration.ofSeconds(2)).until(() -> executor.getActiveCount() == 0);
        verify(claimService).release(jobs.get(0));
        worker.poll();
        verify(claimService, times(2)).claimAvailable("worker-test", 2, properties.getLeaseDuration());
    }

    @Test
    void contextClosePreventsPollingAndRefillAfterTheRunningJobFinishes() throws Exception {
        Queue<ClaimedReceiptJob> queue = prepareQueuedJobs(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            started.countDown();
            if (!finish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 작업의 완료 신호를 받지 못했습니다.");
            }
            return null;
        }).when(processor).process(any(ClaimedReceiptJob.class));
        startWorker();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ReceiptExtractionWorker.class, () -> worker);
            context.refresh();
            worker.poll();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            context.publishEvent(new ContextClosedEvent(context));
            worker.poll();
            finish.countDown();
            executor.getThreadPoolExecutor().submit(() -> {}).get(2, TimeUnit.SECONDS);

            assertThat(queue).hasSize(1);
            verify(claimService).claimAvailable("worker-test", 1, properties.getLeaseDuration());
            verify(recoveryService).recoverExpired(properties.getBatchSize());
        } finally {
            finish.countDown();
        }
    }

    @Test
    void stoppingDuringAClaimReturnsJobsWithoutSubmittingThem() {
        properties.setConcurrency(2);
        properties.setBatchSize(2);
        List<ClaimedReceiptJob> jobs = jobs(2);
        when(claimService.claimAvailable(anyString(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    worker.stopPolling();
                    return jobs;
                });
        startWorker();

        worker.poll();
        worker.poll();

        verify(claimService).claimAvailable("worker-test", 2, properties.getLeaseDuration());
        for (ClaimedReceiptJob job : jobs) {
            verify(claimService).release(job);
        }
        verifyNoInteractions(processor);
    }

    private void startWorker() {
        executor = executor(properties.getConcurrency());
        worker = new ReceiptExtractionWorker(claimService, recoveryService, processor, properties,
                new ReceiptWorkerIdentity("worker-test"), executor);
    }

    private Queue<ClaimedReceiptJob> prepareQueuedJobs(int count) {
        Queue<ClaimedReceiptJob> queue = new ConcurrentLinkedQueue<>(jobs(count));
        when(claimService.claimAvailable(anyString(), anyInt(), any(Duration.class)))
                .thenAnswer(invocation -> {
                    int requested = invocation.getArgument(1);
                    List<ClaimedReceiptJob> claimed = new ArrayList<>();
                    while (claimed.size() < requested) {
                        ClaimedReceiptJob job = queue.poll();
                        if (job == null) break;
                        claimed.add(job);
                    }
                    return claimed;
                });
        return queue;
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
