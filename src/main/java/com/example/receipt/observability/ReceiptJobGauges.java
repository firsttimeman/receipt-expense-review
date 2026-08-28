package com.example.receipt.observability;

import com.example.receipt.repository.ReceiptExtractionJobRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 재시작 후에도 실제 DB 상태를 보여주기 위해 Prometheus 조회 시점에 미완료 Job을 계산한다. */
@Component
public class ReceiptJobGauges {
    private final ReceiptExtractionJobRepository jobRepository;

    public ReceiptJobGauges(ReceiptExtractionJobRepository jobRepository, MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        Gauge.builder("receipt.jobs.unfinished", this, ReceiptJobGauges::unfinishedJobs)
                .description("QUEUED, PROCESSING, RETRY_WAIT 상태의 미완료 Job 수")
                .register(meterRegistry);
    }

    private double unfinishedJobs() {
        return jobRepository.countUnfinishedJobs();
    }
}
