package com.example.receipt.global.storage;

import com.example.receipt.global.config.ReceiptStorageConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptStorageConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReceiptStorageConfiguration.class);

    @Test
    void usesS3WithoutAStorageProviderSetting() {
        runner.withPropertyValues("receipt.storage.s3.bucket=receipt-test")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ReceiptImageStorage.class).hasSingleBean(S3Client.class);
                    assertThat(context.getBean(ReceiptImageStorage.class)).isInstanceOf(S3ReceiptImageStorage.class);
                });
    }

    @Test
    void missingBucketFailsAtStartup() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("receipt.storage.s3.bucket");
        });
    }

    @Test
    void blankBucketFailsAtStartup() {
        runner.withPropertyValues("receipt.storage.s3.bucket=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("receipt.storage.s3.bucket");
        });
    }

    @Test
    void blankRegionFailsAtStartup() {
        runner.withPropertyValues("receipt.storage.s3.bucket=receipt-test", "receipt.storage.s3.region=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("receipt.storage.s3.region");
                });
    }

    @Test
    void invalidEndpointFailsAtStartup() {
        runner.withPropertyValues("receipt.storage.s3.bucket=receipt-test",
                        "receipt.storage.s3.endpoint=file:///tmp/images")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("S3 endpoint");
                });
    }

    @Test
    void testProfileKeepsInMemoryStorage() {
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withUserConfiguration(InMemoryReceiptImageStorage.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ReceiptImageStorage.class).doesNotHaveBean(S3Client.class);
                    assertThat(context.getBean(ReceiptImageStorage.class)).isInstanceOf(InMemoryReceiptImageStorage.class);
                });
    }
}
