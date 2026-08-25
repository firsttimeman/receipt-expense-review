package com.example.receipt.exception;

/** Lease 만료와 재선점으로 현재 Worker가 Job 처리 권한을 잃었을 때 발생한다. */
public class JobOwnershipLostException extends RuntimeException {
    public JobOwnershipLostException(Long jobId) {
        super("추출 작업 처리 권한을 잃었습니다. jobId=" + jobId);
    }
}
