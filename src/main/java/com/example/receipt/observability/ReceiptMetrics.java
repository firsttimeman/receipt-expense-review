package com.example.receipt.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** DB 이력을 추가하지 않고 운영에 필요한 최소 Counter만 기록한다. */
@Component
public class ReceiptMetrics {
    private final MeterRegistry registry;

    public ReceiptMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordUpload(String outcome) {
        registry.counter("receipt.upload.requests", "outcome", outcome).increment();
        if ("duplicate".equals(outcome)) {
            registry.counter("receipt.upload.duplicates").increment();
        }
    }

    public void recordDuplicateFastPath() {
        registry.counter("receipt.upload.duplicate.fast.path").increment();
    }

    public void recordExtractionSuccess() {
        registry.counter("receipt.extraction.successes").increment();
    }

    public void recordExtractionRetry() {
        registry.counter("receipt.extraction.retries").increment();
    }

    public void recordExtractionFailure() {
        registry.counter("receipt.extraction.final.failures").increment();
    }

    public void recordRecoveredJobs(int count) {
        if (count > 0) registry.counter("receipt.jobs.recovered").increment(count);
    }
}
