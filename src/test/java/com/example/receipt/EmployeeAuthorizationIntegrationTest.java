package com.example.receipt;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.service.EmployeeService;
import com.example.receipt.domain.employee.dto.SetPasswordRequest;
import com.example.receipt.domain.receipt.repository.*;
import com.example.receipt.domain.receipt.service.ReceiptUploadService;
import com.example.receipt.domain.receipt.model.ReceiptData;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.extraction.service.*;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
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
import java.math.BigDecimal;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EmployeeAuthorizationIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        r.add("spring.flyway.enabled", () -> "true");
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
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
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
    private ResultActions reissueSetupToken(Cookie session, long employeeId) throws Exception {
        return mvc.perform(post("/api/employees/{id}/setup-token", employeeId).cookie(session).with(csrf()));
    }
    private ResultActions setPassword(String token, String password) throws Exception {
        return mvc.perform(post("/api/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("token", token, "password", password))));
    }
    private String storedSetupTokenHash(long employeeId) {
        return jdbc.queryForObject("select setup_token_hash from employees where id = ?", String.class, employeeId);
    }
    private String tokenHash(String token) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private byte[] png(int marker) throws Exception {
        return png(marker, 800, 800);
    }
    private byte[] png(int marker, int width, int height) throws Exception {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); image.setRGB(0, 0, marker);
        var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
    }
    private JsonNode upload(Cookie session, byte[] bytes, String key, int status) throws Exception {
        return upload(session, bytes, key, "missing-shop-name.png", status);
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
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":"+version+",\"shopName\":\"corrected\",\"reviewerId\":\"forged-user\"}"));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void administratorCanReplaceValidOrExpiredSetupToken(boolean expired) throws Exception {
        Cookie adminSession = login(admin);
        JsonNode issued = issue(adminSession, "reissued-invite", "REVIEWER", 201);
        long employeeId = issued.path("employee").path("id").asLong();
        String oldToken = issued.path("setupToken").asText();
        Employee before = employees.findById(employeeId).orElseThrow();
        if (expired) {
            before.invite(tokenHash(oldToken), Instant.now(clock).minusSeconds(60));
            before = employees.saveAndFlush(before);
            setPassword(oldToken, PASSWORD).andExpect(status().isBadRequest());
        }
        long employeeCount = employees.count();
        Instant startedAt = Instant.now(clock);

        JsonNode renewed = body(reissueSetupToken(adminSession, employeeId)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn());

        Instant finishedAt = Instant.now(clock);
        String newToken = renewed.path("setupToken").asText();
        Instant expiresAt = Instant.parse(renewed.path("expiresAt").asText());
        assertThat(newToken).hasSize(43).isNotEqualTo(oldToken).matches("[A-Za-z0-9_-]+");
        assertThat(expiresAt).isBetween(startedAt.plus(Duration.ofHours(24)), finishedAt.plus(Duration.ofHours(24)));
        assertThat(renewed.path("employee")).isEqualTo(issued.path("employee"));
        assertThat(renewed.toString()).doesNotContain(oldToken, "passwordHash", "setupTokenHash");
        assertThat(employees.count()).isEqualTo(employeeCount);
        Employee after = employees.findById(employeeId).orElseThrow();
        assertThat(after.loginId()).isEqualTo(before.loginId());
        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.role()).isEqualTo(before.role());
        assertThat(after.active()).isEqualTo(before.active());
        assertThat(after.sessionVersion()).isEqualTo(before.sessionVersion());
        assertThat(after.passwordHash()).isNull();
        assertThat(after.setupExpiresAt()).isBetween(expiresAt.minusMillis(1), expiresAt.plusMillis(1));
        assertThat(storedSetupTokenHash(employeeId)).isEqualTo(tokenHash(newToken)).isNotEqualTo(newToken);

        setPassword(oldToken, PASSWORD).andExpect(status().isBadRequest());
        assertThat(employees.findById(employeeId).orElseThrow().passwordHash()).isNull();
        setPassword(newToken, PASSWORD).andExpect(status().isNoContent());
        setPassword(newToken, "another-password-123").andExpect(status().isBadRequest());
        assertThat(storedSetupTokenHash(employeeId)).isNull();
        assertThat(employees.findById(employeeId).orElseThrow().setupExpiresAt()).isNull();
        mvc.perform(get("/api/auth/me").cookie(login("reissued-invite", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginId").value("reissued-invite"))
                .andExpect(jsonPath("$.role").value("REVIEWER"));
    }

    @Test void setupTokenReissueRequiresAdministratorAndCsrfWithoutChangingTheToken() throws Exception {
        Cookie adminSession = login(admin);
        JsonNode issued = issue(adminSession, "protected-invite", "EMPLOYEE", 201);
        long employeeId = issued.path("employee").path("id").asLong();
        String oldToken = issued.path("setupToken").asText();
        Instant expiresAt = employees.findById(employeeId).orElseThrow().setupExpiresAt();
        long employeeCount = employees.count();

        mvc.perform(post("/api/employees/{id}/setup-token", employeeId).with(csrf()))
                .andExpect(status().isUnauthorized());
        reissueSetupToken(login(a), employeeId).andExpect(status().isForbidden());
        reissueSetupToken(login(reviewer), employeeId).andExpect(status().isForbidden());
        mvc.perform(post("/api/employees/{id}/setup-token", employeeId).cookie(adminSession))
                .andExpect(status().isForbidden());

        assertThat(employees.count()).isEqualTo(employeeCount);
        assertThat(storedSetupTokenHash(employeeId)).isEqualTo(tokenHash(oldToken));
        assertThat(employees.findById(employeeId).orElseThrow().setupExpiresAt()).isEqualTo(expiresAt);
        setPassword(oldToken, PASSWORD).andExpect(status().isNoContent());
    }

    @Test void setupTokenReissueReturnsNotFoundForMissingEmployee() throws Exception {
        long employeeCount = employees.count();
        reissueSetupToken(login(admin), Long.MAX_VALUE).andExpect(status().isNotFound());
        assertThat(employees.count()).isEqualTo(employeeCount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password-set", "inactive"})
    void setupTokenReissueRejectsIneligibleAccountsWithoutChangingThem(String state) throws Exception {
        Cookie adminSession = login(admin);
        JsonNode issued = issue(adminSession, "ineligible-invite", "EMPLOYEE", 201);
        long employeeId = issued.path("employee").path("id").asLong();
        String oldToken = issued.path("setupToken").asText();
        if ("password-set".equals(state)) {
            setPassword(oldToken, PASSWORD).andExpect(status().isNoContent());
        } else {
            Employee employee = employees.findById(employeeId).orElseThrow();
            employee.setActive(false);
            employees.saveAndFlush(employee);
        }
        Employee before = employees.findById(employeeId).orElseThrow();
        String oldHash = storedSetupTokenHash(employeeId);
        long employeeCount = employees.count();

        JsonNode response = body(reissueSetupToken(adminSession, employeeId)
                .andExpect(status().isConflict()).andReturn());

        assertThat(response.path("detail").asText()).isNotBlank();
        assertThat(response.toString()).doesNotContain(oldToken, PASSWORD, "passwordHash", "setupTokenHash", "setupToken");
        assertThat(employees.count()).isEqualTo(employeeCount);
        Employee after = employees.findById(employeeId).orElseThrow();
        assertThat(after.loginId()).isEqualTo(before.loginId());
        assertThat(after.name()).isEqualTo(before.name());
        assertThat(after.role()).isEqualTo(before.role());
        assertThat(after.active()).isEqualTo(before.active());
        assertThat(after.passwordHash()).isEqualTo(before.passwordHash());
        assertThat(after.sessionVersion()).isEqualTo(before.sessionVersion());
        assertThat(after.setupExpiresAt()).isEqualTo(before.setupExpiresAt());
        assertThat(storedSetupTokenHash(employeeId)).isEqualTo(oldHash);
        setPassword(oldToken, "replacement-password-123").andExpect(status().isBadRequest());
        if ("password-set".equals(state)) {
            login("ineligible-invite", PASSWORD);
        }
    }

    @RepeatedTest(3)
    void passwordSetupAndTokenReissueCannotOverwriteEachOther() throws Exception {
        Cookie adminSession = login(admin);
        JsonNode issued = issue(adminSession, "racing-invite", "EMPLOYEE", 201);
        long employeeId = issued.path("employee").path("id").asLong();
        String oldToken = issued.path("setupToken").asText();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> setup = executor.submit(() -> {
                ready.countDown();
                start.await();
                return setPassword(oldToken, PASSWORD).andReturn();
            });
            Future<MvcResult> reissue = executor.submit(() -> {
                ready.countDown();
                start.await();
                return reissueSetupToken(adminSession, employeeId).andReturn();
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult setupResult = setup.get(20, TimeUnit.SECONDS);
            MvcResult reissueResult = reissue.get(20, TimeUnit.SECONDS);
            String expectedPassword;

            if (setupResult.getResponse().getStatus() == 204) {
                // 비밀번호 설정이 먼저 잠금을 얻었다면 재발급은 거절해야 합니다.
                assertThat(reissueResult.getResponse().getStatus()).isEqualTo(409);
                expectedPassword = PASSWORD;
            } else {
                // 재발급이 먼저 끝났다면 이전 토큰으로 비밀번호를 설정할 수 없습니다.
                assertThat(setupResult.getResponse().getStatus()).isEqualTo(400);
                assertThat(reissueResult.getResponse().getStatus()).isEqualTo(200);
                assertThat(employees.findById(employeeId).orElseThrow().passwordHash()).isNull();
                String newToken = body(reissueResult).path("setupToken").asText();
                assertThat(storedSetupTokenHash(employeeId)).isEqualTo(tokenHash(newToken));
                expectedPassword = "replacement-password-123";
                setPassword(newToken, expectedPassword).andExpect(status().isNoContent());
                setPassword(newToken, PASSWORD).andExpect(status().isBadRequest());
            }

            setPassword(oldToken, "unexpected-password-123").andExpect(status().isBadRequest());
            Employee employee = employees.findById(employeeId).orElseThrow();
            assertThat(new BCryptPasswordEncoder().matches(expectedPassword, employee.passwordHash())).isTrue();
            assertThat(employee.setupExpiresAt()).isNull();
            assertThat(storedSetupTokenHash(employeeId)).isNull();
            login("racing-invite", expectedPassword);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test void concurrentReissuesLeaveOnlyOneUsableTokenForTheSameEmployee() throws Exception {
        Cookie firstSession = login(admin);
        Cookie secondSession = login(admin);
        JsonNode issued = issue(firstSession, "double-reissue", "EMPLOYEE", 201);
        long employeeId = issued.path("employee").path("id").asLong();
        String oldToken = issued.path("setupToken").asText();
        long employeeCount = employees.count();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Cookie session : List.of(firstSession, secondSession)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return reissueSetupToken(session, employeeId).andReturn();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            MvcResult first = futures.get(0).get(20, TimeUnit.SECONDS);
            MvcResult second = futures.get(1).get(20, TimeUnit.SECONDS);
            assertThat(first.getResponse().getStatus()).isEqualTo(200);
            assertThat(second.getResponse().getStatus()).isEqualTo(200);
            String firstToken = body(first).path("setupToken").asText();
            String secondToken = body(second).path("setupToken").asText();
            assertThat(firstToken).isNotEqualTo(secondToken);
            String currentHash = storedSetupTokenHash(employeeId);
            String usableToken = tokenHash(firstToken).equals(currentHash) ? firstToken : secondToken;
            String replacedToken = usableToken.equals(firstToken) ? secondToken : firstToken;
            assertThat(currentHash).isEqualTo(tokenHash(usableToken));
            assertThat(employees.count()).isEqualTo(employeeCount);

            setPassword(oldToken, PASSWORD).andExpect(status().isBadRequest());
            setPassword(replacedToken, PASSWORD).andExpect(status().isBadRequest());
            setPassword(usableToken, PASSWORD).andExpect(status().isNoContent());
            setPassword(usableToken, PASSWORD).andExpect(status().isBadRequest());
            assertThat(storedSetupTokenHash(employeeId)).isNull();
            login("double-reissue", PASSWORD);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
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
        long id = upload(session, png(60), "owner-correction", "missing-shop-name.png", 202)
                .path("receiptId").asLong();
        process(id);
        JsonNode before = detail(session, id, 200);
        assertThat(before.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(before.path("currentData").path("shopName").isNull()).isTrue();

        JsonNode corrected = body(mvc.perform(patch("/api/receipts/{id}/fields", id).cookie(session).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("version", before.path("version").asLong(),
                                "shopName", "수정된 상점"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(corrected.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(corrected.path("currentData").path("shopName").asText()).isEqualTo("수정된 상점");
        assertThat(corrected.path("currentData").path("totalAmount").decimalValue()).isEqualByComparingTo("12000");
        assertThat(corrected.path("originalData").path("shopName").isNull()).isTrue();
        assertThat(corrected.path("originalData").path("totalAmount").decimalValue()).isEqualByComparingTo("12000");
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
        long id = upload(session, png(61), "self-review", "missing-shop-name.png", 202)
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

    @ParameterizedTest
    @EnumSource(value = ReceiptStatus.class, names = {"NEEDS_RECAPTURE", "UNREADABLE", "MANUAL_ENTRY"})
    void reviewerCanRejectIncompleteReceiptWithoutBypassingOtherGuards(ReceiptStatus initialStatus) throws Exception {
        Cookie submitterSession = login(a);
        Cookie reviewerSession = login(reviewer);
        long id = submitIncompleteReceipt(submitterSession, initialStatus);
        var before = receipts.findById(id).orElseThrow();
        long version = before.version();
        long auditCount = audits.count();

        decision(reviewerSession, id, version, "APPROVE").andExpect(status().isConflict());
        decision(submitterSession, id, version, "REJECT").andExpect(status().isForbidden());
        decision(reviewerSession, id, version - 1, "REJECT").andExpect(status().isConflict());
        assertThat(receipts.findById(id).orElseThrow().status()).isEqualTo(initialStatus);
        assertThat(receipts.findById(id).orElseThrow().version()).isEqualTo(version);
        assertThat(audits.count()).isEqualTo(auditCount);

        String note = "원본 확인 후 증빙 불충분으로 반려";
        JsonNode rejected = body(mvc.perform(post("/api/receipts/{id}/decision", id)
                        .cookie(reviewerSession).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("version", version, "decision", "REJECT", "note", note))))
                .andExpect(status().isOk()).andReturn());
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("version").asLong()).isGreaterThan(version);
        var after = receipts.findById(id).orElseThrow();
        assertThat(after.currentData()).isEqualTo(before.currentData());
        assertThat(audits.count()).isEqualTo(auditCount + 1);
        assertThat(audits.findByReceiptIdOrderByOccurredAtAsc(id))
                .filteredOn(event -> event.action().name().equals("REVIEW_REJECTED"))
                .singleElement().satisfies(event -> {
                    assertThat(event.actor()).isEqualTo("employee:" + reviewer.id());
                    assertThat(event.previousStatus()).isEqualTo(initialStatus);
                    assertThat(event.newStatus()).isEqualTo(ReceiptStatus.REJECTED);
                    assertThat(event.details()).containsEntry("note", note);
                });

        decision(reviewerSession, id, after.version(), "APPROVE").andExpect(status().isConflict());
        decision(reviewerSession, id, after.version(), "REJECT").andExpect(status().isConflict());
        correction(reviewerSession, id, after.version()).andExpect(status().isConflict());
        assertThat(receipts.findById(id).orElseThrow().version()).isEqualTo(after.version());
        assertThat(audits.count()).isEqualTo(auditCount + 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteApprovalData")
    void approvalRequiresCurrentEssentialFields(String description, ReceiptData data, String errorMessage) throws Exception {
        long id = submitIncompleteReceipt(login(a), ReceiptStatus.MANUAL_ENTRY);
        var receipt = receipts.findById(id).orElseThrow();
        receipt.updateData(data, receipt.ruleResults(), ReceiptStatus.MANUAL_ENTRY, Instant.now());
        receipt = receipts.saveAndFlush(receipt);
        long version = receipt.version();
        long auditCount = audits.count();

        decision(login(reviewer), id, version, "APPROVE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(errorMessage));

        var unchanged = receipts.findById(id).orElseThrow();
        assertThat(unchanged.status()).isEqualTo(ReceiptStatus.MANUAL_ENTRY);
        assertThat(unchanged.currentData()).isEqualTo(data);
        assertThat(unchanged.version()).isEqualTo(version);
        assertThat(audits.count()).isEqualTo(auditCount);
    }

    private static Stream<Arguments> incompleteApprovalData() {
        LocalDate date = LocalDate.of(2026, 1, 15);
        BigDecimal amount = new BigDecimal("12000");
        String amountMessage = "승인하려면 총 금액을 0보다 크게 입력해 주세요.";
        return Stream.of(
                Arguments.of("추출 데이터 없음", null,
                        "승인하려면 상호명, 사용일, 총 금액을 먼저 입력해 주세요."),
                Arguments.of("상호명 없음", new ReceiptData(null, date, amount, null, null, List.of()),
                        "승인하려면 상호명을 입력해 주세요."),
                Arguments.of("공백 상호명", new ReceiptData("   ", date, amount, null, null, List.of()),
                        "승인하려면 상호명을 입력해 주세요."),
                Arguments.of("사용일 없음", new ReceiptData("테스트 상점", null, amount, null, null, List.of()),
                        "승인하려면 사용일을 입력해 주세요."),
                Arguments.of("총 금액 없음", new ReceiptData("테스트 상점", date, null, null, null, List.of()),
                        amountMessage),
                Arguments.of("총 금액 0", new ReceiptData("테스트 상점", date, BigDecimal.ZERO, null, null, List.of()),
                        amountMessage),
                Arguments.of("총 금액 음수", new ReceiptData("테스트 상점", date, new BigDecimal("-1"), null, null, List.of()),
                        amountMessage)
        );
    }

    @ParameterizedTest
    @EnumSource(value = ReceiptStatus.class, names = {"NEEDS_RECAPTURE", "UNREADABLE", "MANUAL_ENTRY"})
    void incompleteReceiptCanBeApprovedAfterOwnerCorrectsEssentialFields(ReceiptStatus initialStatus) throws Exception {
        Cookie submitterSession = login(a);
        Cookie reviewerSession = login(reviewer);
        long id = submitIncompleteReceipt(submitterSession, initialStatus);
        long oldVersion = receipts.findById(id).orElseThrow().version();
        JsonNode corrected = body(mvc.perform(patch("/api/receipts/{id}/fields", id)
                        .cookie(submitterSession).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("version", oldVersion,
                                "shopName", "직접 확인한 상점", "date", "2026-01-15", "totalAmount", 12000))))
                .andExpect(status().isOk()).andReturn());
        assertThat(corrected.path("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(corrected.path("originalData")).isNotEqualTo(corrected.path("currentData"));

        decision(reviewerSession, id, oldVersion, "APPROVE").andExpect(status().isConflict());
        JsonNode approved = body(decision(reviewerSession, id, corrected.path("version").asLong(), "APPROVE")
                .andExpect(status().isOk()).andReturn());
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("currentData").path("shopName").asText()).isEqualTo("직접 확인한 상점");
        assertThat(audits.findByReceiptIdOrderByOccurredAtAsc(id))
                .filteredOn(event -> event.action().name().equals("REVIEW_APPROVED"))
                .singleElement().satisfies(event -> assertThat(event.actor()).isEqualTo("employee:" + reviewer.id()));
    }

    private long submitIncompleteReceipt(Cookie session, ReceiptStatus receiptStatus) throws Exception {
        byte[] image = switch (receiptStatus) {
            case NEEDS_RECAPTURE -> png(70, 200, 300);
            case UNREADABLE -> Arrays.copyOf(png(70), 16);
            default -> png(70);
        };
        String fileName = receiptStatus == ReceiptStatus.MANUAL_ENTRY ? "manual.png" : "receipt.png";
        long id = upload(session, image, "incomplete-receipt", fileName, 202).path("receiptId").asLong();
        process(id);
        assertThat(receipts.findById(id).orElseThrow().status()).isEqualTo(receiptStatus);
        return id;
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
