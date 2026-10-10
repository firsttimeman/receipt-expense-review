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

    // 본인이 제출한 영수증과 소유자가 없는 과거 자료는 검수 목록에서 제외합니다.
    @Query("""
            select r from Receipt r
            where r.ownerEmployeeId is not null
              and r.ownerEmployeeId <> :reviewerId
              and r.status in :pendingStatuses
              and (:status is null or r.status = :status)
              and (:fromInclusive is null or r.createdAt >= :fromInclusive)
              and (:toExclusive is null or r.createdAt < :toExclusive)
            """)
    Page<Receipt> findReviewQueue(
            @Param("reviewerId") Long reviewerId,
            @Param("pendingStatuses") List<ReceiptStatus> pendingStatuses,
            @Param("status") ReceiptStatus status,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive,
            Pageable pageable
    );
}
