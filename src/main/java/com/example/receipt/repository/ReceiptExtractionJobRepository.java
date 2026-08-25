package com.example.receipt.repository;

import com.example.receipt.entity.ReceiptExtractionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReceiptExtractionJobRepository extends JpaRepository<ReceiptExtractionJob, Long> {
    Optional<ReceiptExtractionJob> findByReceiptId(Long receiptId);

    @Query(value = """
            SELECT *
            FROM receipt_extraction_jobs
            WHERE status IN ('QUEUED', 'RETRY_WAIT')
              AND available_at <= :now
            ORDER BY available_at, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ReceiptExtractionJob> lockAvailableJobs(@Param("now") Instant now,
                                                  @Param("batchSize") int batchSize);

    @Query(value = """
            SELECT *
            FROM receipt_extraction_jobs
            WHERE status = 'PROCESSING'
              AND lease_until < :now
            ORDER BY lease_until, id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<ReceiptExtractionJob> lockExpiredJobs(@Param("now") Instant now,
                                                @Param("batchSize") int batchSize);
}
