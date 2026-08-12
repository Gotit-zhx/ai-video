ALTER TABLE ai_video_tasks
    ADD COLUMN music_mode VARCHAR(32) NOT NULL DEFAULT 'AI_GENERATED' AFTER background_mode,
    ADD COLUMN music_file_name VARCHAR(255) NULL AFTER music_mode,
    ADD COLUMN music_file_path VARCHAR(1000) NULL AFTER music_file_name,
    ADD COLUMN music_content_type VARCHAR(120) NULL AFTER music_file_path,
    ADD COLUMN music_size_bytes BIGINT NOT NULL DEFAULT 0 AFTER music_content_type;

UPDATE ai_video_tasks
SET music_mode = 'AI_GENERATED'
WHERE music_mode IS NULL OR music_mode = '';
