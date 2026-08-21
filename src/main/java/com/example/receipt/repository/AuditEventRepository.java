package com.example.receipt.repository;

import com.example.receipt.entity.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findByReceiptIdOrderByOccurredAtAsc(Long receiptId);
}
