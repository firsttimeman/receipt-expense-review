package com.example.receipt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "receipt.worker")
public class ReceiptWorkerProperties {
    private boolean enabled = true;
    private long pollDelayMillis = 1_000;
    private Duration leaseDuration = Duration.ofSeconds(60);
    private int batchSize = 4;
    private int concurrency = 4;
    private String workerId = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getPollDelayMillis() { return pollDelayMillis; }
    public void setPollDelayMillis(long pollDelayMillis) { this.pollDelayMillis = pollDelayMillis; }
    public Duration getLeaseDuration() { return leaseDuration; }
    public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }

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
