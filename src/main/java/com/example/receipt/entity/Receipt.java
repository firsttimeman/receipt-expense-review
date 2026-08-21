package com.example.receipt.entity;

import com.example.receipt.domain.ReceiptData;
import com.example.receipt.domain.ReceiptStatus;
import com.example.receipt.domain.RuleResult;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 영수증 도메인
 */
@Entity
@Table(name = "receipts", uniqueConstraints = @UniqueConstraint(
        name = "uk_receipts_company_image_hash", columnNames = {"company_id", "image_sha256"}))
public class Receipt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "company_id", nullable = false, length = 64)
    private String companyId;

    @Column(name = "image_sha256", nullable = false, length = 64)
    private String imageSha256;

    @Column(name = "original_file_name", length = 255)
    private String originalFileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_data", columnDefinition = "json")
    private ReceiptData originalData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "current_data", columnDefinition = "json")
    private ReceiptData currentData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReceiptStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_results", nullable = false, columnDefinition = "json")
    private List<RuleResult> ruleResults = List.of();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Receipt() {
    }

    public Receipt(String companyId, String imageSha256, String originalFileName,
                   String contentType, long fileSize, ReceiptData originalData, ReceiptStatus status,
                   List<RuleResult> ruleResults, Instant createdAt) {
        this.companyId = companyId;
        this.imageSha256 = imageSha256;
        this.originalFileName = originalFileName;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.originalData = originalData;
        this.currentData = originalData;
        this.status = status;
        this.ruleResults = List.copyOf(ruleResults);
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public Long id() { return id; }
    public long version() { return version; }
    public String companyId() { return companyId; }
    public String imageSha256() { return imageSha256; }
    public String originalFileName() { return originalFileName; }
    public String contentType() { return contentType; }
    public long fileSize() { return fileSize; }
    public ReceiptData originalData() { return originalData; }
    public ReceiptData currentData() { return currentData; }
    public ReceiptStatus status() { return status; }
    public List<RuleResult> ruleResults() { return List.copyOf(ruleResults); }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }

    public void updateData(ReceiptData data, List<RuleResult> results, ReceiptStatus nextStatus, Instant at) {
        currentData = data;
        ruleResults = List.copyOf(results);
        status = nextStatus;
        updatedAt = at;
    }

    public void changeStatus(ReceiptStatus nextStatus, Instant at) {
        status = nextStatus;
        updatedAt = at;
    }
}
