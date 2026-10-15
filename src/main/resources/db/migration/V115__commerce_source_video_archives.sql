ALTER TABLE product_capture_videos ADD COLUMN storage_video_id CHAR(36) NULL;

-- Video bytes are immutable; freeze their file key without linking to a mutable source row.
UPDATE product_capture_videos copied
JOIN commerce_source_copies c
    ON c.organization_id=copied.organization_id AND c.capture_id=copied.capture_id
JOIN product_capture_videos original
    ON original.organization_id=c.organization_id AND original.capture_id=c.source_capture_id
    AND original.content_sha256=copied.content_sha256
SET copied.storage_video_id=COALESCE(original.storage_video_id,original.id);
