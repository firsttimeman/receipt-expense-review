package com.example.receipt.domain.extraction.config;

import com.example.receipt.domain.extraction.extractor.FakeReceiptExtractor;
import com.example.receipt.domain.extraction.extractor.OpenAiReceiptExtractor;
import com.example.receipt.domain.extraction.extractor.ReceiptExtractor;
import com.example.receipt.global.config.ReceiptProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExtractorConfiguration {
    @Bean
    ReceiptExtractor receiptExtractor(ReceiptProperties properties, ObjectMapper objectMapper) {
        return switch (properties.getExtractor().getProvider().toLowerCase()) {
            case "fake" -> new FakeReceiptExtractor();
            case "openai" -> new OpenAiReceiptExtractor(properties.getOpenai(), objectMapper);
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 추출 공급자입니다: " + properties.getExtractor().getProvider());
        };
    }
}
