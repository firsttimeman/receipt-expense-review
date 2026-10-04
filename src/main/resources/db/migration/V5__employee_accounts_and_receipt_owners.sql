CREATE TABLE employees (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    login_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    password_hash VARCHAR(100) NULL,
    name VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    setup_token_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    setup_expires_at DATETIME(6) NULL,
    CONSTRAINT uk_employees_login UNIQUE (login_id),
    CONSTRAINT uk_employees_setup_token UNIQUE (setup_token_hash),
    CONSTRAINT ck_employee_role CHECK (role IN ('EMPLOYEE', 'REVIEWER', 'ADMIN'))
) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE account_bootstrap_lock (id INT NOT NULL PRIMARY KEY);
INSERT INTO account_bootstrap_lock (id) VALUES (1);

-- Existing rows deliberately retain NULL ownership. They are quarantined, admin read-only.
-- Do not guess an owner from company_id or copy an HTTP header into this column.
ALTER TABLE receipts ADD COLUMN owner_employee_id BIGINT NULL,
    ADD CONSTRAINT fk_receipts_owner FOREIGN KEY (owner_employee_id) REFERENCES employees (id),
    ADD INDEX ix_receipts_owner_created (owner_employee_id, created_at);
