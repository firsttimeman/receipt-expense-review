package com.example.receipt.domain.receipt.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/** Spring Data 내부 Page 구현 대신 고정된 API 응답 형식을 제공합니다. page는 0부터 시작합니다. */
public record ReceiptPageResponse(
        List<ReceiptListItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static ReceiptPageResponse from(Page<ReceiptListItemResponse> results) {
        return new ReceiptPageResponse(results.getContent(), results.getNumber(), results.getSize(),
                results.getTotalElements(), results.getTotalPages(), results.hasNext());
    }
}
