CREATE TABLE product_capture_videos (
 id CHAR(36) PRIMARY KEY, organization_id CHAR(36) NOT NULL, capture_id CHAR(36) NOT NULL,
 source_url TEXT NOT NULL, mime_type VARCHAR(40) NOT NULL, byte_length BIGINT NOT NULL,
 content_sha256 CHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_capture_video_content(organization_id,capture_id,content_sha256),
 CONSTRAINT fk_capture_video_capture FOREIGN KEY(capture_id) REFERENCES product_captures(id)
);
