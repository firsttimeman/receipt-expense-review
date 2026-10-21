package com.example.receipt;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.security.EmployeePrincipal;
import com.example.receipt.domain.extraction.dto.ClaimedReceiptJob;
import com.example.receipt.domain.extraction.dto.ExtractionResult;
import com.example.receipt.domain.extraction.model.ExtractionJobStatus;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.extraction.service.ReceiptExtractionLifecycleService;
import com.example.receipt.domain.extraction.service.ReceiptJobClaimService;
import com.example.receipt.domain.receipt.dto.ReceiptResponse;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.model.ReceiptData;
import com.example.receipt.domain.receipt.repository.AuditEventRepository;
import com.example.receipt.domain.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import com.example.receipt.domain.receipt.service.ReceiptPersistenceService;
import com.example.receipt.domain.receipt.service.ReceiptQueryService;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.aop.framework.Advised;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReceiptDetailConsistencyIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired EmployeeRepository employees;
    @Autowired ReceiptRepository receipts;
    @Autowired ReceiptExtractionJobRepository jobs;
    @Autowired AuditEventRepository audits;
    @Autowired IdempotencyRecordRepository idempotencyRecords;
    @Autowired ReceiptPersistenceService persistenceService;
    @Autowired ReceiptQueryService queryService;
    @Autowired ReceiptJobClaimService claimService;
    @Autowired ReceiptExtractionLifecycleService lifecycleService;

    private Employee owner;
    private Long receiptId;
    private ClaimedReceiptJob claimedJob;

    @BeforeEach
    void prepareProcessingReceipt() {
        audits.deleteAll();
        idempotencyRecords.deleteAll();
        jobs.deleteAll();
        receipts.deleteAll();
        employees.deleteAll();

        owner = new Employee("snapshot-owner", "조회 테스트 직원", EmployeeRole.EMPLOYEE);
        owner.setPassword("test-password-hash");
        employees.saveAndFlush(owner);

        Receipt receipt = new Receipt("internal", "a".repeat(64), "receipt.png", "image/png",
                100, null, null, List.of(), Instant.now());
        receipt.submittedBy(owner.id());
        persistenceService.createQueued(receipt, "test-image", null, List.of());
        receiptId = receipt.id();
        claimedJob = claimService.claimAvailable("snapshot-worker", 1, Duration.ofMinutes(1)).get(0);
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void separateServiceCallsCanMixReceiptAndJobFromDifferentTimes() {
        EmployeePrincipal principal = EmployeePrincipal.forSession(owner);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

        // 기존 컨트롤러의 두 서비스 호출 사이에서 Worker가 커밋하는 상황을 재현합니다.
        Receipt receipt = queryService.getReceipt(receiptId);
        completeExtraction();
        ReceiptResponse response = ReceiptResponse.from(receipt, queryService.getExtractionJob(receiptId).status());

        assertThat(response.status()).isNull();
        assertThat(response.jobStatus()).isEqualTo(ExtractionJobStatus.COMPLETED);
    }

    @Test
    void detailKeepsOneSnapshotWhenWorkerCommitsBetweenQueries() throws Exception {
        // 별도 격리 수준을 지정하지 않고 MySQL 연결의 기본값을 사용하는지 확인합니다.
        assertThat(jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class))
                .isEqualTo("REPEATABLE-READ");

        ExecutorService worker = Executors.newSingleThreadExecutor();
        AtomicBoolean completionTriggered = new AtomicBoolean();
        AtomicReference<String> readIsolation = new AtomicReference<>();
        // 테스트에서만 실제 Repository 호출 앞에 실행 순서를 제어하는 코드를 끼웁니다.
        Advised jobProxy = (Advised) jobs;
        MethodInterceptor completeBeforeJobQuery = invocation -> {
            if (invocation.getMethod().getName().equals("findByReceiptId")
                    && receiptId.equals(invocation.getArguments()[0])
                    && completionTriggered.compareAndSet(false, true)) {
                readIsolation.set(jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class));
                // 영수증 SELECT 이후, 작업 SELECT 직전에 별도 스레드/트랜잭션으로 커밋합니다.
                worker.submit(this::completeExtraction).get(10, TimeUnit.SECONDS);
            }
            return invocation.proceed();
        };
        jobProxy.addAdvice(0, completeBeforeJobQuery);
        try {
            mvc.perform(get("/api/receipts/{id}", receiptId).with(user(EmployeePrincipal.forSession(owner))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").isEmpty())
                    .andExpect(jsonPath("$.jobStatus").value("PROCESSING"))
                    .andExpect(jsonPath("$.currentData").isEmpty());

            assertThat(completionTriggered).isTrue();
            assertThat(readIsolation.get()).isEqualTo("REPEATABLE-READ");

            // 다음 HTTP 요청은 새 트랜잭션이므로 완료된 영수증과 작업을 함께 읽습니다.
            mvc.perform(get("/api/receipts/{id}", receiptId).with(user(EmployeePrincipal.forSession(owner))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("AUTO_APPROVED"))
                    .andExpect(jsonPath("$.jobStatus").value("COMPLETED"))
                    .andExpect(jsonPath("$.currentData.shopName").value("테스트상점"));
        } finally {
            jobProxy.removeAdvice(completeBeforeJobQuery);
            worker.shutdownNow();
            assertThat(worker.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void completeExtraction() {
        ReceiptData data = new ReceiptData("테스트상점", LocalDate.of(2026, 1, 15),
                new BigDecimal("12000"), null, "신용카드", List.of());
        lifecycleService.completeExtraction(claimedJob,
                new ExtractionResult(data, "test", "snapshot-test"), Instant.now());
    }
}
