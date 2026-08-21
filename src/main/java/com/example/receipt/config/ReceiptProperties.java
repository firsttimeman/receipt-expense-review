package com.example.receipt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

@ConfigurationProperties(prefix = "receipt")
public record ReceiptProperties(
        Extractor extractor,
        Quality quality,
        Policy policy,
        OpenAi openai
) {
    public ReceiptProperties {
        extractor = extractor == null ? new Extractor("fake") : extractor;
        quality = quality == null ? new Quality(600, 600) : quality;
        policy = policy == null
                ? new Policy(new BigDecimal("300000"), true, List.of("유흥", "카지노", "성인"))
                : policy;
        openai = openai == null
                ? new OpenAi("", "gpt-5.4-mini", "https://api.openai.com")
                : openai;
    }

    public record Extractor(String provider) {
        public Extractor {
            provider = provider == null || provider.isBlank() ? "fake" : provider;
        }
    }

    public record Quality(int minWidth, int minHeight) {
    }

    public record Policy(
            BigDecimal maxAmount,
            boolean weekendRequiresReview,
            List<String> prohibitedMerchantKeywords
    ) {
        public Policy {
            maxAmount = maxAmount == null ? new BigDecimal("300000") : maxAmount;
            prohibitedMerchantKeywords = prohibitedMerchantKeywords == null
                    ? List.of()
                    : List.copyOf(prohibitedMerchantKeywords);
        }
    }

    public record OpenAi(String apiKey, String model, String baseUrl) {
        public OpenAi {
            apiKey = apiKey == null ? "" : apiKey;
            model = model == null || model.isBlank() ? "gpt-5.4-mini" : model;
            baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com" : baseUrl;
        }
    }
}
