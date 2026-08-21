package com.example.receipt.config;

import com.example.receipt.extraction.FakeReceiptExtractor;
import com.example.receipt.extraction.OpenAiReceiptExtractor;
import com.example.receipt.extraction.ReceiptExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExtractorConfiguration {
    @Bean
    ReceiptExtractor receiptExtractor(ReceiptProperties properties, ObjectMapper objectMapper) {
        return switch (properties.extractor().provider().toLowerCase()) {
            case "fake" -> new FakeReceiptExtractor();
            case "openai" -> new OpenAiReceiptExtractor(properties.openai(), objectMapper);
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 추출 공급자입니다: " + properties.extractor().provider());
        };
    }
}
