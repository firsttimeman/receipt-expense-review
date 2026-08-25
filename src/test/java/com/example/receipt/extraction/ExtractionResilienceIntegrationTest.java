package com.example.receipt.extraction;

import com.example.receipt.domain.AuditAction;
import com.example.receipt.domain.ExtractionJobStatus;
import com.example.receipt.domain.ReceiptStatus;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.service.ReceiptExtractionProcessor;
import com.example.receipt.service.ReceiptJobClaimService;
import com.example.receipt.service.ReceiptUploadService;
import com.example.receipt.service.model.ClaimedReceiptJob;
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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "receipt.worker.enabled=false")
@ActiveProfiles("test")
@Import(ExtractionResilienceIntegrationTest.ExtractorTestConfiguration.class)
class ExtractionResilienceIntegrationTest {
    private static final String WORKER_ID = "resilience-test-worker";

    @Autowired ReceiptUploadService uploadService;
    @Autowired ReceiptJobClaimService claimService;
    @Autowired ReceiptExtractionProcessor processor;
    @Autowired ReceiptRepository receiptRepository;
    @Autowired ReceiptExtractionJobRepository jobRepository;
    @Autowired AuditEventRepository auditRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired ScriptedReceiptExtractor extractor;
    @Autowired MutableClock mutableClock;

    @BeforeEach
    void cleanDatabaseAndExtractor() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        jobRepository.deleteAll();
        receiptRepository.deleteAll();
        extractor.reset();
        mutableClock.reset();
    }

    @Test
    void rateLimitIsPersistedAndEventuallyCompletesAfterRetry() throws Exception {
        extractor.failNext(new ExtractionException("temporary OpenAI failure"));
        UploadResult uploaded = upload("rate-limit.png", Color.WHITE);

        processor.process(claimNext());

        assertThat(job(uploaded).status()).isEqualTo(ExtractionJobStatus.RETRY_WAIT);
        assertThat(job(uploaded).lastErrorCode()).isEqualTo("ExtractionException");
        assertThat(receiptRepository.findById(uploaded.receipt().id()).orElseThrow().status()).isNull();

        mutableClock.advance(Duration.ofSeconds(10));
        processor.process(claimNext());

        assertThat(job(uploaded).status()).isEqualTo(ExtractionJobStatus.COMPLETED);
        assertThat(receiptRepository.findById(uploaded.receipt().id()).orElseThrow().status())
                .isEqualTo(ReceiptStatus.AUTO_APPROVED);
        assertThat(extractor.invocationCount()).isEqualTo(2);
        assertThat(actions(uploaded)).contains(AuditAction.EXTRACTION_RETRY_SCHEDULED);
    }

    @Test
    void extractionFailureStopsAfterMaximumAttempts() throws Exception {
        extractor.failNext(failure(), failure(), failure());
        UploadResult uploaded = upload("exhausted.png", Color.GRAY);

        processor.process(claimNext());
        mutableClock.advance(Duration.ofSeconds(10));
        processor.process(claimNext());
        mutableClock.advance(Duration.ofSeconds(10));
        processor.process(claimNext());

        assertThat(job(uploaded).status()).isEqualTo(ExtractionJobStatus.FAILED);
        assertThat(job(uploaded).attemptCount()).isEqualTo(3);
        assertThat(receiptRepository.findById(uploaded.receipt().id()).orElseThrow().status())
                .isEqualTo(ReceiptStatus.MANUAL_ENTRY);
        assertThat(extractor.invocationCount()).isEqualTo(3);
        assertThat(actions(uploaded)).contains(AuditAction.EXTRACTION_FAILED);
    }

    private ExtractionException failure() {
        return new ExtractionException("OpenAI extraction failed");
    }

    private UploadResult upload(String fileName, Color color) throws Exception {
        return uploadService.upload("resilience-company", null, fileName, "image/png", png(color));
    }

    private ClaimedReceiptJob claimNext() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            List<ClaimedReceiptJob> jobs = claimService.claimAvailable(
                    WORKER_ID, 1, Duration.ofSeconds(2));
            if (!jobs.isEmpty()) return jobs.get(0);
            TimeUnit.MILLISECONDS.sleep(5);
        }
        throw new AssertionError("재시도 대기 중인 추출 Job을 제한 시간 안에 선점하지 못했습니다.");
    }

    private com.example.receipt.entity.ReceiptExtractionJob job(UploadResult uploaded) {
        return jobRepository.findByReceiptId(uploaded.receipt().id()).orElseThrow();
    }

    private List<AuditAction> actions(UploadResult uploaded) {
        return auditRepository.findByReceiptIdOrderByOccurredAtAsc(uploaded.receipt().id())
                .stream().map(event -> event.action()).toList();
    }

    private byte[] png(Color color) throws Exception {
        BufferedImage image = new BufferedImage(800, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 800, 1200);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @TestConfiguration
    static class ExtractorTestConfiguration {
        @Bean
        @Primary
        ScriptedReceiptExtractor scriptedReceiptExtractor() {
            return new ScriptedReceiptExtractor();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    static final class ScriptedReceiptExtractor implements ReceiptExtractor {
        private final Queue<ExtractionException> failures = new ConcurrentLinkedQueue<>();
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final ReceiptExtractor delegate = new FakeReceiptExtractor();

        @Override
        public ExtractionResult extract(ExtractionRequest request) {
            invocationCount.incrementAndGet();
            ExtractionException failure = failures.poll();
            if (failure != null) throw failure;
            return delegate.extract(request);
        }

        void failNext(ExtractionException... exceptions) {
            failures.addAll(List.of(exceptions));
        }

        int invocationCount() {
            return invocationCount.get();
        }

        void reset() {
            failures.clear();
            invocationCount.set(0);
        }
    }

    static final class MutableClock extends java.time.Clock {
        private static final java.time.Instant INITIAL =
                java.time.Instant.parse("2026-08-25T00:00:00Z");
        private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> current =
                new java.util.concurrent.atomic.AtomicReference<>(INITIAL);

        void advance(Duration duration) {
            current.updateAndGet(value -> value.plus(duration));
        }

        void reset() {
            current.set(INITIAL);
        }

        @Override
        public java.time.ZoneId getZone() {
            return java.time.ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return java.time.Clock.fixed(current.get(), zone);
        }

        @Override
        public java.time.Instant instant() {
            return current.get();
        }
    }
}
