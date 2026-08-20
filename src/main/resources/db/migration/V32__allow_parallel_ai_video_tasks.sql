ALTER TABLE ai_video_tasks
    DROP INDEX uk_ai_video_tasks_active_owner,
    DROP COLUMN active_owner_key;

