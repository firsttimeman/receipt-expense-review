package com.example.receipt.domain.receipt.controller;

import com.example.receipt.domain.extraction.dto.ClaimedReceiptJob;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.extraction.service.ReceiptExtractionProcessor;
import com.example.receipt.domain.extraction.service.ReceiptJobClaimService;
import com.example.receipt.domain.receipt.dto.UploadResult;
import com.example.receipt.domain.receipt.repository.AuditEventRepository;
import com.example.receipt.domain.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import com.example.receipt.domain.receipt.service.ReceiptUploadService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import jakarta.servlet.http.Cookie;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class ReceiptApiIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private Cookie sessionCookie;

    private Cookie submitterCookie;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ReceiptUploadService uploadService;
    @Autowired ReceiptExtractionProcessor extractionProcessor;
    @Autowired ReceiptJobClaimService claimService;
    @Autowired ReceiptRepository receiptRepository;
    @Autowired ReceiptExtractionJobRepository jobRepository;
    @Autowired AuditEventRepository auditRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;

    @Autowired com.example.receipt.domain.employee.repository.EmployeeRepository employees;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;

    @BeforeEach
    void cleanDatabase() throws Exception {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        jobRepository.deleteAll();
        receiptRepository.deleteAll();
        employees.deleteAll();
        var reviewer = new com.example.receipt.domain.employee.entity.Employee("regression-reviewer", "회귀 테스트 검토자",
                com.example.receipt.domain.employee.model.EmployeeRole.REVIEWER);
        String passwordHash = encoder.encode("regression-password-123");
        reviewer.setPassword(passwordHash);
        employees.saveAndFlush(reviewer);
        var submitter = new com.example.receipt.domain.employee.entity.Employee("regression-submitter", "회귀 테스트 제출자",
                com.example.receipt.domain.employee.model.EmployeeRole.EMPLOYEE);
        submitter.setPassword(passwordHash);
        employees.saveAndFlush(submitter);
        sessionCookie = login(reviewer.loginId());
        submitterCookie = login(submitter.loginId());
    }

    private Cookie login(String loginId) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "loginId", loginId, "password", "regression-password-123"))))
                .andExpect(status().isNoContent()).andReturn().getResponse().getCookie("JSESSIONID");
    }

    @Test
    void acceptsReceiptDurablyThenProcessesAndAutoApprovesIt() throws Exception {
        JsonNode accepted = upload("receipt.png", png(800, 1200), "durable-upload-001", 202);
        assertThat(accepted.path("jobStatus").asText()).isEqualTo("QUEUED");
        JsonNode queued = getReceipt(accepted.path("receiptId").asLong());
        assertThat(queued.path("status").isNull()).isTrue();
        assertThat(queued.path("jobStatus").asText()).isEqualTo("QUEUED");
        assertThat(receiptRepository.count()).isOne();
        assertThat(jobRepository.count()).isOne();
        assertThat(idempotencyRepository.count()).isOne();
        assertThat(auditRepository.count()).isOne();

        process(accepted.path("receiptId").asLong());
        JsonNode response = getReceipt(accepted.path("receiptId").asLong());

        assertThat(response.path("status").asText()).isEqualTo("AUTO_APPROVED");
        assertThat(response.path("jobStatus").asText()).isEqualTo("COMPLETED");
        assertThat(response.path("originalData").path("shopName").asText()).isEqualTo("테스트상점");
        assertThat(response.path("currentData").path("totalAmount").decimalValue()).isEqualByComparingTo("12000");
        assertThat(response.path("file").path("sha256").asText()).hasSize(64);
    }

    @Test
    void routesLowResolutionAndUnreadableImagesSafely() throws Exception {
        JsonNode small = upload("small.png", png(200, 300), null, 202);
        process(small.path("receiptId").asLong());
        assertThat(getReceipt(small.path("receiptId").asLong()).path("status").asText())
                .isEqualTo("NEEDS_RECAPTURE");
        JsonNode broken = upload("broken.png", new byte[]{1, 2, 3, 4}, null, 202);
        process(broken.path("receiptId").asLong());
        assertThat(getReceipt(broken.path("receiptId").asLong()).path("status").asText())
                .isEqualTo("UNREADABLE");
    }

    @Test
    void supportsFieldCorrectionFinalDecisionAndAuditTrail() throws Exception {
        JsonNode accepted = upload("missing-shop-name.png", png(800, 1200), null, 202);
        process(accepted.path("receiptId").asLong());
        JsonNode uploaded = getReceipt(accepted.path("receiptId").asLong());
        String id = uploaded.path("id").asText();
        long version = uploaded.path("version").asLong();
        assertThat(uploaded.path("status").asText()).isEqualTo("NEEDS_REVIEW");

        String correction = """
                {"version":%d,"reviewerId":"reviewer-1","shopName":"수정된 상점"}
                """.formatted(version);
        String correctedJson = mockMvc.perform(patch("/api/receipts/{id}/fields", id).cookie(sessionCookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentData.shopName").value("수정된 상점"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode corrected = objectMapper.readTree(correctedJson);

        String decision = """
                {"version":%d,"reviewerId":"reviewer-1","decision":"APPROVE","note":"증빙 확인"}
                """.formatted(corrected.path("version").asLong());
        mockMvc.perform(post("/api/receipts/{id}/decision", id).cookie(sessionCookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/receipts/{id}/audit-events", id).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItems(
                        "UPLOADED", "EXTRACTION_COMPLETED", "VALIDATION_COMPLETED",
                        "FIELDS_CORRECTED", "REVIEW_APPROVED")));
    }

    @Test
    void supportsShopNameCorrectionAndClearing() throws Exception {
        JsonNode accepted = upload("receipt.png", png(800, 1200), null, 202);
        long receiptId = accepted.path("receiptId").asLong();
        process(receiptId);
        JsonNode receipt = getReceipt(receiptId);

        String correction = """
                {"version":%d,"shopName":"수정된 상점"}
                """.formatted(receipt.path("version").asLong());
        String response = mockMvc.perform(patch("/api/receipts/{id}/fields", receiptId)
                        .cookie(sessionCookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentData.shopName").value("수정된 상점"))
                .andExpect(jsonPath("$.originalData.shopName").value("테스트상점"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode corrected = objectMapper.readTree(response);

        String clear = """
                {"version":%d,"clearFields":["shopName"]}
                """.formatted(corrected.path("version").asLong());
        response = mockMvc.perform(patch("/api/receipts/{id}/fields", receiptId)
                        .cookie(sessionCookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(clear))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalData.shopName").value("테스트상점"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode cleared = objectMapper.readTree(response);
        assertThat(cleared.path("currentData").path("shopName").isNull()).isTrue();
        assertThat(cleared.path("currentData").path("totalAmount").decimalValue())
                .isEqualByComparingTo("12000");
    }

    @Test
    void rejectsStaleReviewerVersion() throws Exception {
        JsonNode accepted = upload("missing-shop-name.png", png(800, 1200), null, 202);
        process(accepted.path("receiptId").asLong());
        JsonNode uploaded = getReceipt(accepted.path("receiptId").asLong());
        long version = uploaded.path("version").asLong();
        String body = """
                {"version":%d,"reviewerId":"reviewer-1","shopName":"첫 수정"}
                """.formatted(version);
        mockMvc.perform(patch("/api/receipts/{id}/fields", uploaded.path("id").asText()).cookie(sessionCookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/receipts/{id}/fields", uploaded.path("id").asText()).cookie(sessionCookie).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    // 같은 중복 요청 방지 키로 재전송하면 최초 영수증을 반환하는지 검증한다.
    void idempotencyKeyReplaysSameReceipt() throws Exception {
        byte[] image = png(800, 1200);
        JsonNode first = upload("receipt.png", image, "upload-001", 202);
        JsonNode second = upload("receipt.png", image, "upload-001", 200);

        assertThat(second.path("receiptId").asText()).isEqualTo(first.path("receiptId").asText());
        assertThat(receiptRepository.count()).isOne();
        assertThat(jobRepository.count()).isOne();
    }

    @Test
    void duplicateImageBecomesReviewTargetWithoutCreatingSecondRow() throws Exception {
        byte[] image = png(800, 1200);
        JsonNode first = upload("first.png", image, null, 202);
        process(first.path("receiptId").asLong());
        JsonNode second = upload("second.png", image, null, 200);

        assertThat(second.path("receiptId").asText()).isEqualTo(first.path("receiptId").asText());
        assertThat(getReceipt(first.path("receiptId").asLong()).path("status").asText())
                .isEqualTo("NEEDS_REVIEW");
        assertThat(receiptRepository.count()).isOne();
        assertThat(jobRepository.count()).isOne();
    }

    @Test
    void concurrentDuplicateUploadsLeaveOneReceipt() throws Exception {
        byte[] image = png(800, 1200);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<UploadResult>> futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(index -> executor.submit(() -> {
                        start.await();
                        return uploadService.upload("company-concurrent", null, "receipt-" + index + ".png",
                                "image/png", image, null);
                    })).toList();
            start.countDown();
            Set<Object> ids = new java.util.HashSet<>();
            for (Future<UploadResult> future : futures) ids.add(future.get(10, TimeUnit.SECONDS).receipt().id());

            assertThat(ids).hasSize(1);
            assertThat(receiptRepository.count()).isOne();
            assertThat(jobRepository.count()).isOne();
            assertThat(receiptRepository.findAll().get(0).status()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode upload(String fileName, byte[] bytes, String idempotencyKey, int expectedStatus) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "image/png", bytes);
        var request = multipart("/api/receipts").file(file).header("X-Company-Id", "company-a");
        // 실제 클라이언트처럼 재시도 요청에 동일한 중복 요청 방지 키를 전달한다.
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        String json = mockMvc.perform(request.cookie(submitterCookie).with(csrf()))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private JsonNode getReceipt(long receiptId) throws Exception {
        String json = mockMvc.perform(get("/api/receipts/{id}", receiptId).cookie(sessionCookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
    }

    private void process(long receiptId) {
        ClaimedReceiptJob claimedJob = claimService.claimAvailable(
                        "api-integration-test", 10, Duration.ofSeconds(30)).stream()
                .filter(job -> job.receiptId().equals(receiptId))
                .findFirst()
                .orElseThrow();
        extractionProcessor.process(claimedJob);
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
