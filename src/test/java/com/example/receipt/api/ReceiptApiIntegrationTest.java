package com.example.receipt.api;

import com.example.receipt.service.ReceiptUploadService;
import com.example.receipt.service.model.UploadResult;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReceiptApiIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ReceiptUploadService uploadService;
    @Autowired ReceiptRepository receiptRepository;
    @Autowired AuditEventRepository auditRepository;
    @Autowired IdempotencyRecordRepository idempotencyRepository;

    @BeforeEach
    void cleanDatabase() {
        auditRepository.deleteAll();
        idempotencyRepository.deleteAll();
        receiptRepository.deleteAll();
    }

    @Test
    void uploadsAndAutoApprovesClearReceipt() throws Exception {
        JsonNode response = upload("receipt.png", png(800, 1200), null, 201);

        assertThat(response.path("status").asText()).isEqualTo("AUTO_APPROVED");
        assertThat(response.path("originalData").path("merchant").asText()).isEqualTo("테스트상점");
        assertThat(response.path("currentData").path("totalAmount").decimalValue()).isEqualByComparingTo("12000");
        assertThat(response.path("file").path("sha256").asText()).hasSize(64);
    }

    @Test
    void routesLowResolutionAndUnreadableImagesSafely() throws Exception {
        assertThat(upload("small.png", png(200, 300), null, 201).path("status").asText())
                .isEqualTo("NEEDS_RECAPTURE");
        assertThat(upload("broken.png", new byte[]{1, 2, 3, 4}, null, 201).path("status").asText())
                .isEqualTo("UNREADABLE");
    }

    @Test
    void supportsFieldCorrectionFinalDecisionAndAuditTrail() throws Exception {
        JsonNode uploaded = upload("missing-merchant.png", png(800, 1200), null, 201);
        String id = uploaded.path("id").asText();
        long version = uploaded.path("version").asLong();
        assertThat(uploaded.path("status").asText()).isEqualTo("NEEDS_REVIEW");

        String correction = """
                {"version":%d,"reviewerId":"reviewer-1","merchant":"수정된 상점"}
                """.formatted(version);
        String correctedJson = mockMvc.perform(patch("/api/receipts/{id}/fields", id)
                        .contentType(MediaType.APPLICATION_JSON).content(correction))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentData.merchant").value("수정된 상점"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode corrected = objectMapper.readTree(correctedJson);

        String decision = """
                {"version":%d,"reviewerId":"reviewer-1","decision":"APPROVE","note":"증빙 확인"}
                """.formatted(corrected.path("version").asLong());
        mockMvc.perform(post("/api/receipts/{id}/decision", id)
                        .contentType(MediaType.APPLICATION_JSON).content(decision))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/receipts/{id}/audit-events", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].action").value(org.hamcrest.Matchers.hasItems(
                        "UPLOADED", "EXTRACTION_COMPLETED", "VALIDATION_COMPLETED",
                        "FIELDS_CORRECTED", "REVIEW_APPROVED")));
    }

    @Test
    void rejectsStaleReviewerVersion() throws Exception {
        JsonNode uploaded = upload("missing-merchant.png", png(800, 1200), null, 201);
        String body = """
                {"version":0,"reviewerId":"reviewer-1","merchant":"첫 수정"}
                """;
        mockMvc.perform(patch("/api/receipts/{id}/fields", uploaded.path("id").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/receipts/{id}/fields", uploaded.path("id").asText())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    // 같은 중복 요청 방지 키로 재전송하면 최초 영수증을 반환하는지 검증한다.
    void idempotencyKeyReplaysSameReceipt() throws Exception {
        byte[] image = png(800, 1200);
        JsonNode first = upload("receipt.png", image, "upload-001", 201);
        JsonNode second = upload("receipt.png", image, "upload-001", 200);

        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(receiptRepository.count()).isOne();
    }

    @Test
    void duplicateImageBecomesReviewTargetWithoutCreatingSecondRow() throws Exception {
        byte[] image = png(800, 1200);
        JsonNode first = upload("first.png", image, null, 201);
        JsonNode second = upload("second.png", image, null, 200);

        assertThat(second.path("id").asText()).isEqualTo(first.path("id").asText());
        assertThat(second.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(receiptRepository.count()).isOne();
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
                                "image/png", image);
                    })).toList();
            start.countDown();
            Set<Object> ids = new java.util.HashSet<>();
            for (Future<UploadResult> future : futures) ids.add(future.get(10, TimeUnit.SECONDS).receipt().id());

            assertThat(ids).hasSize(1);
            assertThat(receiptRepository.count()).isOne();
            assertThat(receiptRepository.findAll().getFirst().status().name()).isEqualTo("NEEDS_REVIEW");
        } finally {
            executor.shutdownNow();
        }
    }

    private JsonNode upload(String fileName, byte[] bytes, String idempotencyKey, int expectedStatus) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "image/png", bytes);
        var request = multipart("/api/receipts").file(file).header("X-Company-Id", "company-a");
        // 실제 클라이언트처럼 재시도 요청에 동일한 중복 요청 방지 키를 전달한다.
        if (idempotencyKey != null) request.header("Idempotency-Key", idempotencyKey);
        String json = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(json);
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
