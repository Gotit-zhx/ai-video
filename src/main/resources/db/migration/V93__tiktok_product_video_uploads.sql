CREATE TABLE tiktok_product_video_uploads (
 id CHAR(64) PRIMARY KEY,
 organization_id CHAR(36) NOT NULL,
 shop_id CHAR(36) NOT NULL,
 source_video_id CHAR(36) NOT NULL,
 content_sha256 CHAR(64) NOT NULL,
 destination_key CHAR(64) NOT NULL,
 byte_length BIGINT NOT NULL,
 status VARCHAR(20) NOT NULL,
 video_id VARCHAR(255) NULL,
 result_json JSON NULL,
 locked_by CHAR(36) NULL,
 lease_until TIMESTAMP(6) NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_tiktok_video_content (organization_id,shop_id,content_sha256,destination_key),
 KEY idx_tiktok_video_source (organization_id,source_video_id)
);

CREATE TABLE tiktok_product_video_upload_attempts (
 upload_id CHAR(64) NOT NULL,
 organization_id CHAR(36) NOT NULL,
 request_key CHAR(64) NOT NULL,
 status VARCHAR(20) NOT NULL,
 upload_attempts INT NOT NULL DEFAULT 0,
 message VARCHAR(2000) NULL,
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY (upload_id,request_key),
 KEY idx_tiktok_video_request (organization_id,request_key),
 CONSTRAINT fk_tiktok_video_attempt_upload FOREIGN KEY (upload_id) REFERENCES tiktok_product_video_uploads(id) ON DELETE CASCADE
);
