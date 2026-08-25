CREATE INDEX ix_receipt_extraction_jobs_recovery
    ON receipt_extraction_jobs (status, lease_until, id);
