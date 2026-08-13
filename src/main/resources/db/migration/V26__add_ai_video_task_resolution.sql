ALTER TABLE ai_video_tasks
    ADD COLUMN resolution VARCHAR(16) NOT NULL DEFAULT '720p' AFTER aspect_ratio;

UPDATE ai_video_tasks
SET resolution = '720p'
WHERE resolution IS NULL OR resolution = '';
