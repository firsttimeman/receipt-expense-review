package com.example.receipt.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

@Getter
@Setter
@ConfigurationProperties(prefix = "receipt")
public class ReceiptProperties {
    private Extractor extractor = new Extractor();
    private Quality quality = new Quality();
    private Policy policy = new Policy();
    private OpenAi openai = new OpenAi();

    public ReceiptProperties() {
    }

    public ReceiptProperties(Extractor extractor, Quality quality, Policy policy, OpenAi openai) {
        this.extractor = extractor;
        this.quality = quality;
        this.policy = policy;
        this.openai = openai;
    }

    @Getter
    @Setter
    public static class Extractor {
        private String provider = "fake";

        public Extractor() {
        }

        public Extractor(String provider) {
            this.provider = provider;
        }
    }

    @Getter
    @Setter
    public static class Quality {
        private int minWidth = 600;
        private int minHeight = 600;

        public Quality() {
        }

        public Quality(int minWidth, int minHeight) {
            this.minWidth = minWidth;
            this.minHeight = minHeight;
        }
    }

    @Getter
    @Setter
    public static class Policy {
        private BigDecimal maxAmount = new BigDecimal("300000");
        private boolean weekendRequiresReview = true;
        private List<String> prohibitedMerchantKeywords = List.of("유흥", "카지노", "성인");

        public Policy() {
        }

        public Policy(BigDecimal maxAmount, boolean weekendRequiresReview,
                      List<String> prohibitedMerchantKeywords) {
            this.maxAmount = maxAmount;
            this.weekendRequiresReview = weekendRequiresReview;
            this.prohibitedMerchantKeywords = prohibitedMerchantKeywords;
        }
    }

    @Getter
    @Setter
    public static class OpenAi {
        private String apiKey = "";
        private String model = "gpt-5.4-mini";
        private String baseUrl = "https://api.openai.com";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration responseTimeout = Duration.ofSeconds(30);

        public OpenAi() {
        }

        public OpenAi(String apiKey, String model, String baseUrl) {
            this(apiKey, model, baseUrl, Duration.ofSeconds(3), Duration.ofSeconds(30));
        }

        public OpenAi(String apiKey, String model, String baseUrl,
                      Duration connectTimeout, Duration responseTimeout) {
            this.apiKey = apiKey;
            this.model = model;
            this.baseUrl = baseUrl;
            this.connectTimeout = connectTimeout;
            this.responseTimeout = responseTimeout;
        }
    }
}
