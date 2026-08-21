package com.example.receipt;

import com.example.receipt.service.ReceiptUploadService;
import com.example.receipt.service.model.UploadResult;
import com.example.receipt.domain.ReceiptStatus;
import com.example.receipt.repository.ReceiptRepository;
import org.junit.jupiter.api.Test;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

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

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
            assertThat(receiptRepository.findAll().getFirst().status()).isEqualTo(ReceiptStatus.NEEDS_REVIEW);
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
}
