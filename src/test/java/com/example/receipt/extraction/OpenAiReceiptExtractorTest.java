package com.example.receipt.extraction;

import com.example.receipt.config.ReceiptProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiReceiptExtractorTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void missingApiKeyFailsLocallyWithoutNetworkCall() {
        OpenAiReceiptExtractor extractor = new OpenAiReceiptExtractor(
                new ReceiptProperties.OpenAi("", "gpt-5.4-mini", "https://api.openai.com"),
                new ObjectMapper());

        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest(new byte[]{1}, "image/png", "x.png")))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void wrapsRateLimitWithoutExposingResponseBody() throws Exception {
        startServer(429, "sensitive-provider-body", Duration.ZERO);

        ExtractionException exception = captureFailure(Duration.ofSeconds(1));

        assertThat(exception.getMessage()).contains("429");
        assertThat(exception.getMessage()).doesNotContain("sensitive-provider-body");
    }

    @Test
    void wrapsAuthenticationFailure() throws Exception {
        startServer(401, "unauthorized", Duration.ZERO);

        ExtractionException exception = captureFailure(Duration.ofSeconds(1));

        assertThat(exception.getMessage()).contains("401");
    }

    @Test
    void wrapsProviderServerError() throws Exception {
        startServer(503, "temporarily unavailable", Duration.ZERO);

        ExtractionException exception = captureFailure(Duration.ofSeconds(1));

        assertThat(exception.getMessage()).contains("503");
    }

    @Test
    void wrapsSlowProviderAsExtractionFailure() throws Exception {
        startServer(200, "{}", Duration.ofMillis(300));

        ExtractionException exception = captureFailure(Duration.ofMillis(30));

        assertThat(exception.getMessage()).contains("OpenAI");
    }

    private ExtractionException captureFailure(Duration responseTimeout) {
        OpenAiReceiptExtractor extractor = new OpenAiReceiptExtractor(
                new ReceiptProperties.OpenAi("test-key", "test-model", baseUrl(),
                        Duration.ofSeconds(1), responseTimeout), new ObjectMapper());
        try {
            extractor.extract(new ExtractionRequest(new byte[]{1}, "image/png", "x.png"));
            throw new AssertionError("OpenAI 장애가 ExtractionException으로 변환되어야 합니다.");
        } catch (ExtractionException exception) {
            return exception;
        }
    }

    private void startServer(int status, String body, Duration delay) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/responses", exchange -> {
            try {
                if (!delay.isZero()) Thread.sleep(delay.toMillis());
                byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    private String baseUrl() {
        return "http://localhost:" + server.getAddress().getPort();
    }
}
