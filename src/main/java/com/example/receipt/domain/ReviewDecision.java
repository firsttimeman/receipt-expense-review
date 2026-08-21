package com.example.receipt.domain;

/**
 * 영수증 전체에 대해 검수자가 내리는 최종 업무 결정이다.
 * 규칙 엔진의 검사 결과와 달리 승인 또는 반려 상태를 직접 결정한다.
 */
public enum ReviewDecision {
    /** 검수자가 증빙을 확인하고 영수증을 최종 승인한다. */
    APPROVE,

    /** 검수자가 증빙을 확인하고 영수증을 최종 반려한다. */
    REJECT
}
