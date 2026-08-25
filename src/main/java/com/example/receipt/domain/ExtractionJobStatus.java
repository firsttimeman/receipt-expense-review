package com.example.receipt.domain;

/** AI 추출 작업의 기술적인 처리 상태다. 영수증의 승인·검수 상태와 분리한다. */
public enum ExtractionJobStatus {
    QUEUED("접수된 AI 추출 작업이 Worker의 처리를 기다리는 상태"),
    PROCESSING("Worker가 이미지를 읽어 품질 검사와 AI 추출을 수행 중인 상태"),
    RETRY_WAIT("AI 추출 실패 후 다음 재시도 가능 시각까지 기다리는 상태"),
    COMPLETED("이미지 품질 검사와 AI 추출 또는 안전한 상태 분기가 완료된 상태"),
    FAILED("재시도할 수 없거나 허용된 시도 횟수를 초과해 처리가 최종 실패한 상태");

    private final String description;

    ExtractionJobStatus(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
