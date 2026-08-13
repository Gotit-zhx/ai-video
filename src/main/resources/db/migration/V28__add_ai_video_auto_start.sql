ALTER TABLE ai_video_tasks
    ADD COLUMN auto_start_video_after_images TINYINT(1) NOT NULL DEFAULT 0 AFTER resolution;
