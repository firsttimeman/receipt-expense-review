package com.example.receipt.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@Getter
@Setter
@ConfigurationProperties(prefix = "receipt.storage")
public class ReceiptStorageProperties {
    private Provider provider = Provider.LOCAL;

    private String localDirectory = "./runtime/receipt-images";

    private S3 s3 = new S3();

    public enum Provider {
        LOCAL, S3
    }

    @Getter
    @Setter
    public static class S3 {
        private String bucket;

        private String region = "ap-northeast-2";

        private String prefix = "receipt-images";

        private String endpoint;

        private boolean pathStyleAccess = false;

        private Duration connectTimeout = Duration.ofSeconds(3);

        private Duration readTimeout = Duration.ofSeconds(10);

        private Duration requestTimeout = Duration.ofSeconds(15);
    }
}
