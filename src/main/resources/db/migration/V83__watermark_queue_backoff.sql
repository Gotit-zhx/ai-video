ALTER TABLE product_watermark_queue ADD COLUMN next_attempt_at TIMESTAMP(6) NULL, ADD COLUMN throttle_count INT NOT NULL DEFAULT 0;
CREATE INDEX idx_watermark_queue_available ON product_watermark_queue(status,next_attempt_at,created_at);
