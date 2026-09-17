ALTER TABLE video_scripts
    ADD COLUMN analysis_run_id VARCHAR(64) NULL AFTER progress,
    ADD COLUMN analysis_stage VARCHAR(64) NULL AFTER analysis_run_id,
    ADD COLUMN analysis_output MEDIUMTEXT NULL AFTER analysis_stage,
    ADD COLUMN analysis_output_characters BIGINT NOT NULL DEFAULT 0 AFTER analysis_output,
    ADD COLUMN analysis_output_updated_at DATETIME(3) NULL AFTER analysis_output_characters;
