package com.example.receipt;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.service.EmployeeService;
import com.example.receipt.domain.extraction.entity.ReceiptExtractionJob;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.model.ReceiptData;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import com.example.receipt.domain.receipt.model.ReviewDecision;
import com.example.receipt.domain.receipt.repository.AuditEventRepository;
import com.example.receipt.domain.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 실제 DB·Redis·S3 호환 서버와 HTTP로 계정 발급부터 검수까지의 흐름을 검증합니다. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReceiptListingWorkflowIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> S3_SERVER = new GenericContainer<>("adobe/s3mock:5.2.3")
            .withExposedPorts(9090)
            .waitingFor(Wait.forListeningPort());

    private static final String BUCKET = "receipt-listing-test";

    private static final String ACCESS_KEY = "receipt-listing-test";

    private static final String SECRET_KEY = "receipt-listing-test-secret";

    private static final String PASSWORD = "listing-workflow-password";

    private static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-01T03:00:00Z");

    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ServletWebServerApplicationContext server;

    private String previousAccessKey;

    private String previousSecretKey;

    private String previousSessionToken;

    private Employee admin;

    private Employee employee;

    private Employee other;

    private Employee reviewer;

    private int imageSequence;

    @BeforeAll
    void startApplication() {
        previousAccessKey = System.getProperty("aws.accessKeyId");
        previousSecretKey = System.getProperty("aws.secretAccessKey");
        previousSessionToken = System.getProperty("aws.sessionToken");
        System.setProperty("aws.accessKeyId", ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", SECRET_KEY);
        System.clearProperty("aws.sessionToken");

        try (S3Client client = S3Client.builder()
                .endpointOverride(s3Endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()) {
            client.createBucket(request -> request.bucket(BUCKET));
        }
        server = startServer(false);
    }

    @AfterAll
    void stopApplication() {
        try {
            if (server != null) server.close();
        } finally {
            restoreProperty("aws.accessKeyId", previousAccessKey);
            restoreProperty("aws.secretAccessKey", previousSecretKey);
            restoreProperty("aws.sessionToken", previousSessionToken);
        }
    }

    @BeforeEach
    void fixtures() {
        server.getBean(AuditEventRepository.class).deleteAll();
        server.getBean(IdempotencyRecordRepository.class).deleteAll();
        server.getBean(ReceiptExtractionJobRepository.class).deleteAll();
        server.getBean(ReceiptRepository.class).deleteAll();
        server.getBean(EmployeeRepository.class).deleteAll();
        imageSequence = 0;

        server.getBean(EmployeeService.class).bootstrap("list-admin", PASSWORD);
        admin = server.getBean(EmployeeRepository.class).findByLoginId("list-admin").orElseThrow();
        employee = employee("list-employee", EmployeeRole.EMPLOYEE);
        other = employee("list-other", EmployeeRole.EMPLOYEE);
        reviewer = employee("list-reviewer", EmployeeRole.REVIEWER);
    }

    @ParameterizedTest
    @EnumSource(EmployeeRole.class)
    void ownListUsesAuthenticatedOwnerForEveryRoleIncludingTotalCount(EmployeeRole role) throws Exception {
        Employee owner = switch (role) {
            case EMPLOYEE -> employee;
            case REVIEWER -> reviewer;
            case ADMIN -> admin;
        };
        long ready = receipt(owner, ReceiptStatus.APPROVED, SUBMITTED_AT).id();
        long queued = receipt(owner, null, SUBMITTED_AT).id();
        receipt(other, ReceiptStatus.NEEDS_REVIEW, SUBMITTED_AT);
        receipt(other, ReceiptStatus.REJECTED, SUBMITTED_AT);
        receipt(null, ReceiptStatus.NEEDS_REVIEW, SUBMITTED_AT);

        Browser browser = login(owner);
        JsonNode page = browser.page(server, "/api/receipts?ownerEmployeeId=" + other.id()
                + "&employeeId=" + other.id() + "&companyId=historical");
        assertThat(ids(page)).containsExactly(queued, ready);
        assertThat(page.path("totalElements").asLong()).isEqualTo(2);
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(20);
        assertThat(page.path("totalPages").asInt()).isOne();
        assertThat(page.path("hasNext").asBoolean()).isFalse();
        for (JsonNode row : page.path("content")) {
            assertThat(row.path("ownerEmployeeId").asLong()).isEqualTo(owner.id());
            assertThat(row.has("originalData")).isFalse();
            assertThat(row.has("ruleResults")).isFalse();
        }
        assertThat(page.path("content").get(0).path("status").isNull()).isTrue();
        assertThat(page.path("content").get(0).path("jobStatus").asText()).isEqualTo("QUEUED");
        assertThat(page.path("content").get(1).path("shopName").asText()).isEqualTo("목록 테스트 상점");
    }

    @Test
    void anonymousUsersAndEmployeesCannotAccessReviewQueue() throws Exception {
        Browser anonymous = new Browser();
        assertThat(anonymous.get(server, "/api/receipts").statusCode()).isEqualTo(401);
        assertThat(anonymous.get(server, "/api/receipts/review-queue").statusCode()).isEqualTo(401);
        assertThat(login(employee).get(server, "/api/receipts/review-queue").statusCode()).isEqualTo(403);
    }

    @ParameterizedTest
    @EnumSource(value = EmployeeRole.class, names = {"REVIEWER", "ADMIN"})
    void reviewQueueExcludesOwnLegacyProcessingAndFinishedReceipts(EmployeeRole role) throws Exception {
        Employee actor = role == EmployeeRole.ADMIN ? admin : reviewer;
        List<Long> pending = new ArrayList<>();
        for (ReceiptStatus status : ReceiptStatus.values()) {
            Receipt row = receipt(employee, status, SUBMITTED_AT);
            if (List.of(ReceiptStatus.NEEDS_REVIEW, ReceiptStatus.MANUAL_ENTRY,
                    ReceiptStatus.NEEDS_RECAPTURE, ReceiptStatus.UNREADABLE).contains(status)) {
                pending.add(row.id());
            }
        }
        receipt(employee, null, SUBMITTED_AT);
        receipt(actor, ReceiptStatus.NEEDS_REVIEW, SUBMITTED_AT);
        receipt(null, ReceiptStatus.NEEDS_REVIEW, SUBMITTED_AT);

        Browser browser = login(actor);
        JsonNode page = browser.page(server, "/api/receipts/review-queue");
        assertThat(ids(page)).containsExactlyElementsOf(pending.stream().sorted(java.util.Comparator.reverseOrder()).toList());
        assertThat(page.path("totalElements").asLong()).isEqualTo(4);
        assertThat(page.toString()).contains("FAILED"); // 추출 실패 후 수기 입력이 필요한 건도 보여야 합니다.
        JsonNode firstPage = browser.page(server, "/api/receipts/review-queue?size=2");
        assertThat(ids(firstPage)).containsExactlyElementsOf(ids(page).subList(0, 2));
        assertThat(firstPage.path("totalElements").asLong()).isEqualTo(4);
        assertThat(firstPage.path("totalPages").asInt()).isEqualTo(2);
        assertThat(firstPage.path("hasNext").asBoolean()).isTrue();
        JsonNode filtered = browser.page(server, "/api/receipts/review-queue?status=NEEDS_REVIEW");
        assertThat(filtered.path("totalElements").asLong()).isOne();
        assertThat(filtered.path("content").get(0).path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(ids(browser.page(server, "/api/receipts/review-queue?status=APPROVED"))).isEmpty();
    }

    @Test
    void paginationHasStableTieBreakingAndReturnsEmptyPagesWithoutLeakingCounts() throws Exception {
        long first = receipt(employee, ReceiptStatus.APPROVED, SUBMITTED_AT).id();
        long second = receipt(employee, ReceiptStatus.APPROVED, SUBMITTED_AT).id();
        long third = receipt(employee, ReceiptStatus.APPROVED, SUBMITTED_AT).id();
        long latest = receipt(employee, ReceiptStatus.APPROVED, SUBMITTED_AT.plusSeconds(1)).id();
        receipt(other, ReceiptStatus.APPROVED, SUBMITTED_AT.plusSeconds(2));

        Browser browser = login(employee);
        JsonNode page0 = browser.page(server, "/api/receipts?page=0&size=2");
        assertThat(ids(page0)).containsExactly(latest, third);
        assertThat(page0.path("totalElements").asLong()).isEqualTo(4);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.path("hasNext").asBoolean()).isTrue();
        JsonNode page1 = browser.page(server, "/api/receipts?page=1&size=2");
        assertThat(ids(page1)).containsExactly(second, first);
        assertThat(page1.path("hasNext").asBoolean()).isFalse();
        JsonNode outside = browser.page(server, "/api/receipts?page=99&size=2");
        assertThat(ids(outside)).isEmpty();
        assertThat(outside.path("totalElements").asLong()).isEqualTo(4);
        assertThat(outside.path("hasNext").asBoolean()).isFalse();

        JsonNode empty = login(reviewer).page(server, "/api/receipts");
        assertThat(ids(empty)).isEmpty();
        assertThat(empty.path("totalPages").asInt()).isZero();
        assertThat(empty.path("totalElements").asLong()).isZero();
    }

    @Test
    void submissionDateUsesInclusiveKoreanCalendarDaysAndCombinesWithStatus() throws Exception {
        Instant start = Instant.parse("2026-09-30T15:00:00Z");
        Instant end = Instant.parse("2026-10-01T15:00:00Z");
        long before = receipt(employee, ReceiptStatus.NEEDS_REVIEW, start.minusNanos(1000)).id();
        long atStart = receipt(employee, ReceiptStatus.NEEDS_REVIEW, start).id();
        long approved = receipt(employee, ReceiptStatus.APPROVED, start.plusSeconds(1)).id();
        long atLast = receipt(employee, ReceiptStatus.NEEDS_REVIEW, end.minusNanos(1000)).id();
        long after = receipt(employee, ReceiptStatus.NEEDS_REVIEW, end).id();
        receipt(other, ReceiptStatus.NEEDS_REVIEW, start.plusSeconds(2));

        Browser browser = login(employee);
        String range = "from=2026-10-01&to=2026-10-01";
        assertThat(ids(browser.page(server, "/api/receipts?" + range))).containsExactly(atLast, approved, atStart);
        assertThat(ids(browser.page(server, "/api/receipts?" + range + "&status=NEEDS_REVIEW")))
                .containsExactly(atLast, atStart);
        JsonNode filteredPage = browser.page(server, "/api/receipts?" + range + "&status=NEEDS_REVIEW&size=1");
        assertThat(ids(filteredPage)).containsExactly(atLast);
        assertThat(filteredPage.path("totalElements").asLong()).isEqualTo(2);
        assertThat(filteredPage.path("totalPages").asInt()).isEqualTo(2);
        assertThat(ids(browser.page(server, "/api/receipts?to=2026-10-01"))).containsExactly(atLast, approved, atStart, before);
        assertThat(ids(browser.page(server, "/api/receipts?from=2026-10-01"))).containsExactly(after, atLast, approved, atStart);
        Browser inspector = login(reviewer);
        JsonNode queue = inspector.page(server, "/api/receipts/review-queue?" + range);
        assertThat(queue.path("totalElements").asLong()).isEqualTo(3);
        assertThat(ids(queue)).contains(atLast, atStart).doesNotContain(before, after, approved);
        JsonNode queuePage = inspector.page(server,
                "/api/receipts/review-queue?" + range + "&status=NEEDS_REVIEW&size=1");
        assertThat(ids(queuePage)).containsExactly(atLast);
        assertThat(queuePage.path("totalElements").asLong()).isEqualTo(3);
        assertThat(queuePage.path("totalPages").asInt()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidFiltersAndPagination() throws Exception {
        Browser browser = login(reviewer);
        List<String> invalid = List.of("page=-1", "size=0", "size=101", "page=abc", "size=abc",
                "page=2147483647&size=100", "status=UNKNOWN", "from=not-a-date", "to=2026-02-30",
                "from=2026-10-02&to=2026-10-01", "from=0000-01-01", "to=%2B10000-01-01");
        for (String path : List.of("/api/receipts", "/api/receipts/review-queue")) {
            for (String query : invalid) {
                HttpResponse<String> response = browser.get(server, path + "?" + query);
                assertThat(response.statusCode()).as("%s?%s: %s", path, query, response.body()).isEqualTo(400);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(ReviewDecision.class)
    void issuedEmployeeCanSubmitAndSeeAnotherReviewersDecision(ReviewDecision decision) throws Exception {
        Browser administrator = login(admin);
        JsonNode issuedEmployee = issue(administrator, "workflow-employee", EmployeeRole.EMPLOYEE);
        JsonNode issuedReviewer = issue(administrator, "workflow-reviewer", EmployeeRole.REVIEWER);
        Browser submitter = activateAndLogin(issuedEmployee, "workflow-employee");
        Browser inspector = activateAndLogin(issuedReviewer, "workflow-reviewer");

        HttpResponse<String> upload = submitter.upload(server);
        assertThat(upload.statusCode()).as(upload.body()).isEqualTo(202);
        long id = json.readTree(upload.body()).path("receiptId").asLong();
        JsonNode waiting = submitter.page(server, "/api/receipts");
        assertThat(ids(waiting)).containsExactly(id);
        assertThat(waiting.path("content").get(0).path("jobStatus").asText()).isEqualTo("QUEUED");
        assertThat(ids(login(other).page(server, "/api/receipts"))).isEmpty();
        assertThat(login(other).get(server, "/api/receipts/" + id).statusCode()).isEqualTo(404);

        // 별도 서버의 Worker가 같은 Redis 세션과 S3 버킷으로 처리를 이어갑니다.
        try (var worker = startServer(true)) {
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                assertThat(ids(submitter.page(worker, "/api/receipts?status=NEEDS_REVIEW"))).containsExactly(id);
            });
            JsonNode detail = submitter.page(worker, "/api/receipts/" + id);
            assertThat(detail.path("jobStatus").asText()).isEqualTo("COMPLETED");
            HttpResponse<String> correction = submitter.change(worker, "PATCH", "/api/receipts/" + id + "/fields",
                    Map.of("version", detail.path("version").asLong(), "shopName", "수정된 상점"));
            assertThat(correction.statusCode()).as(correction.body()).isEqualTo(200);
            JsonNode corrected = json.readTree(correction.body());
            assertThat(corrected.path("status").asText()).isEqualTo("NEEDS_REVIEW");
            assertThat(corrected.path("currentData").path("shopName").asText()).isEqualTo("수정된 상점");
            assertThat(corrected.path("ruleResults")).allSatisfy(rule ->
                    assertThat(rule.path("outcome").asText()).isNotEqualTo("FAIL"));
            assertThat(submitter.get(worker, "/api/receipts/review-queue").statusCode()).isEqualTo(403);

            JsonNode queue = inspector.page(server, "/api/receipts/review-queue?status=NEEDS_REVIEW");
            assertThat(ids(queue)).containsExactly(id);
            HttpResponse<String> result = inspector.change(worker, "POST", "/api/receipts/" + id + "/decision",
                    Map.of("version", queue.path("content").get(0).path("version").asLong(),
                            "decision", decision.name(), "note", "증빙 확인 완료"));
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            String finalStatus = decision == ReviewDecision.APPROVE ? "APPROVED" : "REJECTED";
            assertThat(json.readTree(result.body()).path("status").asText()).isEqualTo(finalStatus);
            assertThat(ids(inspector.page(server, "/api/receipts/review-queue"))).isEmpty();
            assertThat(ids(submitter.page(server, "/api/receipts?status=" + finalStatus))).containsExactly(id);

            JsonNode events = submitter.page(server, "/api/receipts/" + id + "/audit-events");
            String reviewerActor = "employee:" + issuedReviewer.path("employee").path("id").asLong();
            String submitterActor = "employee:" + issuedEmployee.path("employee").path("id").asLong();
            assertThat(events.toString()).contains("EXTRACTION_COMPLETED", "FIELDS_CORRECTED", "REVIEW_" + finalStatus);
            for (JsonNode event : events) {
                if (event.path("action").asText().startsWith("REVIEW_")) {
                    assertThat(event.path("actor").asText()).isEqualTo(reviewerActor);
                }
                if (event.path("action").asText().equals("UPLOADED")) {
                    assertThat(event.path("actor").asText()).isEqualTo(submitterActor);
                }
            }
            assertThat(submitter.change(server, "POST", "/api/auth/logout", Map.of()).statusCode()).isEqualTo(204);
            assertThat(submitter.get(worker, "/api/receipts").statusCode()).isEqualTo(401);
        }
    }

    private Employee employee(String loginId, EmployeeRole role) {
        Employee result = new Employee(loginId, loginId, role);
        result.setPassword(HASH);
        return server.getBean(EmployeeRepository.class).saveAndFlush(result);
    }

    private Receipt receipt(Employee owner, ReceiptStatus status, Instant createdAt) {
        String hash = String.format("%064x", ++imageSequence);
        ReceiptData data = status == null ? null : new ReceiptData("목록 테스트 상점", LocalDate.of(2026, 1, 15),
                new BigDecimal("12000"), null, "신용카드", List.of());
        Receipt row = new Receipt("internal", hash, "receipt.png", "image/png", 100,
                data, status, List.of(), createdAt);
        if (owner != null) row.submittedBy(owner.id());
        row = server.getBean(ReceiptRepository.class).saveAndFlush(row);
        ReceiptExtractionJob job = new ReceiptExtractionJob(row.id(), "fixture/" + hash, createdAt);
        if (status != null) {
            job.claim("fixture-worker", "fixture-token", createdAt, createdAt.plusSeconds(30));
            if (status == ReceiptStatus.MANUAL_ENTRY) {
                job.fail("fixture-worker", "fixture-token", "SYNTHETIC_FAILURE", "synthetic", createdAt);
            } else {
                job.complete("fixture-worker", "fixture-token", createdAt);
            }
        }
        server.getBean(ReceiptExtractionJobRepository.class).saveAndFlush(job);
        return row;
    }

    private Browser login(Employee person) throws Exception {
        Browser browser = new Browser();
        assertThat(browser.change(server, "POST", "/api/auth/login",
                Map.of("loginId", person.loginId(), "password", PASSWORD)).statusCode()).isEqualTo(204);
        return browser;
    }

    private JsonNode issue(Browser adminBrowser, String loginId, EmployeeRole role) throws Exception {
        HttpResponse<String> response = adminBrowser.change(server, "POST", "/api/employees",
                Map.of("loginId", loginId, "name", loginId, "role", role.name()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json.readTree(response.body());
    }

    private Browser activateAndLogin(JsonNode issued, String loginId) throws Exception {
        Browser browser = new Browser();
        assertThat(browser.change(server, "POST", "/api/auth/password",
                Map.of("token", issued.path("setupToken").asText(), "password", PASSWORD)).statusCode()).isEqualTo(204);
        assertThat(browser.change(server, "POST", "/api/auth/login",
                Map.of("loginId", loginId, "password", PASSWORD)).statusCode()).isEqualTo(204);
        return browser;
    }

    private List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(row -> ids.add(row.path("id").asLong()));
        return ids;
    }

    private ServletWebServerApplicationContext startServer(boolean worker) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(ReceiptApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(), "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.datasource.hikari.maximum-pool-size=3", "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.session.redis.namespace=receipt:listing-workflow:session", "--server.servlet.session.cookie.secure=false",
                "--receipt.storage.s3.bucket=" + BUCKET, "--receipt.storage.s3.region=us-east-1",
                "--receipt.storage.s3.endpoint=" + s3Endpoint(), "--receipt.storage.s3.path-style-access=true",
                "--receipt.storage.s3.prefix=listing-workflow",
                "--receipt.extractor.provider=fake", "--receipt.worker.enabled=" + worker,
                "--receipt.worker.poll-delay-millis=100", "--receipt.worker.concurrency=1", "--receipt.worker.batch-size=1",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private URI s3Endpoint() {
        return URI.create("http://" + S3_SERVER.getHost() + ":" + S3_SERVER.getMappedPort(9090));
    }

    private void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private class Browser {
        private String cookie;

        private HttpResponse<String> get(ServletWebServerApplicationContext target, String path) throws Exception {
            return send(target, "GET", path, new byte[0], null, "application/json");
        }

        private JsonNode page(ServletWebServerApplicationContext target, String path) throws Exception {
            HttpResponse<String> response = get(target, path);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return json.readTree(response.body());
        }

        private HttpResponse<String> change(ServletWebServerApplicationContext target, String method, String path, Object body) throws Exception {
            return send(target, method, path, json.writeValueAsBytes(body), page(target, "/api/auth/csrf"), "application/json");
        }

        private HttpResponse<String> upload(ServletWebServerApplicationContext target) throws Exception {
            var image = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
            var png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            String boundary = "receiptWorkflowBoundary";
            var body = new ByteArrayOutputStream();
            body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"missing-shop-name.png\"\r\nContent-Type: image/png\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            body.write(png.toByteArray());
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return send(target, "POST", "/api/receipts", body.toByteArray(), page(target, "/api/auth/csrf"),
                    "multipart/form-data; boundary=" + boundary);
        }

        private HttpResponse<String> send(ServletWebServerApplicationContext target, String method, String path,
                                          byte[] body, JsonNode csrf, String contentType) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + target.getWebServer().getPort() + path))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            if (cookie != null) request.header("Cookie", cookie);
            if (csrf != null) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                HttpCookie parsed = HttpCookie.parse(header).get(0);
                if (parsed.getName().equals("JSESSIONID")) {
                    cookie = parsed.getMaxAge() == 0 ? null : "JSESSIONID=" + parsed.getValue();
                }
            }
            return response;
        }
    }
}
