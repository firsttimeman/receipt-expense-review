package com.example.receipt.extraction;

import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import com.example.receipt.service.ReceiptUploadService;
import com.example.receipt.service.ReceiptExtractionProcessor;
import com.example.receipt.service.ReceiptJobClaimService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(ConcurrentExtractionCharacterizationTest.ExtractorTestConfiguration.class)
class ConcurrentExtractionCharacterizationTest {
    private static final int CONCURRENT_REQUESTS = 100;

    @Autowired ReceiptUploadService uploadService;
    @Autowired ReceiptRepository receiptRepository;
    @Autowired ReceiptExtractionJobRepository jobRepository;
    @Autowired AuditEventRepository auditRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired CountingReceiptExtractor countingExtractor;
    @Autowired ReceiptExtractionProcessor extractionProcessor;
    @Autowired ReceiptJobClaimService claimService;

    @BeforeEach
    void cleanDatabaseAndCounter() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        jobRepository.deleteAll();
        receiptRepository.deleteAll();
        countingExtractor.reset();
    }

    @Test
    void asyncIntakeCreatesOneJobAndDoesNotCallExtractorInsideConcurrentUploads() throws Exception {
        byte[] image = png(800, 1200);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CountDownLatch start = new CountDownLatch(1);
        long startedAt = System.nanoTime();

        try {
            List<Future<UploadResult>> futures = java.util.stream.IntStream.range(0, CONCURRENT_REQUESTS)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return uploadService.upload(
                                "concurrent-extraction-company",
                                null,
                                "receipt-" + index + ".png",
                                "image/png",
                                image
                        );
                    }))
                    .toList();

            start.countDown();
            Set<Long> receiptIds = new HashSet<>();
            for (Future<UploadResult> future : futures) {
                receiptIds.add(future.get(60, TimeUnit.SECONDS).receipt().id());
            }

            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            int extractionCalls = countingExtractor.invocationCount();

            System.out.printf(
                    "ASYNC_INTAKE requests=%d receipts=%d jobs=%d extractionCalls=%d elapsedMillis=%d%n",
                    CONCURRENT_REQUESTS,
                    receiptRepository.count(),
                    jobRepository.count(),
                    extractionCalls,
                    elapsedMillis
            );

            assertThat(receiptIds).hasSize(1);
            assertThat(receiptRepository.count()).isOne();
            assertThat(jobRepository.count()).isOne();
            assertThat(extractionCalls)
                    .as("업로드 요청 경로에서는 외부 AI 추출기를 호출하지 않는다")
                    .isZero();

            ClaimedReceiptJob claimedJob = claimService.claimAvailable(
                    "concurrent-test-worker", 1, Duration.ofSeconds(30)).get(0);
            extractionProcessor.process(claimedJob);
            assertThat(countingExtractor.invocationCount())
                    .as("한 Receipt에 생성된 한 Job을 처리할 때만 추출기가 한 번 호출된다")
                    .isOne();
        } finally {
            executor.shutdownNow();
        }
    }

    private byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @TestConfiguration
    static class ExtractorTestConfiguration {
        @Bean
        @Primary
        CountingReceiptExtractor countingReceiptExtractor() {
            return new CountingReceiptExtractor(new FakeReceiptExtractor());
        }
    }
}
