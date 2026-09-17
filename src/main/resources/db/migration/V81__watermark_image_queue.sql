CREATE TABLE product_watermark_queue_guard (
 id INT NOT NULL PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO product_watermark_queue_guard(id) VALUES(1);
CREATE TABLE product_watermark_queue (
 id CHAR(64) NOT NULL PRIMARY KEY,
 organization_id CHAR(36) NOT NULL,
 capture_id CHAR(36) NOT NULL,
 image_id CHAR(36) NOT NULL,
 job_id CHAR(36) NOT NULL,
 status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
 worker_token CHAR(36) NULL,
 lease_until TIMESTAMP(6) NULL,
 result_url TEXT NULL,
 result_json JSON NULL,
 error_message VARCHAR(1000) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_watermark_queue_job_image(job_id,image_id),
 INDEX idx_watermark_queue_pending(status,created_at,id),
 INDEX idx_watermark_queue_capture(organization_id,capture_id,created_at),
 CONSTRAINT fk_watermark_queue_job FOREIGN KEY(job_id) REFERENCES product_image_processing_jobs(id) ON DELETE CASCADE,
 CONSTRAINT fk_watermark_queue_image FOREIGN KEY(image_id) REFERENCES product_capture_images(id) ON DELETE CASCADE
) ENGINE=InnoDB;
