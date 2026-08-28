package com.example.receipt.observability;

import com.example.receipt.repository.ReceiptExtractionJobRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReceiptMetricsTest {

    @Test
    void recordsOnlyMinimalOperationalCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReceiptMetrics metrics = new ReceiptMetrics(registry);

        metrics.recordUpload("created");
        metrics.recordUpload("duplicate");
        metrics.recordDuplicateFastPath();
        metrics.recordExtractionSuccess();
        metrics.recordExtractionRetry();
        metrics.recordExtractionFailure();
        metrics.recordRecoveredJobs(2);

        assertThat(registry.get("receipt.upload.requests").tag("outcome", "created").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("receipt.upload.duplicates").counter().count()).isEqualTo(1);
        assertThat(registry.get("receipt.upload.duplicate.fast.path").counter().count()).isEqualTo(1);
        assertThat(registry.get("receipt.extraction.successes").counter().count()).isEqualTo(1);
        assertThat(registry.get("receipt.extraction.retries").counter().count()).isEqualTo(1);
        assertThat(registry.get("receipt.extraction.final.failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("receipt.jobs.recovered").counter().count()).isEqualTo(2);
    }

    @Test
    void unfinishedJobGaugeReadsCurrentDatabaseCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReceiptExtractionJobRepository repository = mock(ReceiptExtractionJobRepository.class);
        when(repository.countUnfinishedJobs()).thenReturn(3L);
        new ReceiptJobGauges(repository, registry);

        assertThat(registry.get("receipt.jobs.unfinished").gauge().value()).isEqualTo(3);
    }
}
