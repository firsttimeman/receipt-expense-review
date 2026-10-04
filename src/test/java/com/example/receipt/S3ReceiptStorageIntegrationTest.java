package com.example.receipt;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.global.storage.LocalReceiptImageStorage;
import com.example.receipt.global.storage.ReceiptImageStorage;
import com.example.receipt.global.storage.ReceiptImageStorageException;
import com.example.receipt.global.storage.S3ReceiptImageStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
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
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** 실제 S3 호환 서버와 독립 웹 서버를 사용하며 AWS 계정에 접근하지 않습니다. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class S3ReceiptStorageIntegrationTest {
    private static final String ACCESS_KEY = "receipt-storage-test";

    private static final String SECRET_KEY = "receipt-storage-test-secret";

    private static final String BUCKET = "receipt-storage-test";

    private static final String PASSWORD = "storage-integration-password";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

    @Container
    static final GenericContainer<?> S3_SERVER = new GenericContainer<>("adobe/s3mock:5.2.3")
            .withExposedPorts(9090)
            .waitingFor(Wait.forListeningPort());

    @TempDir
    static Path files;

    private final ObjectMapper json = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private S3Client client;

    private String previousAccessKey;

    private String previousSecretKey;

    private String previousSessionToken;

    @BeforeAll
    void prepareBucketAndTestCredentials() {
        // 앱은 운영 환경과 같은 기본 인증 체인을 사용하고, 테스트 종료 시 원래 설정을 복원합니다.
        previousAccessKey = System.getProperty("aws.accessKeyId");
        previousSecretKey = System.getProperty("aws.secretAccessKey");
        previousSessionToken = System.getProperty("aws.sessionToken");
        System.setProperty("aws.accessKeyId", ACCESS_KEY);
        System.setProperty("aws.secretAccessKey", SECRET_KEY);
        System.clearProperty("aws.sessionToken");
        client = S3Client.builder()
                .endpointOverride(endpoint())
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
        client.createBucket(request -> request.bucket(BUCKET));
    }

    @AfterAll
    void closeClientAndRestoreCredentials() {
        if (client != null) client.close();
        restoreProperty("aws.accessKeyId", previousAccessKey);
        restoreProperty("aws.secretAccessKey", previousSecretKey);
        restoreProperty("aws.sessionToken", previousSessionToken);
    }

    @Test
    void anotherServerProcessesImageAfterTheUploaderStops() throws Exception {
        byte[] png = png();
        Browser browser = new Browser();
        long receiptId;
        Path uploaderDirectory = files.resolve("uploader-disk");
        Path workerDirectory = files.resolve("worker-disk");
        Path thirdDirectory = files.resolve("third-disk");

        try (var uploader = startServer(false, uploaderDirectory)) {
            assertThat(uploader.getBean(ReceiptImageStorage.class)).isInstanceOf(S3ReceiptImageStorage.class);
            Employee employee = new Employee("s3-employee", "공유 저장소 테스트", EmployeeRole.EMPLOYEE);
            employee.setPassword(new BCryptPasswordEncoder(12).encode(PASSWORD));
            uploader.getBean(EmployeeRepository.class).saveAndFlush(employee);
            assertThat(browser.change(uploader, "/api/auth/login",
                    Map.of("loginId", employee.loginId(), "password", PASSWORD)).statusCode()).isEqualTo(204);
            HttpResponse<String> upload = browser.upload(uploader, png);
            assertThat(upload.statusCode()).as(upload.body()).isEqualTo(202);
            JsonNode accepted = json.readTree(upload.body());
            receiptId = accepted.path("receiptId").asLong();
            assertThat(accepted.path("jobStatus").asText()).isEqualTo("QUEUED");
            assertThat(Files.exists(uploaderDirectory)).isFalse();
        }

        // 업로드를 처리한 서버와 로컬 디스크 없이도 DB의 키로 공통 저장소를 읽습니다.
        try (var worker = startServer(true, workerDirectory);
             var third = startServer(false, thirdDirectory)) {
            assertThat(browser.get(worker, "/api/auth/me").statusCode()).isEqualTo(200);
            assertThat(browser.get(third, "/api/auth/me").statusCode()).isEqualTo(200);
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
                HttpResponse<String> response = browser.get(third, "/api/receipts/" + receiptId);
                assertThat(response.statusCode()).isEqualTo(200);
                JsonNode receipt = json.readTree(response.body());
                assertThat(receipt.path("jobStatus").asText()).isEqualTo("COMPLETED");
                assertThat(receipt.path("status").asText()).isEqualTo("AUTO_APPROVED");
                assertThat(receipt.path("currentData").path("totalAmount").asInt()).isEqualTo(12000);
            });
            JsonNode events = json.readTree(browser.get(worker, "/api/receipts/" + receiptId + "/audit-events").body());
            assertThat(events.toString()).contains("EXTRACTION_COMPLETED").doesNotContain("EXTRACTION_FAILED");
            assertThat(Files.exists(workerDirectory)).isFalse();
            assertThat(Files.exists(thirdDirectory)).isFalse();
            var objects = client.listObjectsV2(request -> request.bucket(BUCKET).prefix("integration/")).contents();
            assertThat(objects).hasSize(1);
            assertThat(client.getObjectAsBytes(request -> request.bucket(BUCKET).key(objects.get(0).key())).asByteArray())
                    .isEqualTo(png);
        }
    }

    @Test
    void retainsLocalKeysForMigrationAndRepeatedWritesHaveOneObject() throws Exception {
        byte[] bytes = "storage-migration-fixture".getBytes(StandardCharsets.UTF_8);
        String sha = sha256(bytes);
        var local = new LocalReceiptImageStorage(files.resolve("migration-source").toString());
        String localKey = local.store("internal", sha, bytes);
        var storage = new S3ReceiptImageStorage(client, BUCKET, "/migration/");

        // 전환 전에 기존 파일을 같은 상대 경로로 복사하면 DB 키를 수정할 필요가 없습니다.
        client.putObject(request -> request.bucket(BUCKET).key("migration/" + localKey), RequestBody.fromBytes(local.load(localKey)));
        assertThat(storage.load(localKey)).isEqualTo(bytes);
        assertThat(storage.store("internal", sha, bytes)).isEqualTo(localKey);
        assertThat(storage.store("internal", sha, bytes)).isEqualTo(localKey);
        assertThat(client.listObjectsV2(request -> request.bucket(BUCKET).prefix("migration/")).contents()).hasSize(1);

        var noPrefix = new S3ReceiptImageStorage(client, BUCKET, "");
        String key = noPrefix.store("no-prefix", sha, bytes);
        assertThat(client.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)).asByteArray()).isEqualTo(bytes);
    }

    @Test
    void missingObjectsAndBucketsUseTheExistingStorageFailureContract() {
        var storage = new S3ReceiptImageStorage(client, BUCKET, "missing");
        assertThatThrownBy(() -> storage.load("missing-image.bin"))
                .isInstanceOf(ReceiptImageStorageException.class)
                .hasCauseInstanceOf(S3Exception.class)
                .hasMessage("공유 저장소에서 영수증 이미지를 읽지 못했습니다.");

        var missingBucket = new S3ReceiptImageStorage(client, "unavailable-bucket", "");
        assertThatThrownBy(() -> missingBucket.store("internal", "a".repeat(64), new byte[]{1, 2, 3}))
                .isInstanceOf(ReceiptImageStorageException.class)
                .hasCauseInstanceOf(S3Exception.class)
                .hasMessage("공유 저장소에 영수증 이미지를 저장하지 못했습니다.");
    }

    private ServletWebServerApplicationContext startServer(boolean worker, Path directory) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(ReceiptApplication.class).run(
                "--server.port=0", "--spring.datasource.url=" + MYSQL.getJdbcUrl(),
                "--spring.datasource.username=" + MYSQL.getUsername(), "--spring.datasource.password=" + MYSQL.getPassword(),
                "--spring.datasource.hikari.maximum-pool-size=3", "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.session.redis.namespace=receipt:s3-integration:session", "--server.servlet.session.cookie.secure=false",
                "--receipt.storage.provider=s3", "--receipt.storage.local-directory=" + directory,
                "--receipt.storage.s3.bucket=" + BUCKET, "--receipt.storage.s3.region=us-east-1",
                "--receipt.storage.s3.endpoint=" + endpoint(), "--receipt.storage.s3.path-style-access=true",
                "--receipt.storage.s3.prefix=integration", "--receipt.extractor.provider=fake",
                "--receipt.worker.enabled=" + worker, "--receipt.worker.poll-delay-millis=100",
                "--receipt.worker.concurrency=1", "--receipt.worker.batch-size=1",
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private URI endpoint() {
        return URI.create("http://" + S3_SERVER.getHost() + ":" + S3_SERVER.getMappedPort(9090));
    }

    private byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void restoreProperty(String name, String value) {
        if (value == null) System.clearProperty(name);
        else System.setProperty(name, value);
    }

    private class Browser {
        private String cookie;

        private JsonNode csrf(ServletWebServerApplicationContext server) throws Exception {
            HttpResponse<String> response = get(server, "/api/auth/csrf");
            assertThat(response.statusCode()).isEqualTo(200);
            return json.readTree(response.body());
        }

        private HttpResponse<String> get(ServletWebServerApplicationContext server, String path) throws Exception {
            return send(server, "GET", path, new byte[0], null, "application/json");
        }

        private HttpResponse<String> change(ServletWebServerApplicationContext server, String path, Object body) throws Exception {
            return send(server, "POST", path, json.writeValueAsBytes(body), csrf(server), "application/json");
        }

        private HttpResponse<String> upload(ServletWebServerApplicationContext server, byte[] bytes) throws Exception {
            String boundary = "receiptS3TestBoundary";
            var out = new ByteArrayOutputStream();
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"receipt.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return send(server, "POST", "/api/receipts", out.toByteArray(), csrf(server), "multipart/form-data; boundary=" + boundary);
        }

        private HttpResponse<String> send(ServletWebServerApplicationContext server, String method, String path,
                                          byte[] bytes, JsonNode csrf, String contentType) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getWebServer().getPort() + path))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", contentType)
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(bytes));
            if (cookie != null) request.header("Cookie", cookie);
            if (csrf != null) request.header(csrf.path("headerName").asText(), csrf.path("token").asText());
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                var parsed = HttpCookie.parse(header).get(0);
                if (parsed.getName().equals("JSESSIONID")) cookie = parsed.getMaxAge() == 0 ? null : "JSESSIONID=" + parsed.getValue();
            }
            return response;
        }
    }
}
