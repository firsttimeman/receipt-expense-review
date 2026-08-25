package com.example.receipt;

import com.example.receipt.service.ReceiptUploadService;
import com.example.receipt.service.ReceiptJobClaimService;
import com.example.receipt.service.ExpiredJobRecoveryService;
import com.example.receipt.service.ReceiptExtractionLifecycleService;
import com.example.receipt.service.ReceiptExtractionProcessor;
import com.example.receipt.service.model.UploadResult;
import com.example.receipt.service.model.ClaimedReceiptJob;
import com.example.receipt.domain.ExtractionJobStatus;
import com.example.receipt.domain.ReceiptStatus;
import com.example.receipt.exception.JobOwnershipLostException;
import com.example.receipt.quality.ImageQualityResult;
import com.example.receipt.quality.ImageQualityStatus;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MySqlSchemaIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("receipt_review")
            .withUsername("receipt_user")
            .withPassword("receipt_password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @Autowired ReceiptUploadService uploadService;
    @Autowired ReceiptRepository receiptRepository;
    @Autowired ReceiptExtractionJobRepository jobRepository;
    @Autowired AuditEventRepository auditRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;
    @Autowired ReceiptJobClaimService claimService;
    @Autowired ExpiredJobRecoveryService recoveryService;
    @Autowired ReceiptExtractionLifecycleService lifecycleService;
    @Autowired ReceiptExtractionProcessor extractionProcessor;

    @BeforeEach
    void cleanDatabase() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        jobRepository.deleteAll();
        receiptRepository.deleteAll();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("receipt.worker.enabled", () -> "false");
    }

    @Test
    void flywaySchemaAndRedisLockConcurrencyWorkOnMySql() throws Exception {
        byte[] image = png(800, 1200);
        ExecutorService executor = Executors.newFixedThreadPool(6);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<UploadResult>> futures = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return uploadService.upload("mysql-company", null, "receipt-" + index + ".png",
                                "image/png", image);
                    })).toList();
            start.countDown();
            Set<Object> ids = new HashSet<>();
            for (Future<UploadResult> future : futures) ids.add(future.get(15, TimeUnit.SECONDS).receipt().id());

            assertThat(ids).hasSize(1);
            assertThat(receiptRepository.count()).isOne();
            assertThat(jobRepository.count()).isOne();
            assertThat(receiptRepository.findAll().get(0).status()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void twoWorkersDistributeJobsWithoutOverlappingOnMySqlSkipLocked() throws Exception {
        for (int index = 0; index < 6; index++) {
            uploadService.upload("mysql-worker-company", null, "receipt-" + index + ".png",
                    "image/png", pngWithMarker(800, 1200, index));
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<List<ClaimedReceiptJob>> workerA = executor.submit(() -> {
                start.await();
                return claimAcrossPolls("mysql-worker-a", 3);
            });
            Future<List<ClaimedReceiptJob>> workerB = executor.submit(() -> {
                start.await();
                return claimAcrossPolls("mysql-worker-b", 3);
            });
            start.countDown();

            List<ClaimedReceiptJob> claimed = new java.util.ArrayList<>();
            claimed.addAll(workerA.get(10, TimeUnit.SECONDS));
            claimed.addAll(workerB.get(10, TimeUnit.SECONDS));

            assertThat(claimed).hasSize(6);
            assertThat(claimed).extracting(ClaimedReceiptJob::jobId).doesNotHaveDuplicates();
            assertThat(claimed).extracting(ClaimedReceiptJob::workerId)
                    .contains("mysql-worker-a", "mysql-worker-b");
            assertThat(jobRepository.findAll()).allMatch(job -> job.status() == ExtractionJobStatus.PROCESSING);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * SKIP LOCKED는 다른 트랜잭션이 잠근 행을 기다리지 않으므로 한 번의 조회가 batchSize보다
     * 적게 반환될 수 있다. 운영 Worker처럼 짧은 polling을 반복해 남은 작업을 가져온다.
     */
    private List<ClaimedReceiptJob> claimAcrossPolls(String workerId, int batchSize) throws Exception {
        List<ClaimedReceiptJob> claimed = new java.util.ArrayList<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            claimed.addAll(claimService.claimAvailable(workerId, batchSize, Duration.ofSeconds(30)));
            TimeUnit.MILLISECONDS.sleep(10);
        }
        return claimed;
    }

    @Test
    void expiredLeaseIsRecoveredAndStaleWorkerCannotComplete() throws Exception {
        UploadResult uploaded = uploadService.upload("mysql-recovery-company", null,
                "recover.png", "image/png", pngWithMarker(800, 1200, 99));

        ClaimedReceiptJob staleClaim = claimService.claimAvailable(
                "stale-worker", 1, Duration.ofMillis(5)).get(0);
        TimeUnit.MILLISECONDS.sleep(20);

        assertThat(recoveryService.recoverExpired(10)).isOne();
        ClaimedReceiptJob recoveredClaim = claimService.claimAvailable(
                "recovery-worker", 1, Duration.ofSeconds(30)).get(0);

        assertThat(recoveredClaim.jobId()).isEqualTo(staleClaim.jobId());
        assertThat(recoveredClaim.claimToken()).isNotEqualTo(staleClaim.claimToken());
        assertThatThrownBy(() -> lifecycleService.completeQualityRejection(staleClaim,
                new ImageQualityResult(ImageQualityStatus.NEEDS_RECAPTURE, 200, 300, "테스트")))
                .isInstanceOf(JobOwnershipLostException.class);

        extractionProcessor.process(recoveredClaim);

        assertThat(jobRepository.findById(uploaded.job().id()).orElseThrow().status())
                .isEqualTo(ExtractionJobStatus.COMPLETED);
        assertThat(receiptRepository.findById(uploaded.receipt().id()).orElseThrow().status())
                .isEqualTo(ReceiptStatus.AUTO_APPROVED);
        assertThat(jobRepository.findById(uploaded.job().id()).orElseThrow().attemptCount()).isEqualTo(2);
    }

    private byte[] png(int width, int height) throws Exception {
        return pngWithMarker(width, height, 0);
    }

    private byte[] pngWithMarker(int width, int height, int marker) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        image.setRGB(0, 0, new Color(marker % 256, (marker * 31) % 256, (marker * 67) % 256).getRGB());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
