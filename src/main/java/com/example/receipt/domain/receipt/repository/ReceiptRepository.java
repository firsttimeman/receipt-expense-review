package com.example.receipt.domain.receipt.repository;

import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    Optional<Receipt> findByCompanyIdAndImageSha256(String companyId, String imageSha256);

    // 상태나 기간이 null이면 해당 검색 조건은 적용하지 않습니다.
    @Query("""
            select r from Receipt r
            where r.ownerEmployeeId = :employeeId
              and (:status is null or r.status = :status)
              and (:fromInclusive is null or r.createdAt >= :fromInclusive)
              and (:toExclusive is null or r.createdAt < :toExclusive)
            """)
    Page<Receipt> findMyReceipts(
            @Param("employeeId") Long employeeId,
            @Param("status") ReceiptStatus status,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            Pageable pageable
    );

    // 검수할 영수증 번호부터 고릅니다. 원본·수정 JSON은 선택된 페이지의 본문 조회에서 읽습니다.
    // 본인 제출과 소유자 없는 자료의 제외 조건은 목록과 전체 건수 조회에 똑같이 적용합니다.
    @Query(value = """
            select r.id from Receipt r
            where r.ownerEmployeeId is not null
              and r.ownerEmployeeId <> :reviewerId
              and r.status in :pendingStatuses
              and (:status is null or r.status = :status)
              and (:fromInclusive is null or r.createdAt >= :fromInclusive)
              and (:toExclusive is null or r.createdAt < :toExclusive)
            """, countQuery = """
            select count(r.id) from Receipt r
            where r.ownerEmployeeId is not null
              and r.ownerEmployeeId <> :reviewerId
              and r.status in :pendingStatuses
              and (:status is null or r.status = :status)
              and (:fromInclusive is null or r.createdAt >= :fromInclusive)
              and (:toExclusive is null or r.createdAt < :toExclusive)
            """)
    Page<Long> findReviewQueueIds(
            @Param("reviewerId") Long reviewerId,
            @Param("pendingStatuses") List<ReceiptStatus> pendingStatuses,
            @Param("status") ReceiptStatus status,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            Pageable pageable
    );
}
