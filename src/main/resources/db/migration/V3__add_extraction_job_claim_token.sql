ALTER TABLE receipt_extraction_jobs
    ADD COLUMN claim_token VARCHAR(36) NULL AFTER lease_until;
