package com.example.receipt;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.security.EmployeePrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.data.redis.RedisSessionRepository;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 별도 웹 서버와 Spring 컨텍스트 사이에서 실제 HTTP 쿠키와 Redis 세션을 검증합니다. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisSessionIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    private static final String PASSWORD = "distributed-session-password";

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(12).encode(PASSWORD);

    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private ServletWebServerApplicationContext first;

    private ServletWebServerApplicationContext second;

    @BeforeAll
    void startServers() {
        first = startServer();
        second = startServer();
    }

    @AfterAll
    void stopServers() {
        if (second != null) second.close();
        if (first != null) first.close();
    }

    @BeforeEach
    void createEmployee() {
        EmployeeRepository employees = first.getBean(EmployeeRepository.class);
        employees.deleteAll();
        Employee employee = new Employee("distributed-user", "분산 세션 테스트", EmployeeRole.EMPLOYEE);
        employee.setPassword(PASSWORD_HASH);
        employees.saveAndFlush(employee);

        Employee admin = new Employee("distributed-admin", "분산 세션 관리자", EmployeeRole.ADMIN);
        admin.setPassword(PASSWORD_HASH);
        employees.saveAndFlush(admin);
    }

    @Test
    void sharesLoginWithANewThirdServerAndLogsOutAcrossAllServers() throws Exception {
        Browser browser = new Browser();
        JsonNode anonymousCsrf = browser.csrf(first);
        String anonymousCookie = browser.cookie;

        HttpResponse<String> login = browser.post(second, "/api/auth/login", credentials(PASSWORD), anonymousCsrf);
        assertThat(login.statusCode()).isEqualTo(204);
        assertThat(browser.cookie).isNotEqualTo(anonymousCookie);
        assertThat(login.headers().allValues("Set-Cookie").toString()).contains("HttpOnly", "SameSite=Strict");
        assertThat(new Browser(anonymousCookie).get(first, "/api/auth/me").statusCode()).isEqualTo(401);

        Session storedSession = first.getBean(RedisSessionRepository.class).findById(sessionId(browser.cookie));
        assertThat(storedSession).isNotNull();
        SecurityContext storedContext = storedSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(storedContext.getAuthentication().getPrincipal()).isInstanceOf(EmployeePrincipal.class);
        assertThat(((EmployeePrincipal) storedContext.getAuthentication().getPrincipal()).getPassword()).isNull();

        // 로그인 후 세 번째 서버를 추가해도 재로그인이나 sticky session이 필요하지 않습니다.
        try (ServletWebServerApplicationContext third = startServer()) {
            assertLoggedIn(browser, first);
            assertLoggedIn(browser, second);
            assertLoggedIn(browser, third);

            assertThat(browser.post(third, "/api/auth/logout", "", null).statusCode()).isEqualTo(403);
            assertThat(browser.post(third, "/api/auth/logout", "", anonymousCsrf).statusCode()).isEqualTo(403);
            JsonNode csrf = browser.csrf(third);
            assertThat(browser.post(second, "/api/employees", "{}", csrf).statusCode()).isEqualTo(403);

            String authenticatedCookie = browser.cookie;
            HttpResponse<String> logout = browser.post(second, "/api/auth/logout", "", csrf);
            assertThat(logout.statusCode()).isEqualTo(204);
            assertThat(logout.headers().allValues("Set-Cookie").toString()).contains("Max-Age=0");
            for (ServletWebServerApplicationContext server : new ServletWebServerApplicationContext[]{first, second, third}) {
                assertThat(new Browser(authenticatedCookie).get(server, "/api/auth/me").statusCode()).isEqualTo(401);
            }
        }
    }

    @Test
    void keepsSessionAfterTheServerThatHandledLoginStops() throws Exception {
        Browser browser = new Browser();
        try (ServletWebServerApplicationContext temporary = startServer()) {
            assertThat(browser.post(temporary, "/api/auth/login", credentials(PASSWORD), browser.csrf(temporary))
                    .statusCode()).isEqualTo(204);
        }

        assertLoggedIn(browser, first);
        assertLoggedIn(browser, second);
        assertThat(browser.post(second, "/api/auth/logout", "", browser.csrf(first)).statusCode()).isEqualTo(204);
    }

    @Test
    void deactivationPermanentlyRevokesIdleSessionsAcrossThreeServers() throws Exception {
        Browser[] devices = {login(), login(), login()};
        Browser admin = login("distributed-admin");
        setEmployeeActive(admin, second, false);

        Browser attemptedLogin = new Browser();
        assertThat(attemptedLogin.post(first, "/api/auth/login", credentials(PASSWORD), attemptedLogin.csrf(first))
                .statusCode()).isEqualTo(401);

        // 세 기기는 비활성화된 동안 한 번도 요청하지 않습니다. 재활성화해도 이전 로그인이 살아나면 안 됩니다.
        setEmployeeActive(admin, first, true);
        try (ServletWebServerApplicationContext third = startServer()) {
            ServletWebServerApplicationContext[] servers = {first, second, third};
            for (int i = 0; i < servers.length; i++) {
                String oldSessionId = sessionId(devices[i].cookie);
                assertThat(devices[i].get(servers[i], "/api/auth/me").statusCode()).isEqualTo(401);
                assertThat(first.getBean(RedisSessionRepository.class).findById(oldSessionId)).isNull();
            }

            Browser freshLogin = login();
            for (ServletWebServerApplicationContext server : servers) {
                assertLoggedIn(freshLogin, server);
            }
        }
    }

    @Test
    void settingAnAlreadyActiveEmployeeToActiveKeepsTheirSession() throws Exception {
        Browser employee = login();
        setEmployeeActive(login("distributed-admin"), second, true);

        assertLoggedIn(employee, first);
        assertLoggedIn(employee, second);
    }

    @Test
    void rejectsSessionsCreatedBeforeSessionVersionWasIntroduced() throws Exception {
        UserDetails legacyPrincipal = User.withUsername("distributed-user").password("").roles("EMPLOYEE").build();
        Browser legacyBrowser = storeAuthentication(legacyPrincipal);
        String oldSessionId = sessionId(legacyBrowser.cookie);

        assertThat(legacyBrowser.get(second, "/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(first.getBean(RedisSessionRepository.class).findById(oldSessionId)).isNull();
        assertLoggedIn(login(), second);
    }

    @Test
    void loginStartedBeforeDeactivationCannotSaveAValidSessionAfterReactivation() throws Exception {
        // 비밀번호 검증 직전에 읽은 사용자 정보를 보관해 로그인과 비활성화의 순서가 엇갈리는 상황을 재현합니다.
        UserDetails principal = first.getBean(UserDetailsService.class).loadUserByUsername("distributed-user");
        Browser admin = login("distributed-admin");
        setEmployeeActive(admin, first, false);
        setEmployeeActive(admin, second, true);

        ((EmployeePrincipal) principal).eraseCredentials();
        Browser lateLogin = storeAuthentication(principal);
        assertThat(lateLogin.get(second, "/api/auth/me").statusCode()).isEqualTo(401);
        assertLoggedIn(login(), first);
    }

    @Test
    void expiredRedisSessionCannotAuthenticateOnEitherServer() throws Exception {
        Browser browser = login();
        String sessionId = sessionId(browser.cookie);
        RedisSessionRepository sessions = first.getBean(RedisSessionRepository.class);
        expireSession(sessions, sessionId);

        assertThat(browser.get(second, "/api/auth/me").statusCode()).isEqualTo(401);
        assertThat(browser.get(first, "/api/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void acceptsOnlyJsonAndFailedLoginDoesNotRetainPreviousAuthentication() throws Exception {
        Browser browser = new Browser();
        assertThat(browser.post(first, "/api/auth/login", credentials(PASSWORD), null).statusCode()).isEqualTo(403);
        JsonNode csrf = browser.csrf(first);
        HttpResponse<String> form = browser.send(first, "POST", "/api/auth/login",
                "loginId=distributed-user&password=" + PASSWORD, csrf, "application/x-www-form-urlencoded");
        assertThat(form.statusCode()).isEqualTo(415);
        assertThat(browser.post(first, "/api/auth/login", "{}", csrf).statusCode()).isEqualTo(400);
        HttpResponse<String> malformed = browser.post(first, "/api/auth/login", "{\"password\":\"" + PASSWORD, csrf);
        assertThat(malformed.statusCode()).isEqualTo(400);
        assertThat(malformed.body()).doesNotContain(PASSWORD);

        assertThat(browser.post(first, "/api/auth/login", credentials(PASSWORD), csrf).statusCode()).isEqualTo(204);
        HttpResponse<String> failure = browser.post(second, "/api/auth/login", credentials("wrong-password"), browser.csrf(first));
        assertThat(failure.statusCode()).isEqualTo(401);
        assertThat(failure.body()).doesNotContain("wrong-password", PASSWORD_HASH);
        assertThat(browser.get(first, "/api/auth/me").statusCode()).isEqualTo(401);
    }

    private Browser login() throws Exception {
        return login("distributed-user");
    }

    private Browser login(String loginId) throws Exception {
        Browser browser = new Browser();
        String body = json.writeValueAsString(Map.of("loginId", loginId, "password", PASSWORD));
        assertThat(browser.post(first, "/api/auth/login", body, browser.csrf(second)).statusCode()).isEqualTo(204);
        return browser;
    }

    private void setEmployeeActive(Browser admin, ServletWebServerApplicationContext server, boolean active) throws Exception {
        Long employeeId = first.getBean(EmployeeRepository.class).findByLoginId("distributed-user").orElseThrow().id();
        HttpResponse<String> response = admin.send(server, "PATCH", "/api/employees/" + employeeId + "/active",
                json.writeValueAsString(Map.of("active", active)), admin.csrf(server), "application/json");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("active").asBoolean()).isEqualTo(active);
    }

    private Browser storeAuthentication(UserDetails principal) {
        return storeAuthentication(first.getBean(RedisSessionRepository.class), principal);
    }

    private <S extends Session> Browser storeAuthentication(SessionRepository<S> sessions, UserDetails principal) {
        S session = sessions.createSession();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        sessions.save(session);
        return new Browser("JSESSIONID=" + Base64.getEncoder().encodeToString(session.getId().getBytes(StandardCharsets.UTF_8)));
    }

    private String sessionId(String cookie) {
        return new String(Base64.getDecoder().decode(cookie.substring("JSESSIONID=".length())), StandardCharsets.UTF_8);
    }

    private <S extends Session> void expireSession(SessionRepository<S> sessions, String sessionId) {
        S session = sessions.findById(sessionId);
        assertThat(session).isNotNull();
        assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofMinutes(30));

        // 대기 없이 Redis에 저장된 세션을 만료시켜 실제 요청에서 차단되는지 확인합니다.
        session.setMaxInactiveInterval(Duration.ofSeconds(1));
        session.setLastAccessedTime(Instant.now().minusSeconds(60));
        sessions.save(session);
    }

    private void assertLoggedIn(Browser browser, ServletWebServerApplicationContext server) throws Exception {
        HttpResponse<String> response = browser.get(server, "/api/auth/me");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("loginId").asText()).isEqualTo("distributed-user");
    }

    private String credentials(String password) throws Exception {
        return json.writeValueAsString(Map.of("loginId", "distributed-user", "password", password));
    }

    private ServletWebServerApplicationContext startServer() {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(ReceiptApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.datasource.hikari.maximum-pool-size=3",
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.session.redis.namespace=receipt:distributed-test:session",
                "--server.servlet.session.cookie.secure=false",
                "--receipt.worker.enabled=false",
                "--receipt.storage.s3.bucket=session-test-unused",
                "--receipt.extractor.provider=fake",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");
    }

    private class Browser {
        private String cookie;

        private Browser() {
        }

        private Browser(String cookie) {
            this.cookie = cookie;
        }

        private JsonNode csrf(ServletWebServerApplicationContext server) throws Exception {
            HttpResponse<String> response = get(server, "/api/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body());
        }

        private HttpResponse<String> get(ServletWebServerApplicationContext server, String path) throws Exception {
            return send(server, "GET", path, "", null, "application/json");
        }

        private HttpResponse<String> post(ServletWebServerApplicationContext server, String path,
                                          String body, JsonNode csrf) throws Exception {
            return send(server, "POST", path, body, csrf, "application/json");
        }

        private HttpResponse<String> send(ServletWebServerApplicationContext server, String method, String path,
                                          String body, JsonNode csrf, String contentType) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getWebServer().getPort() + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
            if (cookie != null) request.header("Cookie", cookie);
            if (csrf != null) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());

            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                HttpCookie parsed = HttpCookie.parse(header).get(0);
                if (parsed.getName().equals("JSESSIONID")) {
                    cookie = parsed.getMaxAge() == 0 ? null : parsed.getName() + "=" + parsed.getValue();
                }
            }
            return response;
        }
    }
}
