ALTER TABLE ai_video_tasks
    ADD COLUMN use_script_reference_video TINYINT(1) NOT NULL DEFAULT 0 AFTER auto_start_video_after_images;
