package com.example.receipt.config;

import com.example.receipt.service.model.ReceiptWorkerIdentity;
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
        // 실행을 마친 스레드가 다음 Job을 즉시 보충할 때의 짧은 인계 공간이다.
        // Semaphore가 실행 중 + 대기 중 작업 합계를 concurrency 이하로 제한한다.
        executor.setQueueCapacity(properties.getConcurrency());
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
        if (!"openai".equalsIgnoreCase(receiptProperties.getExtractor().getProvider())) return;
        if (workerProperties.getLeaseDuration()
                .compareTo(receiptProperties.getOpenai().getResponseTimeout()) <= 0) {
            throw new IllegalArgumentException(
                    "Worker Lease 기간은 OpenAI 응답 제한 시간보다 길어야 합니다.");
        }
    }
}
