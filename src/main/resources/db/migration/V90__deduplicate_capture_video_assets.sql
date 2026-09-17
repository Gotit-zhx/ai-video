ALTER TABLE product_capture_videos
 ADD COLUMN source_asset_key CHAR(64) NULL,
 ADD COLUMN superseded_by CHAR(36) NULL,
 ADD UNIQUE KEY uk_capture_video_source_asset(organization_id,capture_id,source_asset_key);
