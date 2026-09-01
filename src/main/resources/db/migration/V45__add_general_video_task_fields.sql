ALTER TABLE ai_video_tasks
    ADD COLUMN task_type VARCHAR(32) NOT NULL DEFAULT 'PRODUCT' AFTER name,
    ADD COLUMN duration_seconds SMALLINT NOT NULL DEFAULT 12 AFTER task_type;

CREATE INDEX idx_ai_video_tasks_type_updated
    ON ai_video_tasks (organization_id, task_type, updated_at);
