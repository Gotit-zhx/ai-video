ALTER TABLE video_scripts
    ADD COLUMN source_duration_seconds DECIMAL(10,3) NULL AFTER source_size_bytes,
    ADD COLUMN clip_start_second DECIMAL(10,3) NULL AFTER source_duration_seconds,
    ADD COLUMN clip_end_second DECIMAL(10,3) NULL AFTER clip_start_second;
