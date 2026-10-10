package com.example.receipt.global.config;

import com.example.receipt.global.storage.ReceiptImageStorage;
import com.example.receipt.global.storage.S3ReceiptImageStorage;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

@Configuration
@Profile("!test")
@EnableConfigurationProperties(ReceiptStorageProperties.class)
public class ReceiptStorageConfiguration {
    @Bean
    ReceiptImageStorage receiptImageStorage(ReceiptStorageProperties properties, S3Client client) {
        return new S3ReceiptImageStorage(client, properties.getS3().getBucket(), properties.getS3().getPrefix());
    }

    @Bean(destroyMethod = "close")
    DefaultCredentialsProvider receiptStorageCredentialsProvider() {
        // 환경 변수, AWS 프로필, ECS/EC2 IAM 역할 등 SDK의 기본 자격 증명 체인을 사용합니다.
        return DefaultCredentialsProvider.builder().build();
    }

    @Bean(destroyMethod = "close")
    S3Client receiptStorageS3Client(ReceiptStorageProperties properties,
                                  DefaultCredentialsProvider receiptStorageCredentialsProvider) {
        ReceiptStorageProperties.S3 s3 = properties.getS3();
        Assert.hasText(s3.getBucket(), "S3 이미지 저장에 필요한 receipt.storage.s3.bucket을 설정해야 합니다.");
        Assert.hasText(s3.getRegion(), "S3 이미지 저장에 필요한 receipt.storage.s3.region을 설정해야 합니다.");

        var builder = S3Client.builder()
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(receiptStorageCredentialsProvider)
                .forcePathStyle(s3.isPathStyleAccess())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(s3.getConnectTimeout())
                        .socketTimeout(s3.getReadTimeout()))
                .overrideConfiguration(config -> config.apiCallTimeout(s3.getRequestTimeout()));

        if (StringUtils.hasText(s3.getEndpoint())) {
            URI endpoint = URI.create(s3.getEndpoint());
            Assert.isTrue(("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                            && endpoint.getHost() != null,
                    "S3 endpoint는 http 또는 https URL이어야 합니다.");
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }
}
