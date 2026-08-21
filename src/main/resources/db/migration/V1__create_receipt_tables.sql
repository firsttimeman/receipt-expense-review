CREATE TABLE receipts (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    company_id VARCHAR(64) NOT NULL,
    image_sha256 VARCHAR(64) NOT NULL,
    original_file_name VARCHAR(255) NULL,
    content_type VARCHAR(100) NULL,
    file_size BIGINT NOT NULL,
    original_data JSON NULL,
    current_data JSON NULL,
    status VARCHAR(32) NOT NULL,
    rule_results JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_receipts_company_image_hash UNIQUE (company_id, image_sha256),
    INDEX ix_receipts_company_status_created (company_id, status, created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE audit_events (
    id BIGINT NOT NULL AUTO_INCREMENT,
    receipt_id BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    actor VARCHAR(100) NOT NULL,
    action VARCHAR(40) NOT NULL,
    previous_status VARCHAR(32) NULL,
    new_status VARCHAR(32) NULL,
    details JSON NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_audit_events_receipt FOREIGN KEY (receipt_id) REFERENCES receipts (id),
    INDEX ix_audit_events_receipt_time (receipt_id, occurred_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE idempotency_records (
    id BIGINT NOT NULL AUTO_INCREMENT,
    company_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL COMMENT '동일 API 요청 재전송의 중복 처리를 막는 요청 키',
    receipt_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_idempotency_company_key UNIQUE (company_id, idempotency_key),
    CONSTRAINT fk_idempotency_receipt FOREIGN KEY (receipt_id) REFERENCES receipts (id),
    INDEX ix_idempotency_created_at (created_at)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
