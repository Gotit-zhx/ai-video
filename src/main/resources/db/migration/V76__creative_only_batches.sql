ALTER TABLE commerce_batch_items MODIFY shop_id VARCHAR(36) NULL;
CREATE TABLE commerce_batch_creation_modes (
 batch_id VARCHAR(36) PRIMARY KEY,
 organization_id VARCHAR(36) NOT NULL,
 mode VARCHAR(24) NOT NULL,
 CONSTRAINT fk_creation_mode_batch FOREIGN KEY (batch_id) REFERENCES commerce_batches(id)
);
CREATE TABLE commerce_batch_artwork_checks (
 job_id VARCHAR(36) PRIMARY KEY,
 report_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL,
 CONSTRAINT fk_artwork_check_job FOREIGN KEY (job_id) REFERENCES commerce_batch_artwork_jobs(id)
);
