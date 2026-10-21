ALTER TABLE product_watermark_checks
 ADD COLUMN missing_result_count INT NOT NULL DEFAULT 0,
 ADD COLUMN missing_result_since TIMESTAMP(6) NULL,
 ADD COLUMN unavailable_results_json JSON NULL;
