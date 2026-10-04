package com.example.receipt;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.service.EmployeeService;
import com.example.receipt.domain.employee.dto.SetPasswordRequest;
import com.example.receipt.domain.receipt.repository.*;
import com.example.receipt.domain.receipt.service.ReceiptUploadService;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.extraction.service.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class EmployeeAuthorizationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("receipt.worker.enabled", () -> "false");
        r.add("receipt.extractor.provider", () -> "fake");
    }
    private static final String PASSWORD = "synthetic-password-123";
    private static final String HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EmployeeRepository employees;
    @Autowired EmployeeService employeeService;
    @Autowired ReceiptRepository receipts;
    @Autowired ReceiptExtractionJobRepository jobs;
    @Autowired AuditEventRepository audits;
    @Autowired IdempotencyRecordRepository keys;
    @Autowired ReceiptUploadService uploads;
    @Autowired ReceiptJobClaimService claims;
    @Autowired ReceiptExtractionProcessor processor;
    Employee admin, a, b, reviewer;

    @BeforeEach void fixtures() {
        audits.deleteAll(); keys.deleteAll(); jobs.deleteAll(); receipts.deleteAll(); employees.deleteAll();
        admin = employee("admin", EmployeeRole.ADMIN);
        a = employee("employee-a", EmployeeRole.EMPLOYEE);
        b = employee("employee-b", EmployeeRole.EMPLOYEE);
        reviewer = employee("reviewer", EmployeeRole.REVIEWER);
    }
    private Employee employee(String login, EmployeeRole role) {
        var e = new Employee(login, login, role); e.setPassword(HASH); return employees.saveAndFlush(e);
    }
    private Cookie login(Employee e) throws Exception { return login(e.loginId(), PASSWORD); }
    private Cookie login(String name, String password) throws Exception {
        return mvc.perform(loginRequest(name, password).with(csrf()))
                .andExpect(status().isNoContent()).andReturn().getResponse().getCookie("JSESSIONID");
    }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder loginRequest(String name, String password) throws Exception {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("loginId", name, "password", password)));
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private JsonNode issue(Cookie session, String name, String role, int status) throws Exception {
        return body(mvc.perform(post("/api/employees").cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("loginId", name, "name", name, "role", role))))
                .andExpect(status().is(status)).andReturn());
    }
    private byte[] png(int marker) throws Exception {
        var image = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, marker);
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
    private JsonNode upload(Cookie session, byte[] bytes, String key, int status) throws Exception {
        return upload(session, bytes, key, "missing-merchant.png", status);
    }
    private JsonNode upload(Cookie session, byte[] bytes, String key, String fileName, int status) throws Exception {
        var request = multipart("/api/receipts").file(new MockMultipartFile("file", fileName, "image/png", bytes))
                .cookie(session).with(csrf()).param("ownerEmployeeId", b.id().toString())
                .header("X-Employee-Id", b.id()).header("X-Company-Id", "untrusted-header").header("Idempotency-Key", key);
        return body(mvc.perform(request).andExpect(status().is(status)).andReturn());
    }
    private JsonNode detail(Cookie session, long id, int status) throws Exception {
        return body(mvc.perform(get("/api/receipts/{id}", id).cookie(session)).andExpect(status().is(status)).andReturn());
    }
    private void process(long id) {
        var job = claims.claimAvailable("auth-test", 20, Duration.ofSeconds(30)).stream().filter(j -> j.receiptId().equals(id)).findFirst().orElseThrow();
        processor.process(job);
    }
    private ResultActions correction(Cookie session, long id, long version) throws Exception {
        return mvc.perform(patch("/api/receipts/{id}/fields", id).cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+",\"merchant\":\"corrected\",\"reviewerId\":\"forged-user\"}"));
    }
    private ResultActions decision(Cookie session, long id, long version, String decision) throws Exception {
        return mvc.perform(post("/api/receipts/{id}/decision", id).cookie(session).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+",\"decision\":\""+decision+"\",\"reviewerId\":\"forged-user\"}"));
    }

    @Test void adminIssuesAccountsAndDatabaseRejectsDuplicateLogin() throws Exception {
        var session = login(admin);
        JsonNode issued = issue(session, "new-user", "EMPLOYEE", 201);
        assertThat(issued.path("setupToken").asText()).hasSize(43);
        assertThat(issued.toString()).doesNotContain("passwordHash", "setupTokenHash");
        issue(session, "new-user", "EMPLOYEE", 409);
        mvc.perform(post("/api/employees").cookie(login(a)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"bad-admin\",\"name\":\"bad\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/employees").cookie(login(reviewer)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"bad-admin\",\"name\":\"bad\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void setupLoginLogoutAndInvalidCredentials() throws Exception {
        String token = issue(login(admin), "invited", "EMPLOYEE", 201).path("setupToken").asText();
        mvc.perform(loginRequest("invited", PASSWORD).with(csrf())).andExpect(status().isUnauthorized());
        String request = json.writeValueAsString(Map.of("token", token, "password", PASSWORD));
        mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isNoContent());
        mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isBadRequest());
        assertThat(employees.findByLoginId("invited").orElseThrow().passwordHash()).startsWith("$2a$").isNotEqualTo(PASSWORD);
        var session = login("invited", PASSWORD);
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isOk()).andExpect(jsonPath("$.loginId").value("invited"));
        mvc.perform(loginRequest("invited", "wrong").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(loginRequest("unknown", PASSWORD).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").cookie(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").cookie(session)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void csrfIsRequiredIncludingLoginAndPasswordAndSessionRotatesOnLogin() throws Exception {
        var csrfResponse = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie anonymousSession = csrfResponse.getResponse().getCookie("JSESSIONID");
        JsonNode token = body(csrfResponse);
        mvc.perform(loginRequest(a.loginId(), PASSWORD).cookie(anonymousSession)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        Cookie authenticatedSession = mvc.perform(loginRequest(a.loginId(), PASSWORD).cookie(anonymousSession)
                        .header(token.path("headerName").asText(), token.path("token").asText()))
                .andExpect(status().isNoContent()).andReturn().getResponse().getCookie("JSESSIONID");
        assertThat(authenticatedSession.getValue()).isNotEqualTo(anonymousSession.getValue());
        mvc.perform(get("/api/auth/me").cookie(anonymousSession)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").cookie(authenticatedSession)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").cookie(authenticatedSession)
                .header(token.path("headerName").asText(), token.path("token").asText())).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").cookie(authenticatedSession)).andExpect(status().isOk());
    }

    @Test void inactiveAccountCannotLoginUseSessionOrSetPassword() throws Exception {
        var userSession = login(a); var adminSession = login(admin);
        mvc.perform(patch("/api/employees/{id}/active", a.id()).cookie(adminSession).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me").cookie(userSession)).andExpect(status().isUnauthorized())
                .andExpect(cookie().maxAge("JSESSIONID", 0));
        mvc.perform(loginRequest(a.loginId(), PASSWORD).with(csrf())).andExpect(status().isUnauthorized());
        var issued = issue(adminSession, "inactive-invite", "EMPLOYEE", 201);
        var e = employees.findById(issued.path("employee").path("id").asLong()).orElseThrow(); e.setActive(false); employees.saveAndFlush(e);
        mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", issued.path("setupToken").asText(), "password", PASSWORD))))
                .andExpect(status().isBadRequest());
    }

    @Test void expiredInvalidAndShortPasswordTokensDoNotSetPasswordOrLeakSecrets() throws Exception {
        var issued = issue(login(admin), "expired", "EMPLOYEE", 201);
        String token = issued.path("setupToken").asText();
        var e = employees.findByLoginId("expired").orElseThrow();
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        e.invite(hash, Instant.now().minusSeconds(5)); employees.saveAndFlush(e);
        for (String attempted : List.of(token, "invalid-token")) {
            mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("token", attempted, "password", PASSWORD))))
                    .andExpect(status().isBadRequest());
        }
        var response = mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token, "password", "secret"))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(token, "secret");
        String malformed = mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"password\": private-value}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(malformed).doesNotContain(token, "private-value");
        assertThat(employees.findByLoginId("expired").orElseThrow().passwordHash()).isNull();
    }

    @Test void passwordSetupIsConsumedOnlyOnceUnderConcurrency() throws Exception {
        String token = issue(login(admin), "concurrent-invite", "EMPLOYEE", 201).path("setupToken").asText();
        var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i=0; i<2; i++) futures.add(executor.submit(() -> {
                start.await();
                try { employeeService.setPassword(new SetPasswordRequest(token, PASSWORD)); return true; }
                catch (org.springframework.web.server.ResponseStatusException ex) { return false; }
            }));
            start.countDown();
            assertThat(List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally { executor.shutdownNow(); }
    }

    @Test void staleAccountUpdateCannotEraseAJustSetPassword() throws Exception {
        var issued = issue(login(admin), "versioned-invite", "EMPLOYEE", 201);
        var stale = employees.findByLoginId("versioned-invite").orElseThrow();
        employeeService.setPassword(new SetPasswordRequest(issued.path("setupToken").asText(), PASSWORD));
        stale.setActive(false);
        assertThatThrownBy(() -> employees.saveAndFlush(stale))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
        assertThat(employees.findByLoginId("versioned-invite").orElseThrow().passwordHash()).isNotNull();
        login("versioned-invite", PASSWORD);
    }

    @Test void employeeOwnsSubmissionAndOtherEmployeeCannotReadAuditOrModify() throws Exception {
        var sa = login(a); var sb = login(b);
        long id = upload(sa, png(1), "owner-test", 202).path("receiptId").asLong();
        assertThat(receipts.findById(id).orElseThrow().ownerEmployeeId()).isEqualTo(a.id());
        assertThat(detail(sa, id, 200).path("companyId").asText()).isEqualTo("internal");
        assertThat(audits.findByReceiptIdOrderByOccurredAtAsc(id).get(0).actor()).isEqualTo("employee:"+a.id());
        detail(sb, id, 404);
        mvc.perform(get("/api/receipts/{id}/audit-events", id).cookie(sb)).andExpect(status().isNotFound());
        correction(sb, id, 0).andExpect(status().isNotFound());
        mvc.perform(get("/api/receipts/{id}", id).header("X-Company-Id", "internal")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/receipts/{id}/audit-events", id)).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file", png(2))).with(csrf())).andExpect(status().isUnauthorized());
        process(id);
        long version = receipts.findById(id).orElseThrow().version();
        correction(sa, id, version).andExpect(status().isOk());
        decision(sa, id, version, "APPROVE").andExpect(status().isForbidden());
        decision(sa, id, version, "REJECT").andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(EmployeeRole.class)
    void submitterCorrectionRequiresAnotherReviewerEvenWhenAllRulesPass(EmployeeRole role) throws Exception {
        Employee owner = switch (role) {
            case EMPLOYEE -> a;
            case REVIEWER -> reviewer;
            case ADMIN -> admin;
        };
        var session = login(owner);
        long id = upload(session, png(60), "owner-correction", "over-limit.png", 202)
                .path("receiptId").asLong();
        process(id);
        JsonNode before = detail(session, id, 200);
        assertThat(before.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(before.path("currentData").path("totalAmount").decimalValue()).isEqualByComparingTo("500000");

        JsonNode corrected = body(mvc.perform(patch("/api/receipts/{id}/fields", id).cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("version", before.path("version").asLong(),
                                "totalAmount", 12000))))
                .andExpect(status().isOk()).andReturn());
        assertThat(corrected.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(corrected.path("currentData").path("totalAmount").decimalValue()).isEqualByComparingTo("12000");
        assertThat(corrected.path("originalData").path("totalAmount").decimalValue()).isEqualByComparingTo("500000");
        assertThat(corrected.path("ruleResults")).allSatisfy(rule ->
                assertThat(rule.path("outcome").asText()).isNotEqualTo("FAIL"));
        assertThat(audits.findByReceiptIdOrderByOccurredAtAsc(id))
                .noneMatch(event -> event.action().name().equals("REVIEW_APPROVED"));

        Employee approver = owner.id().equals(reviewer.id()) ? admin : reviewer;
        JsonNode approved = body(decision(login(approver), id, corrected.path("version").asLong(), "APPROVE")
                .andExpect(status().isOk()).andReturn());
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(audits.findByReceiptIdOrderByOccurredAtAsc(id))
                .filteredOn(event -> event.action().name().equals("REVIEW_APPROVED"))
                .singleElement().satisfies(event -> assertThat(event.actor()).isEqualTo("employee:" + approver.id()));
    }

    @ParameterizedTest
    @EnumSource(value = EmployeeRole.class, names = {"REVIEWER", "ADMIN"})
    void reviewerAndAdminCannotApproveOrRejectTheirOwnReceipt(EmployeeRole role) throws Exception {
        Employee owner = role == EmployeeRole.ADMIN ? admin : reviewer;
        var session = login(owner);
        long id = upload(session, png(61), "self-review", "over-limit.png", 202)
                .path("receiptId").asLong();
        process(id);
        long version = receipts.findById(id).orElseThrow().version();
        long auditCount = audits.count();

        decision(session, id, version, "APPROVE").andExpect(status().isForbidden());
        decision(session, id, version, "REJECT").andExpect(status().isForbidden());

        var unchanged = receipts.findById(id).orElseThrow();
        assertThat(unchanged.status()).isEqualTo(ReceiptStatus.NEEDS_REVIEW);
        assertThat(unchanged.version()).isEqualTo(version);
        assertThat(audits.count()).isEqualTo(auditCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clearFields", "lineItems"})
    void nullCorrectionElementsAreBadRequestsAndDoNotChangeReceipt(String field) throws Exception {
        var session = login(a);
        long id = upload(session, png(62), "invalid-elements", 202).path("receiptId").asLong();
        process(id);
        long version = receipts.findById(id).orElseThrow().version();
        long auditCount = audits.count();
        String request = "{\"version\":" + version + ",\"" + field + "\":[null]}";

        mvc.perform(patch("/api/receipts/{id}/fields", id).cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("요청 값이 올바르지 않습니다."));

        assertThat(receipts.findById(id).orElseThrow().version()).isEqualTo(version);
        assertThat(audits.count()).isEqualTo(auditCount);
    }

    @Test void actualReviewerIsAuditedForCorrectionApprovalAndRejection() throws Exception {
        var sa = login(a); var sr = login(reviewer);
        for (int i=0; i<2; i++) {
            long id = upload(sa, png(i+10), "review-"+i, 202).path("receiptId").asLong(); process(id);
            var corrected = body(correction(sr, id, receipts.findById(id).orElseThrow().version()).andExpect(status().isOk()).andReturn());
            decision(sr, id, corrected.path("version").asLong(), i==0 ? "APPROVE" : "REJECT").andExpect(status().isOk());
            var events = audits.findByReceiptIdOrderByOccurredAtAsc(id);
            assertThat(events.stream().filter(e -> e.action().name().startsWith("REVIEW_") || e.action().name().equals("FIELDS_CORRECTED")))
                    .hasSize(2).allMatch(e -> e.actor().equals("employee:"+reviewer.id()));
            mvc.perform(get("/api/receipts/{id}/audit-events", id).cookie(sr)).andExpect(status().isOk());
            correction(sa, id, receipts.findById(id).orElseThrow().version()).andExpect(status().isConflict());
        }
    }

    @Test void crossOwnerDuplicateAndIdempotencyNeverReturnOrMutateOriginalReceipt() throws Exception {
        var sa = login(a); var sb = login(b); byte[] image = png(30);
        long id = upload(sa, image, "shared-key", 202).path("receiptId").asLong();
        long version = receipts.findById(id).orElseThrow().version();
        upload(sb, png(31), "shared-key", 409); // idempotency fast path
        mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file", "same.png", "image/png", image))
                .cookie(sb).with(csrf()).header("X-Company-Id", "different-company").header("Idempotency-Key", "shared-key"))
                .andExpect(status().isConflict());
        upload(sb, image, "different-key", 409); // before duplicate marking
        assertThat(receipts.findById(id).orElseThrow().version()).isEqualTo(version);
        assertThat(jobs.findByReceiptId(id).orElseThrow().duplicateDetected()).isFalse();
        assertThat(upload(sa, image, "shared-key", 200).path("receiptId").asLong()).isEqualTo(id);
        upload(sa, image, "mark-duplicate", 200);
        long auditCount = audits.count();
        upload(sb, image, "fast-path-key", 409); // duplicate already processed
        assertThat(audits.count()).isEqualTo(auditCount);
        assertThat(receipts.count()).isOne();
    }

    @Test void concurrentCrossOwnerIdempotencyRaceHasOneWinnerWithoutDisclosure() throws Exception {
        var sa = login(a); var sb = login(b); var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        byte[] first = png(40), second = png(41);
        try {
            var futures = new ArrayList<Future<MvcResult>>();
            for (int i=0; i<2; i++) {
                var session = i==0 ? sa : sb; var image = i==0 ? first : second;
                futures.add(executor.submit(() -> {
                    start.await();
                    return mvc.perform(multipart("/api/receipts").file(new MockMultipartFile("file", "receipt.png", "image/png", image))
                            .cookie(session).with(csrf()).header("Idempotency-Key", "race-key")).andReturn();
                }));
            }
            start.countDown(); var responses = List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
            assertThat(responses).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(202,409);
            for (var result : responses) if (result.getResponse().getStatus()==409)
                assertThat(result.getResponse().getContentAsString()).doesNotContain("receiptId", "ownerEmployeeId", "imageSha256");
            assertThat(receipts.count()).isOne(); assertThat(keys.count()).isOne();
        } finally { executor.shutdownNow(); }
    }

    @Test void legacyOwnerlessReceiptIsQuarantinedForAdminReadOnly() throws Exception {
        long id = uploads.upload("internal", "legacy-key", "legacy.png", "image/png", png(50), null).receipt().id();
        process(id);
        detail(login(a), id, 404); detail(login(reviewer), id, 404);
        var adminSession = login(admin); detail(adminSession, id, 200);
        mvc.perform(get("/api/receipts/{id}/audit-events", id).cookie(adminSession)).andExpect(status().isOk());
        long version = receipts.findById(id).orElseThrow().version();
        correction(adminSession, id, version).andExpect(status().isConflict());
        decision(adminSession, id, version, "APPROVE").andExpect(status().isConflict());
        upload(login(a), png(50), "new-key", 409);
        upload(login(a), png(51), "legacy-key", 409);
        assertThat(receipts.findById(id).orElseThrow().ownerEmployeeId()).isNull();
    }

    @Test void bootstrapIsOneTimeAndConcurrentInvocationsCannotCreateTwoAdmins() throws Exception {
        employees.deleteAll(); var executor = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i=0; i<2; i++) {
                String name = "bootstrap-"+i;
                futures.add(executor.submit(() -> { start.await();
                    try { employeeService.bootstrap(name, PASSWORD); return true; }
                    catch (IllegalStateException ex) { return false; }
                }));
            }
            start.countDown();
            assertThat(List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(employees.count()).isOne();
            assertThat(employees.findAll().get(0).role()).isEqualTo(EmployeeRole.ADMIN);
            login(employees.findAll().get(0));
            assertThatThrownBy(() -> employeeService.bootstrap("replacement", PASSWORD)).isInstanceOf(IllegalStateException.class);
        } finally { executor.shutdownNow(); }
    }
}
