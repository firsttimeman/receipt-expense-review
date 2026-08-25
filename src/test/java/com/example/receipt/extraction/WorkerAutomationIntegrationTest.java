package com.example.receipt.extraction;

import com.example.receipt.domain.ExtractionJobStatus;
import com.example.receipt.domain.ReceiptStatus;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.service.ReceiptUploadService;
import com.example.receipt.service.model.UploadResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "receipt.worker.enabled=true",
        "receipt.worker.poll-delay-millis=25",
        "receipt.worker.lease-duration=2s",
        "receipt.worker.batch-size=1",
        "receipt.worker.concurrency=1",
        "receipt.worker.worker-id=automation-test-worker"
})
@ActiveProfiles("test")
@Import(WorkerAutomationIntegrationTest.ExtractorTestConfiguration.class)
class WorkerAutomationIntegrationTest {
    @Autowired ReceiptUploadService uploadService;
    @Autowired ReceiptRepository receiptRepository;
    @Autowired ReceiptExtractionJobRepository jobRepository;
    @Autowired AuditEventRepository auditRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired CountingReceiptExtractor countingExtractor;
    @Autowired ConcurrencyTrackingReceiptExtractor concurrencyTrackingExtractor;

    @BeforeEach
    void cleanDatabase() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        jobRepository.deleteAll();
        receiptRepository.deleteAll();
        countingExtractor.reset();
        concurrencyTrackingExtractor.reset();
    }

    @Test
    void scheduledWorkerAutomaticallyProcessesQueuedJobExactlyOnce() throws Exception {
        UploadResult uploaded = uploadService.upload("worker-company", null,
                "receipt.png", "image/png", png(800, 1200));

        assertThat(uploaded.job().status()).isEqualTo(ExtractionJobStatus.QUEUED);
        awaitCompleted(uploaded.receipt().id(), Duration.ofSeconds(5));

        assertThat(jobRepository.findByReceiptId(uploaded.receipt().id()).orElseThrow().status())
                .isEqualTo(ExtractionJobStatus.COMPLETED);
        assertThat(receiptRepository.findById(uploaded.receipt().id()).orElseThrow().status())
                .isEqualTo(ReceiptStatus.AUTO_APPROVED);
        assertThat(countingExtractor.invocationCount()).isOne();
    }

    @Test
    void bulkheadLimitsExternalExtractionToConfiguredConcurrency() throws Exception {
        UploadResult first = uploadService.upload("worker-company", null,
                "receipt-1.png", "image/png", png(800, 1200, Color.WHITE));
        UploadResult second = uploadService.upload("worker-company", null,
                "receipt-2.png", "image/png", png(800, 1200, Color.LIGHT_GRAY));

        awaitCompleted(first.receipt().id(), Duration.ofSeconds(5));
        awaitCompleted(second.receipt().id(), Duration.ofSeconds(5));

        assertThat(countingExtractor.invocationCount()).isEqualTo(2);
        assertThat(concurrencyTrackingExtractor.maximumConcurrentCalls()).isOne();
    }

    private void awaitCompleted(Long receiptId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (jobRepository.findByReceiptId(receiptId)
                    .map(job -> job.status() == ExtractionJobStatus.COMPLETED)
                    .orElse(false)) return;
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError("제한 시간 안에 Worker가 Job을 완료하지 못했습니다.");
    }

    private byte[] png(int width, int height) throws Exception {
        return png(width, height, Color.WHITE);
    }

    private byte[] png(int width, int height, Color color) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @TestConfiguration
    static class ExtractorTestConfiguration {
        @Bean
        ConcurrencyTrackingReceiptExtractor concurrencyTrackingReceiptExtractor() {
            return new ConcurrencyTrackingReceiptExtractor();
        }

        @Bean
        @Primary
        CountingReceiptExtractor countingReceiptExtractor(
                ConcurrencyTrackingReceiptExtractor concurrencyTrackingExtractor) {
            return new CountingReceiptExtractor(concurrencyTrackingExtractor);
        }
    }

    static final class ConcurrencyTrackingReceiptExtractor implements ReceiptExtractor {
        private final ReceiptExtractor delegate = new FakeReceiptExtractor();
        private final AtomicInteger activeCalls = new AtomicInteger();
        private final AtomicInteger maximumConcurrentCalls = new AtomicInteger();

        @Override
        public ExtractionResult extract(ExtractionRequest request) {
            int active = activeCalls.incrementAndGet();
            maximumConcurrentCalls.accumulateAndGet(active, Math::max);
            try {
                TimeUnit.MILLISECONDS.sleep(100);
                return delegate.extract(request);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ExtractionException("동시성 추적 테스트가 중단되었습니다.", exception);
            } finally {
                activeCalls.decrementAndGet();
            }
        }

        int maximumConcurrentCalls() {
            return maximumConcurrentCalls.get();
        }

        void reset() {
            activeCalls.set(0);
            maximumConcurrentCalls.set(0);
        }
    }
}
