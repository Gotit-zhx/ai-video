ALTER TABLE ai_video_tasks
    ADD COLUMN direct_reference_video TINYINT(1) NOT NULL DEFAULT 0 AFTER use_script_reference_video;

ALTER TABLE ai_video_task_assets
    ADD COLUMN reference_role VARCHAR(32) NULL AFTER model_id,
    ADD COLUMN reference_instruction VARCHAR(500) NULL AFTER reference_role,
    ADD COLUMN primary_reference TINYINT(1) NOT NULL DEFAULT 0 AFTER reference_instruction;

