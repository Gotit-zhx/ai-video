ALTER TABLE product_watermark_checks
    ADD COLUMN generation_retry_count INT NOT NULL DEFAULT 0;
ALTER TABLE product_watermark_queue
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
