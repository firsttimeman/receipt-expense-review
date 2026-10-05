package com.example.receipt.domain.receipt.dto;

import com.example.receipt.domain.receipt.model.ReceiptStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** from/to는 한국 시간 기준 제출일이며 양쪽 날짜를 모두 포함합니다. */
public record ReceiptListRequest(
        ReceiptStatus status,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size
) {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    public ReceiptListRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }

    public void validate() {
        if (page < 0 || size < 1 || size > 100 || (long) page * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("페이지 번호 또는 크기가 올바르지 않습니다.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("시작일은 종료일보다 늦을 수 없습니다.");
        }
        if ((from != null && (from.getYear() < 1000 || from.getYear() > 9999))
                || (to != null && (to.getYear() < 1000 || to.getYear() > 9999))) {
            throw new IllegalArgumentException("지원하지 않는 날짜입니다.");
        }
    }

    public Instant fromInclusive() {
        return from == null ? null : from.atStartOfDay(BUSINESS_ZONE).toInstant();
    }

    public Instant toExclusive() {
        return to == null ? null : to.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant();
    }

    public PageRequest pageable() {
        // 같은 시각에 접수한 자료도 페이지 사이에서 정렬 순서가 달라지지 않도록 id를 함께 사용합니다.
        return PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
}
