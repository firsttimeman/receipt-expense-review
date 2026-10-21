package com.example.receipt.global.storage;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3ReceiptImageStorageTest {
    private static final String READ_ERROR_MESSAGE = "공유 저장소에서 영수증 이미지를 읽지 못했습니다.";

    @ParameterizedTest(name = "{0}")
    @MethodSource("retryableFailures")
    void temporaryReadFailuresAreRetryable(String description, SdkException failure) {
        ReceiptImageStorageException result = loadFailure(failure);

        assertThat(result.isRetryable()).isTrue();
        assertThat(result).hasMessage(READ_ERROR_MESSAGE).hasCause(failure);
    }

    static Stream<Arguments> retryableFailures() {
        return Stream.of(
                Arguments.of("HTTP 500", response(500, "InternalError")),
                Arguments.of("HTTP 502", response(502, null)),
                Arguments.of("HTTP 503", response(503, "ServiceUnavailable")),
                Arguments.of("HTTP 504", response(504, null)),
                Arguments.of("HTTP 408", response(408, null)),
                Arguments.of("HTTP 429", response(429, null)),
                Arguments.of("SlowDown code", response(400, "SlowDown")),
                Arguments.of("RequestTimeout code", response(400, "RequestTimeout")),
                Arguments.of("socket read timeout", clientFailure(new SocketTimeoutException())),
                Arguments.of("connection refused", clientFailure(new ConnectException())),
                Arguments.of("connection reset", clientFailure(new SocketException())),
                Arguments.of("DNS unavailable", clientFailure(new UnknownHostException())),
                Arguments.of("incomplete response", clientFailure(new EOFException())),
                Arguments.of("API timeout", ApiCallTimeoutException.create(1_000)),
                Arguments.of("HTTP attempt timeout", ApiCallAttemptTimeoutException.create(1_000)),
                Arguments.of("nested socket timeout",
                        clientFailure(new IllegalStateException(new SocketTimeoutException())))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("permanentFailures")
    void permissionsAndConfigurationFailuresAreNotRetried(String description, SdkException failure) {
        ReceiptImageStorageException result = loadFailure(failure);

        assertThat(result.isRetryable()).isFalse();
        if (failure instanceof S3Exception response && (response.statusCode() == 401 || response.statusCode() == 403)) {
            assertThat(result).hasMessage("공유 저장소의 영수증 이미지를 읽을 권한이 없습니다.").hasCause(failure);
        } else {
            assertThat(result).hasMessage(READ_ERROR_MESSAGE).hasCause(failure);
        }
    }

    static Stream<Arguments> permanentFailures() {
        return Stream.of(
                Arguments.of("access denied", response(403, "AccessDenied")),
                Arguments.of("invalid access key", response(403, "InvalidAccessKeyId")),
                Arguments.of("invalid signature", response(403, "SignatureDoesNotMatch")),
                Arguments.of("unauthenticated", response(401, null)),
                Arguments.of("permission takes precedence over error code", response(403, "SlowDown")),
                Arguments.of("bad request", response(400, "InvalidRequest")),
                Arguments.of("incorrect endpoint", response(301, "PermanentRedirect")),
                Arguments.of("unsupported operation", response(501, "NotImplemented")),
                Arguments.of("missing credentials",
                        SdkClientException.create("Unable to load credentials: synthetic-secret")),
                Arguments.of("invalid client configuration", clientFailure(new IllegalArgumentException())),
                Arguments.of("TLS configuration", clientFailure(new SSLHandshakeException("Invalid certificate"))),
                Arguments.of("explicitly nonretryable",
                        NonRetryableException.create("permanent failure", new SocketTimeoutException()))
        );
    }

    @ParameterizedTest
    @MethodSource("missingImages")
    void missingImagesKeepTheNotFoundException(SdkException failure) {
        ReceiptImageStorageException result = loadFailure(failure);

        assertThat(result).isInstanceOf(ReceiptImageNotFoundException.class)
                .hasMessage("원본 이미지를 찾을 수 없습니다.").hasCause(failure);
        assertThat(result.isRetryable()).isFalse();
    }

    static Stream<Arguments> missingImages() {
        return Stream.of(
                Arguments.of(NoSuchKeyException.builder().message("private-object-key").build()),
                Arguments.of(response(404, "NoSuchKey"))
        );
    }

    @Test
    void existingStorageExceptionsRemainNonretryableByDefault() {
        ReceiptImageStorageException result = new ReceiptImageStorageException(
                "기존 저장소 오류", new SocketTimeoutException());

        assertThat(result.isRetryable()).isFalse();
    }

    @Test
    void oneLoadMakesOneHttpAttemptEvenWhenTheSdkClientWouldRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            attempts.incrementAndGet();
            byte[] body = "<Error><Code>SlowDown</Code><Message>Retry later</Message></Error>"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/xml");
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("synthetic-access", "synthetic-secret")))
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(overrides -> overrides
                        .retryStrategy(strategy -> strategy.maxAttempts(3))
                        .apiCallTimeout(Duration.ofSeconds(5)))
                .build()) {
            S3ReceiptImageStorage storage = new S3ReceiptImageStorage(client, "test-bucket", "images");

            ReceiptImageStorageException result = catchThrowableOfType(
                    () -> storage.load("receipt.png"), ReceiptImageStorageException.class);

            assertThat(result.isRetryable()).isTrue();
            assertThat(attempts).hasValue(1);
            assertThat(client.serviceClientConfiguration().overrideConfiguration()
                    .retryStrategy().orElseThrow().maxAttempts()).isEqualTo(3);
        } finally {
            server.stop(0);
        }
    }

    private static ReceiptImageStorageException loadFailure(SdkException failure) {
        S3Client client = mock(S3Client.class);
        when(client.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(failure);
        S3ReceiptImageStorage storage = new S3ReceiptImageStorage(client, "test-bucket", "images");

        return catchThrowableOfType(() -> storage.load("receipt.png"), ReceiptImageStorageException.class);
    }

    private static SdkClientException clientFailure(Throwable cause) {
        return SdkClientException.create("SDK diagnostic containing private details", cause);
    }

    private static SdkException response(int statusCode, String errorCode) {
        return S3Exception.builder()
                .statusCode(statusCode)
                .message("SDK diagnostic containing private details")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
                .build();
    }
}
