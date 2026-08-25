package com.example.receipt.config;

import com.example.receipt.service.model.ReceiptWorkerIdentity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.UUID;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "receipt.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReceiptWorkerConfiguration {

    @Bean
    ReceiptWorkerIdentity receiptWorkerIdentity(ReceiptWorkerProperties properties,
                                                ReceiptProperties receiptProperties) {
        properties.validate();
        validateTimeoutAndLease(properties, receiptProperties);
        String configured = properties.getWorkerId();
        String value = configured == null || configured.isBlank()
                ? "receipt-worker-" + UUID.randomUUID()
                : configured.trim();
        if (value.length() > 100) throw new IllegalArgumentException("Worker ID는 100자를 초과할 수 없습니다.");
        return new ReceiptWorkerIdentity(value);
    }

    @Bean(name = "receiptExtractionExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskExecutor receiptExtractionExecutor(ReceiptWorkerProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getConcurrency());
        executor.setMaxPoolSize(properties.getConcurrency());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("receipt-extraction-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * Worker lease가 외부 API 응답 제한 시간보다 짧으면, 아직 호출 중인 작업을 다른 Worker가
     * 만료 작업으로 회수할 수 있다. 따라서 OpenAI 사용 시 lease가 응답 제한 시간보다 길어야 한다.
     */
    private void validateTimeoutAndLease(ReceiptWorkerProperties workerProperties,
                                         ReceiptProperties receiptProperties) {
        if (!"openai".equalsIgnoreCase(receiptProperties.extractor().provider())) return;
        if (workerProperties.getLeaseDuration()
                .compareTo(receiptProperties.openai().responseTimeout()) <= 0) {
            throw new IllegalArgumentException(
                    "Worker Lease 기간은 OpenAI 응답 제한 시간보다 길어야 합니다.");
        }
    }
}
