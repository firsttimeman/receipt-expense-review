package com.example.receipt.domain;

public enum ReceiptStatus {
    AUTO_APPROVED("모든 검증과 경비 정책을 통과해 자동 승인된 상태"),
    NEEDS_REVIEW("검증 또는 경비 정책 확인을 위해 사람 검수가 필요한 상태"),
    NEEDS_RECAPTURE("이미지 품질이 낮아 영수증을 다시 촬영해야 하는 상태"),
    MANUAL_ENTRY("AI 결과를 업무에 사용할 수 없어 사람이 핵심 값을 직접 입력해야 하는 상태"),
    UNREADABLE("업로드 파일을 영수증 이미지로 판독할 수 없는 상태"),
    APPROVED("검수자가 영수증을 최종 승인한 상태"),
    REJECTED("검수자가 영수증을 최종 반려한 상태");

    private final String description;

    ReceiptStatus(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
