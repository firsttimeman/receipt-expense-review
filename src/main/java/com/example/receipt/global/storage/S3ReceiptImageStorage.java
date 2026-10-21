package com.example.receipt.global.storage;

import software.amazon.awssdk.core.SdkPlugin;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.RetryableException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;

public class S3ReceiptImageStorage implements ReceiptImageStorage {
    // Worker가 재시도 횟수와 간격을 관리하므로, SDK 안에서 읽기를 다시 시도하지 않습니다.
    private static final SdkPlugin SINGLE_READ_ATTEMPT = configuration ->
            configuration.overrideConfiguration(overrides ->
                    overrides.retryStrategy(strategy -> strategy.maxAttempts(1)));

    private static final Set<Integer> RETRYABLE_STATUS_CODES = Set.of(408, 429, 500, 502, 503, 504);

    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
            "SlowDown", "Throttling", "ThrottlingException",
            "RequestTimeout", "RequestTimeoutException", "InternalError", "ServiceUnavailable");

    private final S3Client client;

    private final String bucket;

    private final String prefix;

    public S3ReceiptImageStorage(S3Client client, String bucket, String prefix) {
        this.client = client;
        this.bucket = bucket;
        String normalized = prefix == null ? "" : prefix.trim().replaceAll("^/+|/+$", "");
        this.prefix = normalized.isEmpty() ? "" : normalized + "/";
    }

    @Override
    public String store(String companyId, String imageSha256, byte[] bytes) {
        ReceiptImageFormat format = ReceiptImageFormat.detect(bytes);
        String storageKey = ReceiptImageKey.create(companyId, imageSha256, format);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(prefix + storageKey)
                .contentType(format.contentType())
                .build();
        try {
            // 이미지 내용으로 만든 키이므로 같은 이미지 재전송도 같은 객체에 저장됩니다.
            client.putObject(request, RequestBody.fromBytes(bytes));
            return storageKey;
        } catch (SdkException exception) {
            throw new ReceiptImageStorageException("공유 저장소에 영수증 이미지를 저장하지 못했습니다.", exception);
        }
    }

    @Override
    public byte[] load(String storageKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(prefix + storageKey)
                .overrideConfiguration(overrides -> overrides.addPlugin(SINGLE_READ_ATTEMPT))
                .build();
        try {
            return client.getObjectAsBytes(request).asByteArray();
        } catch (NoSuchKeyException exception) {
            throw new ReceiptImageNotFoundException(exception);
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                throw new ReceiptImageNotFoundException(exception);
            }
            if (exception.statusCode() == 401 || exception.statusCode() == 403) {
                throw new ReceiptImageStorageException(
                        "공유 저장소의 영수증 이미지를 읽을 권한이 없습니다.", exception);
            }
            throw readFailure(exception);
        } catch (SdkException exception) {
            throw readFailure(exception);
        }
    }

    private ReceiptImageStorageException readFailure(SdkException exception) {
        return new ReceiptImageStorageException(
                "공유 저장소에서 영수증 이미지를 읽지 못했습니다.", exception, isRetryable(exception));
    }

    private boolean isRetryable(SdkException exception) {
        if (exception instanceof S3Exception response) {
            // 인증·권한 오류는 설정을 고쳐야 하므로 반복하지 않습니다.
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                return false;
            }

            String code = response.awsErrorDetails() == null ? null : response.awsErrorDetails().errorCode();
            return RETRYABLE_STATUS_CODES.contains(response.statusCode())
                    || (code != null && RETRYABLE_ERROR_CODES.contains(code));
        }

        if (exception instanceof NonRetryableException) {
            return false;
        }

        // SdkClientException에는 자격 증명·설정 오류도 포함되므로 통신 오류만 재시도합니다.
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ApiCallTimeoutException
                    || cause instanceof ApiCallAttemptTimeoutException
                    || cause instanceof RetryableException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof SocketException
                    || cause instanceof UnknownHostException
                    || cause instanceof EOFException) {
                return true;
            }
        }
        return false;
    }
}
