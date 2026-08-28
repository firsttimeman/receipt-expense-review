package com.example.receipt.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "receipt.worker")
public class ReceiptWorkerProperties {
    private boolean enabled = true;
    private long pollDelayMillis = 1_000;
    private Duration leaseDuration = Duration.ofSeconds(60);
    private int batchSize = 4;
    private int concurrency = 4;
    private String workerId = "";

    public void validate() {
        if (pollDelayMillis <= 0) {
            throw new IllegalArgumentException("Worker polling 간격은 0보다 커야 합니다.");
        }
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("Worker Lease 기간은 0보다 커야 합니다.");
        }
        if (batchSize <= 0 || concurrency <= 0) {
            throw new IllegalArgumentException("Worker batchSize와 concurrency는 1 이상이어야 합니다.");
        }
    }
}
