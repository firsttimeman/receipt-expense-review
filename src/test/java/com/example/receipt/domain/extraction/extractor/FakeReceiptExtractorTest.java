package com.example.receipt.domain.extraction.extractor;

import com.example.receipt.domain.extraction.dto.ExtractionRequest;
import com.example.receipt.domain.extraction.exception.ExtractionException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;

class FakeReceiptExtractorTest {
    private final ExtractionRequest request = new ExtractionRequest(new byte[]{1}, "image/png", "receipt.png");

    @Test
    void waitsForConfiguredDelayAndKeepsDeterministicOutput() {
        Duration delay = Duration.ofMillis(40);
        long started = System.nanoTime();
        var result = new FakeReceiptExtractor(delay).extract(request);
        assertThat(System.nanoTime() - started).isGreaterThanOrEqualTo(delay.toNanos());
        assertThat(result).isEqualTo(new FakeReceiptExtractor().extract(request));
    }

    @Test
    void rejectsNegativeDelay() {
        assertThatThrownBy(() -> new FakeReceiptExtractor(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesInterruptWhenWorkerStops() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> new FakeReceiptExtractor(Duration.ofSeconds(2)).extract(request))
                    .isInstanceOf(ExtractionException.class).hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
