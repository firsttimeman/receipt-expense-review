package com.example.receipt.repository;

import com.example.receipt.entity.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    /** 회사와 중복 요청 방지 키가 같은 최초 처리 기록을 조회한다. */
    Optional<IdempotencyRecord> findByCompanyIdAndIdempotencyKey(String companyId, String idempotencyKey);
}
