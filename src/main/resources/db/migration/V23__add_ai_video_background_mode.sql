ALTER TABLE ai_video_tasks
    ADD COLUMN background_mode VARCHAR(32) NOT NULL DEFAULT 'SCRIPT_ORIGINAL' AFTER generation_mode;

UPDATE ai_video_tasks
SET background_mode = 'SCRIPT_ORIGINAL'
WHERE background_mode IS NULL OR background_mode = '';
