package com.example.receipt.domain;

public enum AuditAction {
    UPLOADED("영수증 이미지가 업로드됨"),
    QUALITY_REJECTED("이미지가 품질 검사를 통과하지 못함"),
    EXTRACTION_COMPLETED("AI 또는 Fake 추출기가 영수증 정보 추출을 완료함"),
    EXTRACTION_FAILED("영수증 정보 추출에 실패함"),
    VALIDATION_COMPLETED("결정론적 검증 및 경비 정책 검사를 완료함"),
    DUPLICATE_DETECTED("동일한 영수증 이미지의 중복 제출을 감지함"),
    FIELDS_CORRECTED("검수자가 추출 필드를 수정함"),
    REVIEW_APPROVED("검수자가 영수증을 최종 승인함"),
    REVIEW_REJECTED("검수자가 영수증을 최종 반려함");

    private final String description;

    AuditAction(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
