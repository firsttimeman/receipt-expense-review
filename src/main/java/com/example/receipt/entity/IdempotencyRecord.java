package com.example.receipt.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "idempotency_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_idempotency_company_key", columnNames = {"company_id", "idempotency_key"}))
/** 동일 API 요청이 재전송되어도 영수증을 중복 생성하지 않도록 최초 처리 결과를 기록한다. */
/**
 * 같은 api 요청이 다시 들어왔을때 최초 처리 결과를 다시 찾아주기 위한 기록
 */
public class IdempotencyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false, length = 64)
    private String companyId;

    @Column(name = "idempotency_key", nullable = false, length = 100)
    // 클라이언트가 재시도할 때 동일하게 보내는 중복 요청 방지 키
    private String idempotencyKey;

    @Column(name = "receipt_id", nullable = false)
    private Long receiptId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecord() {
    }

    public IdempotencyRecord(String companyId, String idempotencyKey, Long receiptId, Instant createdAt) {
        this.companyId = companyId;
        this.idempotencyKey = idempotencyKey;
        this.receiptId = receiptId;
        this.createdAt = createdAt;
    }

    public Long receiptId() { return receiptId; }
}
