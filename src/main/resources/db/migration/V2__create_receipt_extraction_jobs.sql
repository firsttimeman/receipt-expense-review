ALTER TABLE receipts MODIFY status VARCHAR(32) NULL;

CREATE TABLE receipt_extraction_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    version BIGINT NOT NULL DEFAULT 0,
    receipt_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    image_storage_key VARCHAR(512) NOT NULL,
    duplicate_detected BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_count INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    locked_by VARCHAR(100) NULL,
    lease_until DATETIME(6) NULL,
    last_error_code VARCHAR(100) NULL,
    last_error_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_receipt_extraction_jobs_receipt UNIQUE (receipt_id),
    CONSTRAINT fk_receipt_extraction_jobs_receipt FOREIGN KEY (receipt_id) REFERENCES receipts (id),
    INDEX ix_receipt_extraction_jobs_claim (status, available_at, id)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
